package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TrainTraction;
import dev.createmaglev.train.TractionState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.antarcticgardens.esl.energy.EnergyStorage;
import org.antarcticgardens.esl.transaction.Transaction;
import org.antarcticgardens.esl.transaction.TransactionStack;

/** Native scheduled-train checks for rated speeds and the radius-derived curve envelope. */
public final class NativeSpeedEnvelopeTests implements FabricGameTest {
    /** Kept out of Create's seeded default batch so its random recipe rolls stay reproducible. */
    private static final String BATCH = "create_maglev_speed_envelope";
    private static final int REGION_SPACING = 1024;
    private static final int ASSEMBLY_TICK = 20;
    private static final int ROUTE_END_MARGIN = 48;
    private static final int CURVE_APPROACH_MARGIN = 48;
    private static final int LIVE_CARRIAGE_GRACE_TICKS = 8;
    private static final int CURVE_RADIUS = 64;
    private static final int TEST_CHARGE = 8192;

    @GameTest(template = "create_maglev_tests:arena", batch = BATCH, timeoutTicks = 10000, attempts = 1)
    public void electricTrainReachesRatedTopSpeedAndStopsAtDestination(GameTestHelper helper) {
        TrainTier tier = TrainTier.ELECTRIC;
        double targetSpeed = tier.topSpeed();
        int distanceToTopSpeed = accelerationDistance(targetSpeed, tier.acceleration());
        int routeLength = distanceToTopSpeed * 2 + 2 * ROUTE_END_MARGIN;
        NativeTrainFixture.BuiltTrain built = chargedRoute(helper, 0, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "speed-envelope-electric", MaglevContent.ELECTRIC_BOGEY,
                routeLength, 0, 0);
        observeScheduledRoute(helper, built, tier, targetSpeed, routeLength, false, false);
    }

    @GameTest(template = "create_maglev_tests:arena", batch = BATCH, timeoutTicks = 10000, attempts = 1)
    public void reinforcedBulletTrainReachesRatedTopSpeedAndStopsAtDestination(GameTestHelper helper) {
        TrainTier tier = TrainTier.BULLET;
        double targetSpeed = tier.topSpeed();
        int distanceToTopSpeed = accelerationDistance(targetSpeed, tier.acceleration());
        int routeLength = distanceToTopSpeed * 2 + 2 * ROUTE_END_MARGIN;
        NativeTrainFixture.BuiltTrain built = chargedRoute(helper, 1, MaglevContent.REINFORCED_TRACK,
                MaglevContent.REINFORCED_MATERIAL, "speed-envelope-bullet", MaglevContent.BULLET_BOGEY,
                routeLength, 0, 0);
        observeScheduledRoute(helper, built, tier, targetSpeed, routeLength, false, false);
    }

    @GameTest(template = "create_maglev_tests:arena", batch = BATCH, timeoutTicks = 10000, attempts = 1)
    public void ordinaryTrackCapsBulletTrainAtConfiguredSpeed(GameTestHelper helper) {
        TrainTier tier = TrainTier.BULLET;
        double targetSpeed = MaglevConfig.bulletSpeedOnOrdinaryTrack();
        int distanceToCap = accelerationDistance(targetSpeed, tier.acceleration());
        int routeLength = distanceToCap * 2 + 2 * ROUTE_END_MARGIN;
        NativeTrainFixture.BuiltTrain built = chargedRoute(helper, 2, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "speed-envelope-bullet-ordinary", MaglevContent.BULLET_BOGEY,
                routeLength, 0, 0);
        observeScheduledRoute(helper, built, tier, targetSpeed, routeLength, false, false);
    }

    @GameTest(template = "create_maglev_tests:arena", batch = BATCH, timeoutTicks = 10000, attempts = 1)
    public void maglevTrainReachesRatedTopSpeedWhileLevitatingAndStopsAtDestination(GameTestHelper helper) {
        TrainTier tier = TrainTier.MAGLEV;
        double targetSpeed = tier.topSpeed();
        int distanceToTopSpeed = accelerationDistance(targetSpeed, tier.acceleration());
        int routeLength = distanceToTopSpeed * 2 + 2 * ROUTE_END_MARGIN;
        NativeTrainFixture.BuiltTrain built = chargedRoute(helper, 3, MaglevContent.MAGLEV_TRACK,
                MaglevContent.MAGLEV_MATERIAL, "speed-envelope-maglev", MaglevContent.MAGLEV_BOGEY,
                routeLength, 0, 0);
        observeScheduledRoute(helper, built, tier, targetSpeed, routeLength, true, false);
    }

    @GameTest(template = "create_maglev_tests:arena", batch = BATCH, timeoutTicks = 10000, attempts = 1)
    public void reinforcedBulletTrainHonorsRadiusBasedCurveLimitAtSpeed(GameTestHelper helper) {
        TrainTier tier = TrainTier.BULLET;
        double topSpeed = tier.topSpeed();
        double requestedCurveLimit = Math.max(MaglevConfig.minimumCurveSpeed(),
                Math.sqrt(MaglevConfig.curveLateralAcceleration() * CURVE_RADIUS));
        int approachLength = (int) Math.ceil(accelerationDistanceExact(topSpeed, tier.acceleration())
                + speedChangeDistance(topSpeed, requestedCurveLimit, MaglevConfig.emergencyBraking())
                + CURVE_APPROACH_MARGIN);
        int exitLength = (int) Math.ceil(speedChangeDistance(requestedCurveLimit, topSpeed, tier.acceleration())
                + accelerationDistanceExact(topSpeed, tier.acceleration()) + 2 * ROUTE_END_MARGIN);
        NativeTrainFixture.BuiltTrain built = chargedRoute(helper, 4, MaglevContent.REINFORCED_TRACK,
                MaglevContent.REINFORCED_MATERIAL, "speed-envelope-bullet-curve", MaglevContent.BULLET_BOGEY,
                approachLength, CURVE_RADIUS, exitLength);
        double routeLength = approachLength + built.turn.getLength() + exitLength;
        observeScheduledRoute(helper, built, tier, topSpeed, routeLength, false, true);
    }

    private static NativeTrainFixture.BuiltTrain chargedRoute(GameTestHelper helper, int region,
                                                               com.simibubi.create.content.trains.track.TrackBlock track,
                                                               TrackMaterial material, String stationPrefix,
                                                               Block bogey, int approachLength, int curveRadius,
                                                               int exitLength) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(12288 + region * REGION_SPACING, 64, 512));
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.buildRoute(level, origin, track, material,
                stationPrefix, bogey, approachLength, curveRadius, exitLength);
        if (built.substationPositions.isEmpty()) {
            throw new AssertionError("The native speed route must contain charged traction substations");
        }
        for (BlockPos pos : built.substationPositions) {
            long inserted = chargeThroughEnergyApi(level, pos, TEST_CHARGE);
            if (inserted != TEST_CHARGE) {
                throw new AssertionError("Route substation " + pos + " accepted " + inserted
                        + " energy instead of the required " + TEST_CHARGE);
            }
        }
        return built;
    }

    private static void observeScheduledRoute(GameTestHelper helper, NativeTrainFixture.BuiltTrain built,
                                              TrainTier tier, double targetSpeed, double routeLength,
                                              boolean requireLevitatedCruise, boolean observeCurve) {
        ServerLevel level = built.level;
        int timeoutTicks = computedTimeoutTicks(routeLength, targetSpeed, tier.acceleration());
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean sawPowered = new AtomicBoolean();
        AtomicBoolean sawLevitatedCruise = new AtomicBoolean();
        AtomicBoolean sawCurve = new AtomicBoolean();
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<UUID> carriageId = new AtomicReference<>();
        AtomicReference<Train> expectedTrain = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();
        AtomicInteger ticksAfterCurve = new AtomicInteger();
        AtomicReference<Double> maximumSpeed = new AtomicReference<>(0d);
        AtomicReference<Double> maximumSpeedBeforeCurve = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        AtomicReference<Double> maximumCurveSpeed = new AtomicReference<>(0d);
        AtomicReference<Double> maximumCurveSpeedExcess = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        AtomicReference<Double> minimumObservedCurveLimit = new AtomicReference<>(Double.POSITIVE_INFINITY);
        double curveRadius = observeCurve ? TrainTraction.curveRadius(built.turn) : 0;
        double expectedCurveLimit = observeCurve ? TrainTraction.curveSpeedLimit(built.turn) : 0;

        helper.onEachTick(() -> {
            if (!started.get() || completed.get()) return;
            guarded(helper, built, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), expectedTrain.get());
                TrainTraction traction = ((TractionState) train).maglev$traction();
                sawPowered.set(sawPowered.get() || traction.powered());
                UUID liveCarriageId = carriageId.get();
                if (liveCarriageId == null) {
                    if (carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                        throw new AssertionError("Native speed-route train did not register a live carriage after assembly");
                    }
                    liveCarriageId = captureRegisteredCarriageId(level, train);
                    if (liveCarriageId == null) return;
                    carriageId.set(liveCarriageId);
                }
                CarriageContraptionEntity entity = requireLiveCarriage(level, train, liveCarriageId);
                double speed = Math.abs(train.speed);
                maximumSpeed.set(Math.max(maximumSpeed.get(), speed));
                helper.assertTrue(speed <= targetSpeed + 0.001,
                        "Native " + tier + " train exceeded its configured speed envelope " + targetSpeed
                                + " (observed " + speed + ")");
                if (requireLevitatedCruise && speed > 0.05 && traction.lift() == 0.25f) {
                    sawLevitatedCruise.set(true);
                }

                if (observeCurve) {
                    Carriage carriage = entity.getCarriage();
                    TravellingPoint leading = carriage.getLeadingPoint();
                    TravellingPoint trailing = carriage.getTrailingPoint();
                    boolean leadingOnCurve = isTurn(leading);
                    boolean trailingOnCurve = isTurn(trailing);
                    boolean onCurve = leadingOnCurve || trailingOnCurve;
                    if (onCurve) {
                        sawCurve.set(true);
                        ticksAfterCurve.set(0);
                        double limit = Math.min(curveLimitAt(leading), curveLimitAt(trailing));
                        minimumObservedCurveLimit.set(Math.min(minimumObservedCurveLimit.get(), limit));
                        maximumCurveSpeed.set(Math.max(maximumCurveSpeed.get(), speed));
                        maximumCurveSpeedExcess.set(Math.max(maximumCurveSpeedExcess.get(), speed - limit));
                        helper.assertTrue(speed <= limit + 0.001,
                                "Bullet train exceeded radius-based curve limit " + limit + " on radius "
                                        + curveRadius + " (observed " + speed + ")");
                    } else if (sawCurve.get()) {
                        ticksAfterCurve.incrementAndGet();
                    } else {
                        maximumSpeedBeforeCurve.set(Math.max(maximumSpeedBeforeCurve.get(), speed));
                    }
                }

                if (train.getCurrentStation() == built.destinationStation.getStation()
                        && train.navigation.destination == null && speed < 0.001) {
                    completed.set(true);
                    helper.assertTrue(entity.isAlive() && level.getEntity(liveCarriageId) == entity,
                            "Destination arrival must be observed through the same live carriage UUID");
                    helper.assertTrue(sawPowered.get(), "Native traction must report powered during the scheduled run");
                    helper.assertTrue(maximumSpeed.get() >= 0.99 * targetSpeed,
                            "Train must reach at least 99% of its rated/configured speed " + targetSpeed
                                    + " (observed maximum " + maximumSpeed.get() + ")");
                    helper.assertTrue(maximumSpeed.get() <= targetSpeed + 0.001,
                            "Observed native speed must never exceed the configured ceiling " + targetSpeed);
                    if (requireLevitatedCruise) {
                        helper.assertTrue(sawLevitatedCruise.get(),
                                "Maglev traction must reach exact 0.25-block lift while cruising");
                    }
                    if (observeCurve) {
                        helper.assertTrue(Math.abs(curveRadius - CURVE_RADIUS) <= 1.0,
                                "Create's native Bezier curve radius must be 64 blocks; observed " + curveRadius);
                        helper.assertTrue(expectedCurveLimit > MaglevConfig.minimumCurveSpeed() * 1.5,
                                "Radius-based curve limit " + expectedCurveLimit + " must clear the flat minimum "
                                        + MaglevConfig.minimumCurveSpeed() + " by a clear margin (radius " + curveRadius + ")");
                        helper.assertTrue(sawCurve.get() && ticksAfterCurve.get() > 0,
                                "The live carriage must traverse and clear the native curve before destination stop");
                        helper.assertTrue(maximumSpeedBeforeCurve.get() > expectedCurveLimit + 0.001,
                                "Train must exceed the curve limit before the curve to prove anticipatory braking was exercised"
                                        + " (radius " + curveRadius + ", limit " + expectedCurveLimit + ", before "
                                        + maximumSpeedBeforeCurve.get() + ")");
                        helper.assertTrue(maximumCurveSpeed.get() >= 0.95 * expectedCurveLimit,
                                "Speed on the radius-" + curveRadius + " curve must approach its native limit "
                                        + expectedCurveLimit + " (observed " + maximumCurveSpeed.get() + ")");
                        helper.assertTrue(maximumCurveSpeedExcess.get() <= 0.001,
                                "Train speed must remain within the radius-based limit on every curve observation"
                                        + " (radius " + curveRadius + ", worst excess "
                                        + maximumCurveSpeedExcess.get() + ")");
                        helper.assertTrue(Math.abs(minimumObservedCurveLimit.get() - expectedCurveLimit) <= 0.001,
                                "Observed turn edges must use the curve limit derived from radius " + curveRadius);
                    }
                    built.releaseForcedChunks();
                    helper.succeed();
                }
            });
        });

        helper.runAtTickTime(ASSEMBLY_TICK, () -> guarded(helper, built, completed, () -> {
            if (observeCurve) {
                helper.assertTrue(built.turn != null, "The curve route must contain its native Bezier turn");
            }
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            expectedTrain.set(assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
            started.set(true);
        }));
        helper.runAtTickTime(timeoutTicks, () -> {
            if (completed.get()) return;
            completed.set(true);
            built.releaseForcedChunks();
            helper.fail("Native " + tier + " speed-envelope route did not stop at its destination within "
                    + timeoutTicks + " ticks (route length " + routeLength + ", target " + targetSpeed + ")");
        });
    }

    private static int accelerationDistance(double speed, double acceleration) {
        return (int) Math.ceil(accelerationDistanceExact(speed, acceleration));
    }

    private static double accelerationDistanceExact(double speed, double acceleration) {
        return speed * speed / (2 * acceleration);
    }

    private static double speedChangeDistance(double fromSpeed, double toSpeed, double acceleration) {
        return Math.abs(fromSpeed * fromSpeed - toSpeed * toSpeed) / (2 * acceleration);
    }

    private static int computedTimeoutTicks(double routeLength, double targetSpeed, double acceleration) {
        double conservativeTravelTicks = routeLength / Math.max(0.05, targetSpeed * 0.5);
        double accelerationTicks = targetSpeed / acceleration;
        return ASSEMBLY_TICK + (int) Math.ceil(4 * (conservativeTravelTicks + 2 * accelerationTicks + 200));
    }

    private static Train requireRegisteredTrain(UUID id, Train expected) {
        if (id == null) throw new AssertionError("Native route train UUID is not available");
        Train registered = Create.RAILWAYS.trains.get(id);
        if (registered == null || !id.equals(registered.id) || expected != null && registered != expected) {
            throw new AssertionError("Native route train UUID is absent from Create's live railway registry");
        }
        return registered;
    }

    private static UUID captureRegisteredCarriageId(ServerLevel level, Train train) {
        if (train.carriages.isEmpty()) throw new AssertionError("Registered native route train has no carriage");
        CarriageContraptionEntity entity = train.carriages.get(0).anyAvailableEntity();
        if (entity == null) return null;
        UUID id = entity.getUUID();
        if (level.getEntity(id) != entity) {
            throw new AssertionError("Create carriage reference is absent from the server UUID registry");
        }
        requireLiveCarriage(level, train, id);
        return id;
    }

    private static CarriageContraptionEntity requireLiveCarriage(ServerLevel level, Train train, UUID id) {
        Entity registered = id == null ? null : level.getEntity(id);
        if (!(registered instanceof CarriageContraptionEntity carriage)
                || !carriage.isAlive()
                || !id.equals(carriage.getUUID())
                || !train.id.equals(carriage.trainId)
                || carriage.getCarriage() == null
                || carriage.getCarriage().train != train) {
            throw new AssertionError("Native route carriage is not live and linked to its registered train by UUID");
        }
        return carriage;
    }

    private static boolean isTurn(TravellingPoint point) {
        return point.edge != null && point.edge.isTurn();
    }

    private static double curveLimitAt(TravellingPoint point) {
        return isTurn(point) ? TrainTraction.curveSpeedLimit(point.edge.getTurn()) : Double.POSITIVE_INFINITY;
    }

    private static void guarded(GameTestHelper helper, NativeTrainFixture.BuiltTrain built,
                                AtomicBoolean completed, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            completed.set(true);
            built.releaseForcedChunks();
            helper.fail(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static long chargeThroughEnergyApi(ServerLevel level, BlockPos pos, long amount) {
        EnergyStorage storage = EnergyStorage.findForBlock(level, pos, Direction.UP);
        if (storage == null) throw new AssertionError("No public ESL energy storage at route substation " + pos);
        try (Transaction transaction = TransactionStack.get().openOuter()) {
            long inserted = storage.insert(amount, transaction);
            transaction.commit();
            return inserted;
        }
    }
}
