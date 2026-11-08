package dev.createmaglev.train;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TrainStatus;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.createmaglev.advancement.MaglevAdvancements;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.content.PropulsionBogeyBlock;
import dev.createmaglev.mixin.NavigationAccessor;
import dev.createmaglev.power.SubstationNetwork;
import dev.createmaglev.power.TractionPower;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative power and safety envelope for a native Create train. */
public final class TrainTraction {
    public static final float LEVITATION_HEIGHT = 0.25f;
    private static final float LIFT_STEP = 0.0125f;
    private static final double SCAN_STEP = 2;
    /** Ticks a powered/weak-supply change must persist before the train status reports it. */
    private static final int STATUS_SETTLE_TICKS = 20;
    private static final TravellingPoint.IEdgePointListener IGNORE_POINTS = (distance, point) -> false;
    private static final TravellingPoint.ITurnListener IGNORE_TURNS = (distance, edge) -> {};
    /** Tightest radius of each Bezier connection; touched only on the server thread. */
    private static final Map<BezierConnection, Double> CURVE_RADII = new WeakHashMap<>();

    private final Train train;
    private final List<BogeyDrive> drives = new ArrayList<>();
    private final List<SubstationNetwork.Substation> candidates = new ArrayList<>();
    private final List<SubstationNetwork.Substation> suppliers = new ArrayList<>();
    private final LongArrayList draws = new LongArrayList();
    private final TravellingPoint scout = new TravellingPoint();
    private List<Couple<TrackNode>> route = List.of();
    private final TravellingPoint.ITrackSelector selector = this::selectTrack;
    private TrainTier tier = TrainTier.WORKSHOP;
    private boolean classified;
    private int carriageCount;
    private boolean managed;
    private boolean allMaglev;
    private boolean powered;
    private boolean requestedBackwards;
    private float lift;
    private double speedLimit;
    private double powerLimit;
    private double powerFraction;
    private long lastTick = Long.MIN_VALUE;
    private int routeIndex;
    private String status = "workshop";
    private String pendingStatus = "workshop";
    private int pendingStatusTicks;

    private record BogeyDrive(CarriageBogey bogey, TrainTier tier) {}

    public TrainTraction(Train train) { this.train = train; }

    public TrainTier tier() { classify(); return tier; }
    public boolean managed() { classify(); return managed; }
    public boolean powered() { return powered; }
    public boolean authoritative() { return lastTick != Long.MIN_VALUE; }
    public float lift() { return lift; }
    /** Safety envelope from track, curves, supply coverage ahead and route ends, in blocks per tick. */
    public double speedLimit() { return speedLimit; }
    /** Speed this tick's delivered energy can sustain or reach; the tier top speed under full supply. */
    public double powerLimit() { return powerLimit; }
    /** Delivered share of this tick's requested traction energy, from 0 to 1. */
    public double powerFraction() { return powerFraction; }
    public String status() { return status; }

    public Component statusMessage() {
        return status.equals("no_power")
                ? Component.translatable("message.create_maglev.no_power", MaglevConfig.supplyRadius())
                : Component.translatable("message.create_maglev." + status);
    }

    private void classify() {
        if (train.carriages == null || classified && carriageCount == train.carriages.size()) return;
        classified = true;
        carriageCount = train.carriages.size();
        drives.clear();
        tier = TrainTier.MAGLEV;
        allMaglev = !train.carriages.isEmpty();
        for (Carriage carriage : train.carriages) {
            classifyBogey(carriage.leadingBogey());
            if (carriage.isOnTwoBogeys()) classifyBogey(carriage.trailingBogey());
        }
        managed = !drives.isEmpty();
        if (!managed) tier = TrainTier.WORKSHOP;
        speedLimit = tier.topSpeed();
        powerLimit = tier.topSpeed();
    }

    private void classifyBogey(CarriageBogey bogey) {
        AbstractBogeyBlock<?> block = bogey.getStyle().getBlockForSize(bogey.getSize());
        TrainTier bogeyTier = block instanceof PropulsionBogeyBlock propulsion ? propulsion.tier() : TrainTier.WORKSHOP;
        if (bogeyTier.ordinal() < tier.ordinal()) tier = bogeyTier;
        allMaglev &= bogeyTier == TrainTier.MAGLEV;
        if (bogeyTier != TrainTier.WORKSHOP) drives.add(new BogeyDrive(bogey, bogeyTier));
    }

    /** Called once before native schedule/navigation updates, never by a speed getter. */
    public void tick(Level level) {
        classify();
        if (!managed || level.isClientSide || level.getServer() == null) return;
        MinecraftServer server = level.getServer();
        long tick = server.getTickCount();
        if (lastTick == tick) return;
        lastTick = tick;
        powered = false;
        powerFraction = 0;
        speedLimit = tier.topSpeed();
        powerLimit = tier.topSpeed();
        suppliers.clear();
        draws.clear();
        boolean compatible = train.graph != null && !train.derailed;
        for (BogeyDrive drive : drives) {
            compatible &= inspectPoint(drive.bogey().leading(), drive.tier());
            compatible &= inspectPoint(drive.bogey().trailing(), drive.tier());
        }
        // Every vehicle limits the consist, including unpowered stock coupled to an electric vehicle.
        if (compatible) {
            for (Carriage carriage : train.carriages) {
                compatible &= inspectPoint(carriage.getLeadingPoint(), tier);
                compatible &= inspectPoint(carriage.getTrailingPoint(), tier);
            }
        }

        double speed = Math.abs(train.speed);
        // Last tick's control intent decides whether this tick also asks for acceleration energy.
        boolean accelerating = Math.abs(train.targetSpeed) > speed + 1.0E-4;
        long requested = 0;
        long idle = 0;
        double speedCoefficient = 0;
        boolean covered = compatible;
        if (compatible) {
            for (BogeyDrive drive : drives) {
                TrainTier driveTier = drive.tier();
                double nextSpeed = accelerating ? Math.min(speed + driveTier.acceleration(), driveTier.topSpeed()) : speed;
                long cost = driveTier.energyCost(nextSpeed);
                requested += cost;
                idle += driveTier.idleEnergy();
                speedCoefficient += driveTier.speedEnergyCoefficient();
                covered &= reserve(server, drive.bogey().leading(), (cost + 1) / 2, tick);
                covered &= reserve(server, drive.bogey().trailing(), cost / 2, tick);
            }
        }
        long delivered = 0;
        for (int i = 0; i < draws.size(); i++) delivered += draws.getLong(i);
        // Below the idle draw nothing can be held up or levitated, so nothing is consumed either.
        if (covered && delivered >= idle) {
            for (int i = 0; i < suppliers.size(); i++) suppliers.get(i).deliver(draws.getLong(i), tick);
            powered = true;
            powerFraction = requested == 0 ? 1 : Math.min(1, (double) delivered / requested);
            if (delivered < requested) {
                // Weak supply limits speed to what the delivered energy sustains and scales acceleration.
                double sustainable = speedCoefficient <= 0 ? tier.topSpeed() : Math.sqrt((delivered - idle) / speedCoefficient);
                powerLimit = accelerating ? Math.min(sustainable, speed + tier.acceleration() * powerFraction) : sustainable;
            }
        }
        updateStatus(!compatible ? "incompatible_track" : !powered ? "no_power" : powerFraction < 1 ? "low_power" : "powered");
        if (powered) {
            scanAhead(server);
        } else {
            speedLimit = 0;
            powerLimit = 0;
        }
        lift = Mth.approach(lift, powered && allMaglev ? LEVITATION_HEIGHT : 0, LIFT_STEP);
        if (allMaglev && lift >= LEVITATION_HEIGHT && speed > 0.05 && tick % 20 == 0) rewardLevitatingRiders();
    }

    private boolean inspectPoint(TravellingPoint point, TrainTier bogeyTier) {
        if (point.edge == null || point.node1 == null || point.node2 == null) return false;
        TrackMaterial material = point.edge.getTrackMaterial();
        boolean compatible = material.trackType == (bogeyTier == TrainTier.MAGLEV ? MaglevContent.MAGLEV_TYPE : TrackMaterial.TrackType.STANDARD);
        speedLimit = Math.min(speedLimit, limitForEdge(point.edge));
        return compatible;
    }

    /** Reserves one pickup's energy from covering substations, nearest first; false when nothing covers it. */
    private boolean reserve(MinecraftServer server, TravellingPoint point, long amount, long tick) {
        if (point.edge == null || point.node1 == null || point.edge.isInterDimensional()) return false;
        ServerLevel dimension = server.getLevel(point.node1.getLocation().dimension);
        if (dimension == null) return false;
        TractionPower.collectSupplies(dimension, point.getPosition(train.graph), candidates);
        if (candidates.isEmpty()) return false;
        long remaining = amount;
        for (int i = 0; i < candidates.size() && remaining > 0; i++) {
            SubstationNetwork.Substation substation = candidates.get(i);
            int index = suppliers.indexOf(substation);
            long reserved = index == -1 ? 0 : draws.getLong(index);
            long take = Math.min(remaining, substation.availableOutput(tick) - reserved);
            if (take <= 0) continue;
            if (index == -1) {
                suppliers.add(substation);
                draws.add(take);
            } else {
                draws.set(index, reserved + take);
            }
            remaining -= take;
        }
        return true;
    }

    private void updateStatus(String next) {
        if (next.equals(status)) {
            pendingStatus = status;
            pendingStatusTicks = 0;
            return;
        }
        // Losing power is reported at once; flips between full and weak supply must persist first.
        if (supplied(next) && supplied(status)) {
            if (!next.equals(pendingStatus)) {
                pendingStatus = next;
                pendingStatusTicks = 0;
            }
            if (++pendingStatusTicks < STATUS_SETTLE_TICKS) return;
        }
        status = next;
        pendingStatus = next;
        pendingStatusTicks = 0;
        train.status.addMessage(new TrainStatus.StatusMessage(statusMessage()));
    }

    private static boolean supplied(String status) {
        return status.equals("powered") || status.equals("low_power");
    }

    private double limitForEdge(TrackEdge edge) {
        TrackMaterial material = edge.getTrackMaterial();
        if (material.trackType != (allMaglev ? MaglevContent.MAGLEV_TYPE : TrackMaterial.TrackType.STANDARD)) return 0;
        double limit = tier == TrainTier.BULLET && material != MaglevContent.REINFORCED_MATERIAL
                ? Math.min(tier.topSpeed(), MaglevConfig.bulletSpeedOnOrdinaryTrack()) : tier.topSpeed();
        return edge.isTurn() ? Math.min(limit, curveSpeedLimit(edge.getTurn())) : limit;
    }

    /** Lateral-acceleration limit sqrt(a * r) for the tightest part of a curve, never below the configured floor. */
    public static double curveSpeedLimit(BezierConnection turn) {
        double radius = curveRadius(turn);
        if (!Double.isFinite(radius)) return Double.POSITIVE_INFINITY;
        return Math.max(MaglevConfig.minimumCurveSpeed(), Math.sqrt(MaglevConfig.curveLateralAcceleration() * radius));
    }

    /** Create's arc radius for circular turns; sampled curvature for S-bends and slopes, where Create reports 0. */
    public static double curveRadius(BezierConnection turn) {
        Double cached = CURVE_RADII.get(turn);
        if (cached != null) return cached;
        double radius = turn.getRadius();
        if (!(radius > 0) || !Double.isFinite(radius)) radius = sampledMinimumRadius(turn);
        CURVE_RADII.put(turn, radius);
        return radius;
    }

    private static double sampledMinimumRadius(BezierConnection turn) {
        int samples = Math.max(8, turn.getSegmentCount() * 2);
        double minimum = Double.POSITIVE_INFINITY;
        Vec3 first = turn.getPosition(0);
        Vec3 second = turn.getPosition(1.0 / samples);
        for (int i = 2; i <= samples; i++) {
            Vec3 third = turn.getPosition((double) i / samples);
            minimum = Math.min(minimum, circumradius(first, second, third));
            first = second;
            second = third;
        }
        return minimum;
    }

    private static double circumradius(Vec3 a, Vec3 b, Vec3 c) {
        double twiceArea = b.subtract(a).cross(c.subtract(a)).length();
        if (twiceArea < 1.0E-9) return Double.POSITIVE_INFINITY;
        return a.distanceTo(b) * b.distanceTo(c) * c.distanceTo(a) / (2 * twiceArea);
    }

    /** A private travelling point follows the actual route without consuming native path entries. */
    private void scanAhead(MinecraftServer server) {
        if (train.carriages.isEmpty() || train.graph == null) return;
        boolean backwards = train.speed < 0 || train.speed == 0
                && (train.navigation.destination != null ? train.navigation.destinationBehindTrain
                        : train.targetSpeed != 0 ? train.targetSpeed < 0 : requestedBackwards);
        TravellingPoint front = backwards ? train.carriages.get(train.carriages.size() - 1).getTrailingPoint() : train.carriages.get(0).getLeadingPoint();
        if (front.edge == null || front.node1 == null) { speedLimit = 0; return; }
        scout.node1 = front.node1;
        scout.node2 = front.node2;
        scout.edge = front.edge;
        scout.position = front.position;
        scout.upsideDown = front.upsideDown;
        route = train.navigation.destination == null ? List.of() : ((NavigationAccessor) train.navigation).maglev$path();
        routeIndex = 0;
        double braking = MaglevConfig.emergencyBraking();
        double prospectiveSpeed = Math.min(tier.topSpeed(), Math.abs(train.speed) + tier.acceleration());
        double horizon = Math.max(8, prospectiveSpeed * prospectiveSpeed / (2 * braking) + 4);
        if (train.navigation.destination != null) {
            // Infrastructure after a planned stop is not part of this movement authority.
            horizon = Math.min(horizon, Math.max(0, train.navigation.distanceToDestination));
        }
        double travelled = 0;
        // Native travel ignores sub-epsilon distances; do not use its result as the loop cursor.
        for (double requested = 0; requested < horizon; requested += SCAN_STEP) {
            double step = Math.min(SCAN_STEP, horizon - requested);
            double actual = Math.abs(scout.travel(train.graph, backwards ? -step : step, selector, IGNORE_POINTS, IGNORE_TURNS));
            travelled += actual;
            if (scout.edge == null) { restrictForDistance(0, travelled, braking); break; }
            double edgeLimit = limitForEdge(scout.edge);
            if (!scout.edge.isInterDimensional()) {
                ServerLevel dimension = server.getLevel(scout.node1.getLocation().dimension);
                if (dimension == null || !TractionPower.hasChargedSupply(dimension, scout.getPosition(train.graph))) edgeLimit = 0;
            }
            restrictForDistance(edgeLimit, travelled, braking);
            if (scout.blocked || actual < step - 0.001) { restrictForDistance(0, travelled, braking); break; }
        }
    }

    private void restrictForDistance(double limit, double distance, double braking) {
        // Two samples plus a margin account for a boundary between graph samples.
        double envelope = Math.sqrt(limit * limit + 2 * braking * Math.max(0, distance - 4));
        speedLimit = Math.min(speedLimit, envelope);
    }

    private Map.Entry<TrackNode, TrackEdge> selectTrack(TrackGraph graph, Pair<Boolean, List<Map.Entry<TrackNode, TrackEdge>>> choices) {
        List<Map.Entry<TrackNode, TrackEdge>> options = choices.getSecond();
        if (train.navigation.destination != null) {
            if (routeIndex < route.size()) {
                TrackEdge target = graph.getConnection(route.get(routeIndex));
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).getValue() == target) { routeIndex++; return options.get(i); }
                }
            }
            return options.get(0);
        }
        double target = switch (train.manualSteer) { case LEFT -> -1; case RIGHT -> 1; case NONE -> 0; };
        Vec3 normal = scout.edge.getDirection(false).cross(scout.edge.getNormal(graph, 0));
        double closest = Double.MAX_VALUE;
        Map.Entry<TrackNode, TrackEdge> best = options.get(0);
        for (int i = 0; i < options.size(); i++) {
            Map.Entry<TrackNode, TrackEdge> option = options.get(i);
            double difference = Math.abs(target - normal.dot(option.getValue().getDirection(true)));
            if (difference <= closest) { closest = difference; best = option; }
        }
        return best;
    }

    private void rewardLevitatingRiders() {
        for (Carriage carriage : train.carriages) {
            carriage.forEachPresentEntity(entity -> {
                for (Entity passenger : entity.getPassengers()) {
                    if (passenger instanceof ServerPlayer player) MaglevAdvancements.rodeLevitatingMaglev(player);
                }
            });
        }
    }

    public void constrainTarget() {
        if (!managed()) return;
        // Preserve direction even when a terminal's current envelope clamps output to zero.
        if (train.targetSpeed != 0) requestedBackwards = train.targetSpeed < 0;
        double limit = Math.min(speedLimit, powerLimit);
        train.targetSpeed = powered ? Mth.clamp(train.targetSpeed, -limit, limit) : 0;
    }

    /** Applies before native movement, including native navigation's direct speed writes. */
    public void enforceSafety() {
        if (!managed()) return;
        constrainTarget();
        double permitted = powered ? speedLimit : 0;
        if (Math.abs(train.speed) > permitted) {
            double speed = Math.max(permitted, Math.abs(train.speed) - MaglevConfig.emergencyBraking());
            train.speed = Math.copySign(speed, train.speed);
        }
    }

    public void write(CompoundTag tag) {
        if (managed()) tag.putFloat("MaglevLift", lift);
    }

    public void read(CompoundTag tag) {
        float storedLift = tag.getFloat("MaglevLift");
        lift = Float.isFinite(storedLift) ? Mth.clamp(storedLift, 0, LEVITATION_HEIGHT) : 0;
        powered = false;
        lastTick = Long.MIN_VALUE;
    }
}
