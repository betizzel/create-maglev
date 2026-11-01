package dev.createmaglev.train;

import net.createmod.catnip.data.Pair;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TrainStatus;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.content.PropulsionBogeyBlock;
import dev.createmaglev.mixin.NavigationAccessor;
import dev.createmaglev.power.TractionPower;
import dev.createmaglev.power.TractionSubstationBlockEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.createmod.catnip.data.Couple;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.esl.transaction.Transaction;
import org.antarcticgardens.esl.transaction.TransactionStack;

/** Server-authoritative power and safety envelope for a native Create train. */
public final class TrainTraction {
    public static final double BRAKING = 0.04;
    public static final float LEVITATION_HEIGHT = 0.25f;
    private static final float LIFT_STEP = 0.0125f;
    private static final double CURVE_SPEED = 1.0;
    private static final double SCAN_STEP = 2;
    private static final TravellingPoint.IEdgePointListener IGNORE_POINTS = (distance, point) -> false;
    private static final TravellingPoint.ITurnListener IGNORE_TURNS = (distance, edge) -> {};

    private final Train train;
    private final List<BogeyDrive> drives = new ArrayList<>();
    private final List<TractionSubstationBlockEntity> suppliers = new ArrayList<>();
    private final LongArrayList demands = new LongArrayList();
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
    private long lastTick = Long.MIN_VALUE;
    private int routeIndex;
    private String status = "workshop";

    private record BogeyDrive(CarriageBogey bogey, TrainTier tier) {}

    public TrainTraction(Train train) { this.train = train; }

    public TrainTier tier() { classify(); return tier; }
    public boolean managed() { classify(); return managed; }
    public boolean powered() { return powered; }
    public boolean authoritative() { return lastTick != Long.MIN_VALUE; }
    public float lift() { return lift; }
    public double speedLimit() { return speedLimit; }
    public String status() { return status; }

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
        long tick = level.getServer().getTickCount();
        if (lastTick == tick) return;
        lastTick = tick;
        suppliers.clear();
        demands.clear();
        powered = false;
        speedLimit = tier.topSpeed();
        boolean compatible = train.graph != null && !train.derailed;
        boolean supplied = compatible;
        for (int driveIndex = 0; driveIndex < drives.size(); driveIndex++) {
            BogeyDrive drive = drives.get(driveIndex);
            CarriageBogey bogey = drive.bogey();
            long cost = drive.tier().energyCost(train.speed);
            compatible &= inspectPoint(bogey.leading(), drive.tier());
            compatible &= inspectPoint(bogey.trailing(), drive.tier());
            supplied &= requestPoint(level, bogey.leading(), (cost + 1) / 2);
            supplied &= requestPoint(level, bogey.trailing(), cost / 2);
        }
        // Every vehicle limits the consist, including unpowered stock coupled to an electric vehicle.
        if (compatible) {
            for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
                Carriage carriage = train.carriages.get(carriageIndex);
                compatible &= inspectPoint(carriage.getLeadingPoint(), tier);
                compatible &= inspectPoint(carriage.getTrailingPoint(), tier);
            }
        }
        if (compatible && supplied && !suppliers.isEmpty()) {
            boolean affordable = true;
            for (int i = 0; i < suppliers.size(); i++) affordable &= suppliers.get(i).energyStored() >= demands.getLong(i);
            if (affordable) {
                try (Transaction transaction = TransactionStack.get().openOuter()) {
                    powered = true;
                    for (int i = 0; i < suppliers.size(); i++) {
                        if (!suppliers.get(i).consume(demands.getLong(i), transaction)) {
                            powered = false;
                            break;
                        }
                    }
                    if (powered) transaction.commit();
                }
            }
        }
        String nextStatus = !compatible ? "incompatible_track" : !powered ? "no_power" : "powered";
        if (!nextStatus.equals(status)) {
            train.status.addMessage(new TrainStatus.StatusMessage(Component.translatable("message.create_maglev." + nextStatus)));
            status = nextStatus;
        }
        if (powered) scanAhead(level);
        else speedLimit = 0;
        lift = Mth.approach(lift, powered && allMaglev ? LEVITATION_HEIGHT : 0, LIFT_STEP);
    }

    private boolean inspectPoint(TravellingPoint point, TrainTier bogeyTier) {
        if (point.edge == null || point.node1 == null || point.node2 == null) return false;
        TrackMaterial material = point.edge.getTrackMaterial();
        boolean compatible = material.trackType == (bogeyTier == TrainTier.MAGLEV ? MaglevContent.MAGLEV_TYPE : TrackMaterial.TrackType.STANDARD);
        speedLimit = Math.min(speedLimit, limitForEdge(point.edge));
        return compatible;
    }

    private boolean requestPoint(Level overworld, TravellingPoint point, long amount) {
        if (point.edge == null || point.node1 == null || point.edge.isInterDimensional()) return false;
        ServerLevel dimension = overworld.getServer().getLevel(point.node1.getLocation().dimension);
        if (dimension == null) return false;
        TractionSubstationBlockEntity supply = TractionPower.find(dimension, point.getPosition(train.graph));
        if (supply == null) return false;
        int index = suppliers.indexOf(supply);
        if (index == -1) {
            suppliers.add(supply);
            demands.add(amount);
        } else demands.set(index, demands.getLong(index) + amount);
        return true;
    }

    private double limitForEdge(TrackEdge edge) {
        TrackMaterial material = edge.getTrackMaterial();
        if (material.trackType != (allMaglev ? MaglevContent.MAGLEV_TYPE : TrackMaterial.TrackType.STANDARD)) return 0;
        double limit = tier == TrainTier.BULLET && material != MaglevContent.REINFORCED_MATERIAL ? TrainTier.ELECTRIC.topSpeed() : tier.topSpeed();
        return edge.isTurn() ? Math.min(CURVE_SPEED, limit) : limit;
    }

    /** A private travelling point follows the actual route without consuming native path entries. */
    private void scanAhead(Level overworld) {
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
        double prospectiveSpeed = Math.min(tier.topSpeed(), Math.abs(train.speed) + tier.acceleration());
        double horizon = Math.max(8, prospectiveSpeed * prospectiveSpeed / (2 * BRAKING) + 4);
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
            if (scout.edge == null) { restrictForDistance(0, travelled); break; }
            double edgeLimit = limitForEdge(scout.edge);
            if (!scout.edge.isInterDimensional()) {
                ServerLevel dimension = overworld.getServer().getLevel(scout.node1.getLocation().dimension);
                TractionSubstationBlockEntity supply = dimension == null ? null : TractionPower.find(dimension, scout.getPosition(train.graph));
                if (supply == null || supply.energyStored() == 0) edgeLimit = 0;
            }
            restrictForDistance(edgeLimit, travelled);
            if (scout.blocked || actual < step - 0.001) { restrictForDistance(0, travelled); break; }
        }
    }

    private void restrictForDistance(double limit, double distance) {
        // Two samples plus a margin account for a boundary between graph samples.
        double envelope = Math.sqrt(limit * limit + 2 * BRAKING * Math.max(0, distance - 4));
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

    public void constrainTarget() {
        if (!managed()) return;
        // Preserve direction even when a terminal's current envelope clamps output to zero.
        if (train.targetSpeed != 0) requestedBackwards = train.targetSpeed < 0;
        train.targetSpeed = powered ? Mth.clamp(train.targetSpeed, -speedLimit, speedLimit) : 0;
    }

    /** Applies before native movement, including native navigation's direct speed writes. */
    public void enforceSafety() {
        if (!managed()) return;
        constrainTarget();
        double permitted = powered ? speedLimit : 0;
        if (Math.abs(train.speed) > permitted) {
            double speed = Math.max(permitted, Math.abs(train.speed) - BRAKING);
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
