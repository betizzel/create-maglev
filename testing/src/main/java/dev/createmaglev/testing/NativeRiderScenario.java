package dev.createmaglev.testing;

import com.google.gson.JsonObject;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TrainTraction;
import dev.createmaglev.train.TractionState;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Records a seated player passenger through native maglev lift and power-loss landing. */
public final class NativeRiderScenario {
    private static final int SETTLE_TICKS = 10;
    private static final int POWER_CUT_TICK = 60;
    private static final int DEADLINE_TICK = 120;
    private static final int REQUIRED_STABLE_LANDING_TICKS = 10;
    private static final float FULL_LIFT = 0.25f;
    private static final double RIDER_HEIGHT_TOLERANCE = 0.10;
    private static final double STABLE_HEIGHT_TOLERANCE = 0.10;
    private static final double RIDER_HORIZONTAL_TOLERANCE = 0.75;
    private static final double STABLE_HORIZONTAL_TOLERANCE = 0.50;

    private final ServerLevel level;
    private final NativeTrainFixture.BuiltTrain built;
    private final NativeNewAgePowerFixture supply;
    private final ServerPlayer player;
    private final UUID playerId;

    private UUID carriageId;
    private UUID trainId;
    private String stage = "armed";
    private String failureStage = "";
    private String failureReason = "";
    private int elapsedTicks;
    private int controlTicks;
    private int brakingSamples;
    private int stableLandingTicks;

    private boolean started;
    private boolean breakerBlocked = true;
    private boolean wireIsolatedAtBegin;
    private boolean generatorStoppedAtBegin;
    private boolean playerResolved;
    private boolean carriageResolved;
    private boolean controlOwnerMatches;
    private boolean seatMappingMatches;
    private boolean sawPoweredRise;
    private boolean sawControlledMovement;
    private boolean breakerCut;
    private boolean sawUnpoweredAfterCut;
    private boolean sawBraking;
    private boolean sawStopped;
    private boolean sawLanding;
    private boolean cycleComplete;

    private long energyAtBegin = -1;
    private long energyAtPowerCut = -1;
    private long energyAtEnd = -1;
    private double speedAtPowerCut = -1;
    private double previousPostCutSpeed = -1;
    private double maximumLift;
    private double maximumEntityLift;
    private double maximumPhysicalLift;
    private double finalLift = -1;
    private double latestEntityLift = -1;
    private double latestEntityLiftError = -1;
    private double entityLiftAtPowerCut = -1;
    private double finalPhysicalLift = -1;
    private double baselineRelativeFeetHeight = -1;
    private double maximumRelativeFeetHeight = -1;
    private double minimumRelativeFeetHeight = -1;
    private double currentRelativeFeetHeight = -1;
    private double maximumRelativeHeightError;
    private double latestRelativeHeightError;
    private double latestHorizontalDrift;
    private double latestHorizontalTolerance = RIDER_HORIZONTAL_TOLERANCE;
    private double latestVerticalTolerance = RIDER_HEIGHT_TOLERANCE;
    private double latestVerticalFollowError;
    private double latestPhysicalAlignmentError;
    private double maximumBodyDisplacement;
    private double maximumPlayerDisplacement;
    private double latestBodyDisplacement;
    private double latestPlayerDisplacement;
    private double latestMovementMismatch;
    private double liftAtPowerCut = -1;
    private double physicalLiftAtPowerCut = -1;
    private Vec3 baselineBodyPosition;
    private Vec3 baselinePlayerPosition;
    private Vec3 baselineRelativeTangent;
    private Vec3 bodyPositionAtPowerCut;
    private Vec3 playerPositionAtPowerCut;
    private Vec3 finalBodyPosition;
    private Vec3 finalPlayerPosition;
    private Vec3 currentBodyPosition;
    private Vec3 previousSampleBodyPosition;
    private Vec3 currentPlayerPosition;

    public NativeRiderScenario(ServerLevel level, NativeTrainFixture.BuiltTrain built,
                               NativeNewAgePowerFixture supply, ServerPlayer player) {
        this.level = Objects.requireNonNull(level, "level");
        this.built = Objects.requireNonNull(built, "built");
        this.supply = Objects.requireNonNull(supply, "supply");
        this.player = Objects.requireNonNull(player, "player");
        this.playerId = player.getUUID();
        if (built.level != level || supply.level() != level) {
            throw new IllegalArgumentException("The train and NewAge supply must belong to the scenario's ServerLevel");
        }
        setBreakerBlocked(true);
    }

    public void begin(MinecraftServer server) {
        if (started || !"armed".equals(stage)) {
            recordFailure("begin was called more than once or after the scenario had already failed");
            abort();
            return;
        }
        started = true;
        stage = "settling_on_stationary_carriage";
        try {
            requireServer(server);
            if (!breakerBlocked || !level.getBlockState(supply.substationPos().below()).is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalStateException("The real substation breaker was not armed with REDSTONE_BLOCK");
            }
            if (built.train == null || built.train.carriages == null || built.train.carriages.isEmpty()) {
                throw new IllegalStateException("The native train was not assembled before rider observation began");
            }
            trainId = built.train.id;
            CarriageContraptionEntity body = built.liveCarriage();
            if (body == null) throw new IllegalStateException("The assembled train has no live carriage entity");
            carriageId = body.getUUID();
            if (level.getEntity(carriageId) != body) {
                throw new IllegalStateException("The carriage UUID does not resolve to the live world entity");
            }
            energyAtBegin = supply.substation().energyStored();
            wireIsolatedAtBegin = !supply.hasLiveCopperWire();
            generatorStoppedAtBegin = supply.motor().generatedSpeed.value == 0;
            if (energyAtBegin <= 0) {
                throw new IllegalStateException("The isolated native substation has no genuinely generated charge at begin");
            }
            if (!wireIsolatedAtBegin || !generatorStoppedAtBegin) {
                throw new IllegalStateException("The charged NewAge source was not isolated before the rider run");
            }
            boardPlayer(body);
            verifyActors(server, body);
        } catch (RuntimeException error) {
            recordFailure(error.getClass().getSimpleName() + ": " + error.getMessage());
            abort();
        }
    }

    public void tick(MinecraftServer server) {
        if (!started) {
            if (!"failed".equals(stage)) {
                recordFailure("tick was called before begin");
                abort();
            }
            return;
        }
        if ("failed".equals(stage)) return;
        elapsedTicks++;
        try {
            requireServer(server);
            CarriageContraptionEntity body = resolveCarriage();
            verifyActors(server, body);
            sample(body);

            if ("settling_on_stationary_carriage".equals(stage)) {
                if (elapsedTicks >= SETTLE_TICKS) {
                    captureBaseline(body);
                    setBreakerBlocked(false);
                    stage = "powered_rise_and_movement";
                }
            } else if ("powered_rise_and_movement".equals(stage)) {
                observePoweredRun(body);
                if (elapsedTicks >= POWER_CUT_TICK) cutPower(body);
            } else if ("power_cut_braking_landing".equals(stage)) {
                observePowerCut(body);
            } else if ("complete".equals(stage)) {
                // Keep checking actual UUID resolution, ownership, and rider retention through capture end.
            }

            if (elapsedTicks >= DEADLINE_TICK && !cycleComplete && !"failed".equals(stage)) {
                recordFailure("the complete lift, powered movement, braking, landing, and rider-retention cycle exceeded "
                        + DEADLINE_TICK + " observation ticks");
                abort();
            }
        } catch (RuntimeException error) {
            recordFailure(error.getClass().getSimpleName() + ": " + error.getMessage());
            abort();
        }
    }

    public JsonObject evidence() {
        JsonObject result = new JsonObject();
        result.addProperty("stage", stage);
        result.addProperty("failureStage", failureStage);
        result.addProperty("failureReason", failureReason);
        result.addProperty("elapsedTicks", elapsedTicks);
        result.addProperty("cycleComplete", cycleComplete);
        result.addProperty("breakerBlocked", breakerBlocked);
        result.addProperty("breakerCutAtTick", breakerCut ? POWER_CUT_TICK : -1);
        result.addProperty("wireIsolatedAtBegin", wireIsolatedAtBegin);
        result.addProperty("generatorStoppedAtBegin", generatorStoppedAtBegin);
        result.addProperty("energyAtBegin", energyAtBegin);
        result.addProperty("energyAtPowerCut", energyAtPowerCut);
        result.addProperty("energyAtEnd", energyAtEnd);
        result.addProperty("energyConsumed", energyAtBegin >= 0 && energyAtEnd >= 0 ? energyAtBegin - energyAtEnd : -1);
        result.addProperty("retainedEnergyAtEnd", energyAtEnd);
        result.addProperty("liftAtPowerCut", liftAtPowerCut);
        result.addProperty("physicalLiftAtPowerCut", physicalLiftAtPowerCut);
        result.addProperty("playerUUID", playerId.toString());
        result.addProperty("carriageUUID", carriageId == null ? "" : carriageId.toString());
        result.addProperty("trainUUID", trainId == null ? "" : trainId.toString());
        result.addProperty("playerResolvedByUUID", playerResolved);
        result.addProperty("playerAlive", player.isAlive());
        result.addProperty("carriageResolvedByUUID", carriageResolved);
        result.addProperty("carriageAlive", currentCarriageAlive());
        result.addProperty("controlOwnerMatches", controlOwnerMatches);
        result.addProperty("controlOwnedTicks", controlTicks);
        result.addProperty("seatMappingMatches", seatMappingMatches);
        result.addProperty("playerIsPassenger", player.isPassenger());
        result.addProperty("playerVehicleUUID", player.getVehicle() == null ? "" : player.getVehicle().getUUID().toString());
        result.addProperty("seatIndex", currentSeatIndex());
        result.addProperty("seatPosition", NativeTrainFixture.RIDER_SEAT_OFFSET.toString());
        result.addProperty("poweredRiseObserved", sawPoweredRise);
        result.addProperty("controlledMovementObserved", sawControlledMovement);
        result.addProperty("unpoweredAfterCutObserved", sawUnpoweredAfterCut);
        result.addProperty("brakingObserved", sawBraking);
        result.addProperty("brakingSamples", brakingSamples);
        result.addProperty("stoppedAfterCut", sawStopped);
        result.addProperty("landingObserved", sawLanding);
        result.addProperty("stableLandingTicks", stableLandingTicks);
        result.addProperty("maximumLift", maximumLift);
        result.addProperty("maximumEntityLift", maximumEntityLift);
        result.addProperty("maximumPhysicalLift", maximumPhysicalLift);
        result.addProperty("finalLift", finalLift);
        result.addProperty("finalEntityLift", latestEntityLift);
        result.addProperty("entityLiftAtPowerCut", entityLiftAtPowerCut);
        result.addProperty("entityLiftError", latestEntityLiftError);
        result.addProperty("finalPhysicalLift", finalPhysicalLift);
        result.addProperty("baselineRelativeFeetHeight", baselineRelativeFeetHeight);
        result.addProperty("maximumRelativeFeetHeight", maximumRelativeFeetHeight);
        result.addProperty("minimumRelativeFeetHeight", minimumRelativeFeetHeight);
        result.addProperty("finalRelativeFeetHeight", currentRelativeFeetHeight);
        result.addProperty("maximumRelativeHeightError", maximumRelativeHeightError);
        result.addProperty("latestRelativeHeightError", latestRelativeHeightError);
        result.addProperty("latestHorizontalRiderDrift", latestHorizontalDrift);
        result.addProperty("latestHorizontalTolerance", latestHorizontalTolerance);
        result.addProperty("latestVerticalFollowError", latestVerticalFollowError);
        result.addProperty("latestVerticalTolerance", latestVerticalTolerance);
        result.addProperty("latestBodyAlignmentError", latestPhysicalAlignmentError);
        result.addProperty("maximumBodyDisplacement", maximumBodyDisplacement);
        result.addProperty("maximumPlayerDisplacement", maximumPlayerDisplacement);
        result.addProperty("finalBodyDisplacement", latestBodyDisplacement);
        result.addProperty("finalPlayerDisplacement", latestPlayerDisplacement);
        result.addProperty("latestMovementMismatch", latestMovementMismatch);
        result.addProperty("speedAtPowerCut", speedAtPowerCut);
        result.addProperty("finalTrainSpeed", built.train == null ? -1 : Math.abs(built.train.speed));
        addVector(result, "baselineBodyPosition", baselineBodyPosition);
        addVector(result, "baselinePlayerPosition", baselinePlayerPosition);
        addVector(result, "bodyPositionAtPowerCut", bodyPositionAtPowerCut);
        addVector(result, "playerPositionAtPowerCut", playerPositionAtPowerCut);
        if (baselineBodyPosition != null && bodyPositionAtPowerCut != null) {
            addVector(result, "bodyDisplacementAtPowerCut", bodyPositionAtPowerCut.subtract(baselineBodyPosition));
        }
        if (baselinePlayerPosition != null && playerPositionAtPowerCut != null) {
            addVector(result, "playerDisplacementAtPowerCut", playerPositionAtPowerCut.subtract(baselinePlayerPosition));
        }
        addVector(result, "finalBodyPosition", finalBodyPosition);
        addVector(result, "finalPlayerPosition", finalPlayerPosition);
        if (baselineBodyPosition != null && currentBodyPosition != null) {
            addVector(result, "bodyDisplacementVector", currentBodyPosition.subtract(baselineBodyPosition));
        }
        if (baselinePlayerPosition != null && currentPlayerPosition != null) {
            addVector(result, "playerDisplacementVector", currentPlayerPosition.subtract(baselinePlayerPosition));
        }
        return result;
    }

    public boolean passed() {
        return started && cycleComplete && "complete".equals(stage) && failureReason.isEmpty()
                && sawPoweredRise && sawControlledMovement && breakerCut && sawUnpoweredAfterCut
                && sawBraking && brakingSamples >= 2 && sawStopped && sawLanding
                && stableLandingTicks >= REQUIRED_STABLE_LANDING_TICKS
                && energyAtBegin > 0 && energyAtEnd > 0 && energyAtEnd < energyAtBegin
                && wireIsolatedAtBegin && generatorStoppedAtBegin && breakerBlocked
                && finalLift <= 0.001 && latestEntityLift <= 0.005
                && latestEntityLiftError <= 0.005 && Math.abs(finalPhysicalLift) <= 0.03
                && latestRelativeHeightError <= STABLE_HEIGHT_TOLERANCE
                && latestVerticalFollowError <= STABLE_HEIGHT_TOLERANCE
                && latestHorizontalDrift <= STABLE_HORIZONTAL_TOLERANCE
                && level.getEntity(playerId) == player && player.isAlive() && player.serverLevel() == level
                && serverPlayerResolved() && currentCarriageAlive()
                && controlOwnerMatches && currentControlOwnerMatches()
                && seatMappingMatches && seatMappingMatchesCurrent()
                && player.isPassenger() && player.getVehicle() == level.getEntity(carriageId)
                && Math.abs(built.train.speed) <= 0.001;
    }

    private void captureBaseline(CarriageContraptionEntity body) {
        TrainTraction traction = ((TractionState) built.train).maglev$traction();
        if (!traction.authoritative()) {
            throw new IllegalStateException("Maglev traction had not reached an authoritative native tick during rider settling");
        }
        if (traction.powered() || traction.lift() > 0.001f || latestEntityLift > 0.001
                || Math.abs(latestPhysicalLift()) > 0.03) {
            throw new IllegalStateException("The redstone-blocked carriage was already powered or physically lifted at baseline");
        }
        baselineRelativeFeetHeight = currentRelativeFeetHeight;
        if (baselineRelativeFeetHeight < 0.25 || baselineRelativeFeetHeight > 4.0) {
            throw new IllegalStateException("The naturally settled player's feet were not on the carriage body: relative height="
                    + baselineRelativeFeetHeight);
        }
        baselineBodyPosition = body.position();
        baselinePlayerPosition = player.position();
        baselineRelativeTangent = tangent(player.position().subtract(body.position()), latestNormal());
        finalBodyPosition = baselineBodyPosition;
        finalPlayerPosition = baselinePlayerPosition;
        finalLift = traction.lift();
        finalPhysicalLift = latestPhysicalLift();
    }

    private void observePoweredRun(CarriageContraptionEntity body) {
        TrainTraction traction = ((TractionState) built.train).maglev$traction();
        if (traction.authoritative() && traction.powered()
                && traction.lift() >= FULL_LIFT - 0.005
                && latestEntityLift >= FULL_LIFT - 0.005
                && latestEntityLiftError <= 0.005
                && latestPhysicalLift() >= FULL_LIFT - 0.04
                && latestPhysicalAlignmentError <= 0.001) {
            sawPoweredRise = true;
        }
        if (baselineBodyPosition != null && Math.abs(built.train.speed) > 0.05
                && latestBodyDisplacement >= 1.0 && latestPlayerDisplacement >= 0.75
                && latestMovementMismatch <= latestHorizontalTolerance) {
            sawControlledMovement = true;
        }
        if (sawPoweredRise && latestVerticalFollowError > latestVerticalTolerance) {
            throw new IllegalStateException("Player feet did not rise with the powered carriage; vertical follow error="
                    + latestVerticalFollowError + " (one-tick tolerance=" + latestVerticalTolerance + ")");
        }
    }

    private void cutPower(CarriageContraptionEntity body) {
        if (!sawPoweredRise) {
            recordFailure("the real powered lift and carriage-body alignment were not observed before the tick-60 power cut");
        }
        if (!sawControlledMovement) {
            recordFailure("native W-controlled body and rider displacement were not both observed before the tick-60 power cut");
        }
        if (Math.abs(built.train.speed) <= 0.05) {
            recordFailure("the train was no longer moving when the power cut was scheduled while W was held");
        }
        energyAtPowerCut = supply.substation().energyStored();
        if (energyAtPowerCut <= 0) recordFailure("the genuine substation charge was exhausted before the power cut");
        speedAtPowerCut = Math.abs(built.train.speed);
        previousPostCutSpeed = speedAtPowerCut;
        liftAtPowerCut = finalLift;
        entityLiftAtPowerCut = latestEntityLift;
        physicalLiftAtPowerCut = finalPhysicalLift;
        bodyPositionAtPowerCut = body.position();
        playerPositionAtPowerCut = player.position();
        setBreakerBlocked(true);
        breakerCut = true;
        stage = "power_cut_braking_landing";
    }

    private void observePowerCut(CarriageContraptionEntity body) {
        TrainTraction traction = ((TractionState) built.train).maglev$traction();
        double speed = Math.abs(built.train.speed);
        if (!traction.powered()) sawUnpoweredAfterCut = true;
        if (sawUnpoweredAfterCut && speed < previousPostCutSpeed - 0.0005) {
            brakingSamples++;
            sawBraking = true;
        }
        previousPostCutSpeed = speed;
        if (sawUnpoweredAfterCut && speed <= 0.001) sawStopped = true;

        sawLanding = sawUnpoweredAfterCut && sawPoweredRise
                && traction.lift() <= 0.001f && Math.abs(latestPhysicalLift()) <= 0.03;
        if (sawLanding && sawStopped
                && latestRelativeHeightError <= STABLE_HEIGHT_TOLERANCE
                && latestHorizontalDrift <= STABLE_HORIZONTAL_TOLERANCE
                && latestVerticalFollowError <= STABLE_HEIGHT_TOLERANCE) {
            stableLandingTicks++;
        } else {
            stableLandingTicks = 0;
        }

        if (stableLandingTicks >= REQUIRED_STABLE_LANDING_TICKS) {
            energyAtEnd = supply.substation().energyStored();
            finalBodyPosition = body.position();
            finalPlayerPosition = player.position();
            if (failureReason.isEmpty() && sawControlledMovement && sawBraking && brakingSamples >= 2
                    && energyAtBegin > energyAtEnd && energyAtEnd > 0) {
                cycleComplete = true;
                stage = "complete";
            } else if (failureReason.isEmpty()) {
                recordFailure("landing settled, but native movement, braking, or retained-charge evidence was incomplete");
            }
        }
    }

    private void sample(CarriageContraptionEntity body) {
        TrainTraction traction = ((TractionState) built.train).maglev$traction();
        Carriage carriage = body.getCarriage();
        if (carriage == null || built.train.carriages.isEmpty() || carriage != built.train.carriages.get(0)) {
            throw new IllegalStateException("The UUID-resolved carriage is not bound to this train's live carriage data");
        }
        Carriage.DimensionalCarriageEntity dimensional = carriage.getDimensionalIfPresent(level.dimension());
        if (dimensional == null || dimensional.positionAnchor == null) {
            throw new IllegalStateException("The live carriage has no native track position anchor");
        }
        var point = carriage.getLeadingPoint();
        if (point.edge == null || point.edge.getLength() == 0) {
            throw new IllegalStateException("The live carriage has no leading native track edge");
        }
        Vec3 rawNormal = point.edge.getNormal(carriage.train.graph, point.position / point.edge.getLength());
        if (rawNormal.lengthSqr() < 0.5) throw new IllegalStateException("The native track normal is degenerate");
        Vec3 normal = rawNormal.normalize();
        Vec3 bodyOffset = body.position().subtract(dimensional.positionAnchor);
        double physicalLift = bodyOffset.dot(normal);
        Vec3 expectedOffset = rawNormal.scale(traction.lift());
        if (!(body instanceof LevitationData levitation)) {
            throw new IllegalStateException("The live carriage does not expose Create Maglev's synchronized lift state");
        }
        latestEntityLift = levitation.maglev$lift();
        latestEntityLiftError = Math.abs(latestEntityLift - traction.lift());

        Vec3 priorSampleBodyPosition = previousSampleBodyPosition;
        currentBodyPosition = body.position();
        currentPlayerPosition = player.position();
        finalBodyPosition = currentBodyPosition;
        finalPlayerPosition = currentPlayerPosition;
        previousSampleBodyPosition = currentBodyPosition;
        Vec3 bodyStep = priorSampleBodyPosition == null
                ? Vec3.ZERO : currentBodyPosition.subtract(priorSampleBodyPosition);
        latestHorizontalTolerance = RIDER_HORIZONTAL_TOLERANCE + tangent(bodyStep, normal).length();
        latestVerticalTolerance = RIDER_HEIGHT_TOLERANCE + Math.abs(bodyStep.dot(normal));
        currentRelativeFeetHeight = currentPlayerPosition.subtract(currentBodyPosition).dot(normal);
        if (maximumRelativeFeetHeight < 0) maximumRelativeFeetHeight = currentRelativeFeetHeight;
        else maximumRelativeFeetHeight = Math.max(maximumRelativeFeetHeight, currentRelativeFeetHeight);
        if (minimumRelativeFeetHeight < 0) minimumRelativeFeetHeight = currentRelativeFeetHeight;
        else minimumRelativeFeetHeight = Math.min(minimumRelativeFeetHeight, currentRelativeFeetHeight);
        maximumLift = Math.max(maximumLift, traction.lift());
        maximumEntityLift = Math.max(maximumEntityLift, latestEntityLift);
        maximumPhysicalLift = Math.max(maximumPhysicalLift, physicalLift);
        finalLift = traction.lift();
        finalPhysicalLift = physicalLift;
        latestPhysicalAlignmentError = bodyOffset.distanceTo(expectedOffset);

        if (baselineBodyPosition != null) {
            Vec3 bodyDelta = currentBodyPosition.subtract(baselineBodyPosition);
            Vec3 playerDelta = currentPlayerPosition.subtract(baselinePlayerPosition);
            latestBodyDisplacement = tangent(bodyDelta, normal).length();
            latestPlayerDisplacement = tangent(playerDelta, normal).length();
            latestMovementMismatch = tangent(playerDelta.subtract(bodyDelta), normal).length();
            maximumBodyDisplacement = Math.max(maximumBodyDisplacement, latestBodyDisplacement);
            maximumPlayerDisplacement = Math.max(maximumPlayerDisplacement, latestPlayerDisplacement);
            Vec3 currentTangent = tangent(currentPlayerPosition.subtract(currentBodyPosition), normal);
            latestHorizontalDrift = currentTangent.subtract(baselineRelativeTangent).length();
            latestRelativeHeightError = Math.abs(currentRelativeFeetHeight - baselineRelativeFeetHeight);
            maximumRelativeHeightError = Math.max(maximumRelativeHeightError, latestRelativeHeightError);
            latestVerticalFollowError = Math.abs(playerDelta.dot(normal) - bodyDelta.dot(normal));
            if (latestRelativeHeightError > latestVerticalTolerance) {
                throw new IllegalStateException("Rider feet separated vertically from the carriage by "
                        + latestRelativeHeightError + " blocks (one-tick tolerance " + latestVerticalTolerance + ")");
            }
            if (latestHorizontalDrift > latestHorizontalTolerance) {
                throw new IllegalStateException("Rider drifted off the same carriage position by "
                        + latestHorizontalDrift + " blocks (one-tick tolerance " + latestHorizontalTolerance + ")");
            }
            if (latestMovementMismatch > latestHorizontalTolerance) {
                throw new IllegalStateException("Player and native carriage world displacement diverged by "
                        + latestMovementMismatch + " blocks (one-tick tolerance " + latestHorizontalTolerance + ")");
            }
        }
        energyAtEnd = supply.substation().energyStored();
    }

    private void verifyActors(MinecraftServer server, CarriageContraptionEntity body) {
        Entity resolvedPlayer = level.getEntity(playerId);
        playerResolved = resolvedPlayer == player && player.isAlive() && player.serverLevel() == level
                && server.getPlayerList().getPlayer(playerId) == player;
        if (!playerResolved) throw new IllegalStateException("The real ServerPlayer no longer resolves alive by its UUID");

        carriageResolved = level.getEntity(carriageId) == body && body.isAlive();
        if (!carriageResolved) throw new IllegalStateException("The live carriage no longer resolves alive by its UUID");
        controlOwnerMatches = body.getControllingPlayer().filter(playerId::equals).isPresent();
        if (!controlOwnerMatches) throw new IllegalStateException("The native carriage no longer recognizes this rider as its controlling player");
        seatMappingMatches = seatMappingMatches(body);
        if (!seatMappingMatches) throw new IllegalStateException("The live ServerPlayer is no longer seated in the expected native carriage seat");
        controlTicks++;
    }

    private void boardPlayer(CarriageContraptionEntity body) {
        if (!body.handlePlayerInteraction(player, NativeTrainFixture.RIDER_SEAT_OFFSET,
                Direction.UP, InteractionHand.MAIN_HAND)) {
            throw new IllegalStateException("Create's native carriage seat interaction refused the player");
        }
        if (!seatMappingMatches(body)) {
            throw new IllegalStateException("Create did not bind the player's UUID to the expected carriage seat");
        }
    }

    private boolean seatMappingMatches(CarriageContraptionEntity body) {
        if (!player.isPassenger() || player.getVehicle() != body) return false;
        var contraption = body.getContraption();
        int seatIndex = contraption.getSeats().indexOf(NativeTrainFixture.RIDER_SEAT_OFFSET);
        Integer mappedSeat = contraption.getSeatMapping().get(playerId);
        return seatIndex >= 0 && mappedSeat != null && mappedSeat == seatIndex
                && NativeTrainFixture.RIDER_SEAT_OFFSET.equals(contraption.getSeatOf(playerId));
    }

    private int currentSeatIndex() {
        if (carriageId == null) return -1;
        Entity entity = level.getEntity(carriageId);
        if (!(entity instanceof CarriageContraptionEntity body) || body.getContraption() == null) return -1;
        return body.getContraption().getSeatMapping().getOrDefault(playerId, -1);
    }
    private boolean serverPlayerResolved() {
        MinecraftServer server = level.getServer();
        return server != null && level.getEntity(playerId) == player && player.isAlive()
                && player.serverLevel() == level && server.getPlayerList().getPlayer(playerId) == player;
    }

    private boolean currentControlOwnerMatches() {
        Entity entity = carriageId == null ? null : level.getEntity(carriageId);
        return entity instanceof CarriageContraptionEntity body
                && body.getControllingPlayer().filter(playerId::equals).isPresent();
    }

    private boolean seatMappingMatchesCurrent() {
        Entity entity = carriageId == null ? null : level.getEntity(carriageId);
        return entity instanceof CarriageContraptionEntity body && seatMappingMatches(body);
    }

    private CarriageContraptionEntity resolveCarriage() {
        Entity resolved = level.getEntity(carriageId);
        if (!(resolved instanceof CarriageContraptionEntity body)) {
            throw new IllegalStateException("Carriage UUID " + carriageId + " did not resolve to a live carriage entity");
        }
        return body;
    }

    private void requireServer(MinecraftServer server) {
        if (server == null || level.getServer() != server || !server.isSameThread()) {
            throw new IllegalStateException("Rider scenario must tick on its owning server thread");
        }
    }

    private void setBreakerBlocked(boolean blocked) {
        BlockPos breakerPos = supply.substationPos().below();
        Block desired = blocked ? Blocks.REDSTONE_BLOCK : Blocks.STONE;
        level.setBlock(breakerPos, desired.defaultBlockState(), 3);
        if (!level.getBlockState(breakerPos).is(desired)) {
            throw new IllegalStateException("Could not set the real substation breaker at " + breakerPos + " to " + desired);
        }
        breakerBlocked = blocked;
    }

    private void abort() {
        try {
            setBreakerBlocked(true);
        } catch (RuntimeException breakerError) {
            if (failureReason.isEmpty()) recordFailure("could not isolate the substation after scenario failure: " + breakerError.getMessage());
        }
        stage = "failed";
        cycleComplete = false;
    }

    private void recordFailure(String reason) {
        if (failureReason.isEmpty()) {
            failureStage = stage;
            failureReason = reason;
        }
    }

    private Vec3 latestNormal() {
        Entity resolved = carriageId == null ? null : level.getEntity(carriageId);
        if (!(resolved instanceof CarriageContraptionEntity body) || body.getCarriage() == null) {
            return new Vec3(0, 1, 0);
        }
        var point = body.getCarriage().getLeadingPoint();
        if (point.edge == null || point.edge.getLength() == 0) return new Vec3(0, 1, 0);
        return point.edge.getNormal(body.getCarriage().train.graph, point.position / point.edge.getLength()).normalize();
    }

    private double latestPhysicalLift() {
        return finalPhysicalLift < 0 ? 0 : finalPhysicalLift;
    }

    private boolean currentCarriageAlive() {
        if (carriageId == null) return false;
        Entity entity = level.getEntity(carriageId);
        return entity instanceof CarriageContraptionEntity && entity.isAlive();
    }

    private static Vec3 tangent(Vec3 vector, Vec3 unitNormal) {
        return vector.subtract(unitNormal.scale(vector.dot(unitNormal)));
    }

    private static void addVector(JsonObject target, String key, Vec3 vector) {
        if (vector == null) return;
        JsonObject result = new JsonObject();
        result.addProperty("x", vector.x);
        result.addProperty("y", vector.y);
        result.addProperty("z", vector.z);
        target.add(key, result);
    }
}
