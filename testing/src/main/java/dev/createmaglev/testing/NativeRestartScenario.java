package dev.createmaglev.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.content.MaglevBogeyStyles;
import dev.createmaglev.power.TractionPower;
import dev.createmaglev.power.TractionSubstationBlock;
import dev.createmaglev.power.TractionSubstationBlockEntity;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TractionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dedicated-server disk restart coverage for a real scheduled maglev and NewAge power source. */
public final class NativeRestartScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeRestartScenario.class);
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BlockPos TRAIN_ORIGIN = new BlockPos(512, 64, 512);
    private static final BlockPos POWER_ORIGIN = TRAIN_ORIGIN.offset(24, 0, 40);
    private static final int CHARGE_BEFORE_ASSEMBLY = 1_500;
    private static final long MIN_RETAINED_ENERGY = 512;
    private static final int PREPARE_TIMEOUT_TICKS = 1_200;
    private static final int VERIFY_TIMEOUT_TICKS = 600;
    private static final int SETUP_TICKS = 45;
    private static final int LIVE_CARRIAGE_GRACE_TICKS = 200;
    private static final int NO_POWER_TIMEOUT_TICKS = 180;
    private static final int RESUME_TIMEOUT_TICKS = 180;
    private static final String PHASE_PROPERTY = "createmaglev.tests.restartPhase";
    private static final String CHECKPOINT_PROPERTY = "createmaglev.tests.restartCheckpoint";
    private static final String RESULT_PROPERTY = "createmaglev.tests.restartResult";

    private NativeRestartScenario() {}

    /** Called only after the test helper's explicit disposable-test guard succeeds. */
    public static void register() {
        String phase = System.getProperty(PHASE_PROPERTY, "");
        if (!phase.equals("prepare") && !phase.equals("verify"))
            return;

        Path checkpoint = requiredAbsolutePath(CHECKPOINT_PROPERTY);
        Path result = requiredAbsolutePath(RESULT_PROPERTY);
        Scenario scenario = new Scenario(phase, checkpoint, result);
        ServerTickEvents.START_SERVER_TICK.register(scenario::onStartTick);
        ServerTickEvents.END_SERVER_TICK.register(scenario::onEndTick);
        LOGGER.info("Restart scenario registered: phase={}, checkpoint={}, result={}", phase, checkpoint, result);
    }

    private static Path requiredAbsolutePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing required restart scenario system property " + property);
        Path path = Path.of(value).normalize();
        if (!path.isAbsolute())
            throw new IllegalStateException("Restart scenario property must be absolute: " + property + "=" + value);
        return path;
    }

    private static final class Scenario {
        private final String phase;
        private final Path checkpointPath;
        private final Path resultPath;
        private final UUID serverSession = UUID.randomUUID();
        private final long serverPid = ProcessHandle.current().pid();

        private NativeNewAgePowerFixture source;
        private NativeTrainFixture.BuiltTrain built;
        private ServerLevel prepareLevel;
        private Train prepareTrain;
        private Vec3 initialTrainPosition;
        private int serverTicks;
        private int carriageWaitTicks;
        private boolean finished;

        private JsonObject checkpoint;
        private ServerLevel verifyLevel;
        private Train reloadedTrain;
        private TractionSubstationBlockEntity reloadedSubstation;
        private ElectricalConnectorBlockEntity sourceConnector;
        private ElectricalConnectorBlockEntity sinkConnector;
        private CreativeMotorBlockEntity reloadedMotor;
        private CarriageContraptionEntity liveCarriage;
        private Vec3 resumeStartPosition;
        private long noPowerStartEnergy;
        private long resumeStartEnergy;
        private double loadedSpeed;
        private float loadedLift;
        private double observedBrakeSpeed;
        private float observedLandingLift;
        private double firstLiveSpeed;
        private float firstLiveLift;
        private double previousLiveSpeed;
        private float previousLiveLift;
        private int liveBodySamples;
        private int liveBrakingSamples;
        private int liveLandingSamples;
        private boolean sawNonzeroLiveBody;
        private int verifyStateTicks;
        private VerifyStage verifyStage = VerifyStage.WAITING_FOR_RELOAD;
        private JsonObject reloadEvidence;
        private JsonObject noPowerEvidence;

        private Scenario(String phase, Path checkpointPath, Path resultPath) {
            this.phase = phase;
            this.checkpointPath = checkpointPath;
            this.resultPath = resultPath;
        }

        private void onStartTick(MinecraftServer server) {
            if (finished || !phase.equals("verify") || verifyStage != VerifyStage.WAITING_FOR_RELOAD)
                return;
            try {
                if (checkpoint == null)
                    checkpoint = readCheckpoint();
                captureReloadedState(server);
            } catch (Throwable error) {
                fail(server, error);
            }
        }

        private void onEndTick(MinecraftServer server) {
            if (finished)
                return;
            serverTicks++;
            try {
                if (phase.equals("prepare"))
                    prepareTick(server);
                else
                    verifyTick(server);
                int timeout = phase.equals("prepare") ? PREPARE_TIMEOUT_TICKS : VERIFY_TIMEOUT_TICKS;
                if (!finished && serverTicks > timeout)
                    throw new IllegalStateException("Restart scenario timed out after " + serverTicks + " server ticks: " + diagnostics());
            } catch (Throwable error) {
                fail(server, error);
            }
        }

        private void prepareTick(MinecraftServer server) {
            if (source == null) {
                prepareLevel = server.overworld();
                // Let vanilla preload the saved carriage region before the restarted
                // server's first train tick; never freeze trains or inject body state.
                prepareLevel.setDefaultSpawnPos(TRAIN_ORIGIN.offset(16, 0, 16), 0);
                source = NativeNewAgePowerFixture.build(prepareLevel, POWER_ORIGIN);
                built = NativeTrainFixture.build(prepareLevel, TRAIN_ORIGIN, MaglevContent.MAGLEV_TRACK,
                        MaglevContent.MAGLEV_MATERIAL, "restart-maglev", MaglevContent.MAGLEV_BOGEY, false);
                LOGGER.info("Restart prepare built native guideway and NewAge source at gameTime={}", prepareLevel.getGameTime());
                return;
            }

            if (prepareTrain == null) {
                if (prepareLevel.getGameTime() < SETUP_TICKS || !source.hasLiveCopperWire()
                        || source.substation().energyStored() < CHARGE_BEFORE_ASSEMBLY)
                    return;
                if (source.motor().generatedSpeed.value == 0)
                    throw new IllegalStateException("Native NewAge creative motor is not generating before train assembly");
                prepareTrain = built.assemble();
                requireRegisteredTrain(prepareTrain.id, prepareTrain);
                built.scheduleToDestination();
                if (!expectedSchedule(prepareTrain, built.destinationName))
                    throw new IllegalStateException("Create did not retain the active native train schedule");
                initialTrainPosition = leadingPosition(prepareTrain);
                carriageWaitTicks = 0;
                LOGGER.info("Restart prepare assembled and scheduled train {} with initial position {} and energy {}",
                        prepareTrain.id, initialTrainPosition, source.substation().energyStored());
                return;
            }

            Train train = requireRegisteredTrain(prepareTrain.id, prepareTrain);
            TractionState tractionState = (TractionState) train;
            var traction = tractionState.maglev$traction();
            CarriageContraptionEntity entity = built.liveCarriage();
            if (entity == null) {
                if (++carriageWaitTicks > LIVE_CARRIAGE_GRACE_TICKS)
                    throw new IllegalStateException("Create did not materialize the native carriage entity after assembly");
                return;
            }
            requireLiveCarriage(prepareLevel, train, entity);
            double moved = initialTrainPosition.distanceTo(leadingPosition(train));
            long energy = source.substation().energyStored();
            if (!traction.authoritative() || !traction.powered() || traction.tier() != TrainTier.MAGLEV
                    || traction.lift() < 0.15f || Math.abs(train.speed) < 0.2 || moved < 4
                    || energy < MIN_RETAINED_ENERGY)
                return;

            assertPhysicalLift(entity, train, traction.lift());
            if (!source.substationPos().equals(TractionPower.nearestSupply(prepareLevel, leadingPosition(train))))
                throw new IllegalStateException("The moving train was not powered by its actual retained NewAge substation");
            prepareAndHalt(server, train, entity, moved, energy);
        }

        private void prepareAndHalt(MinecraftServer server, Train train, CarriageContraptionEntity entity,
                                    double moved, long energyBeforeCut) {
            if (!source.hasLiveCopperWire())
                throw new IllegalStateException("NewAge copper wire was not connected immediately before controlled cutoff");
            if (!expectedSchedule(train, built.destinationName))
                throw new IllegalStateException("Native schedule disappeared or paused before restart checkpoint");
            TrackMaterial material = currentMaterial(train);
            String bogeyStyle = train.carriages.get(0).leadingBogey().getStyle().id.toString();
            if (!MaglevBogeyStyles.MAGLEV.id.toString().equals(bogeyStyle)
                    || !material.id.equals(MaglevContent.MAGLEV_MATERIAL.id)
                    || !material.trackType.id.equals(MaglevContent.MAGLEV_TYPE.id))
                throw new IllegalStateException("Prepared carriage did not retain its native maglev style/material/type: style="
                        + bogeyStyle + ", material=" + material.id + ", type=" + material.trackType.id);

            source.stopGeneration();
            source.motor().setChanged();
            if (source.motor().generatedSpeed.value != 0)
                throw new IllegalStateException("NewAge mechanical generation did not stop at controlled cutoff");
            source.disconnect();
            source.sourceConnector().setChanged();
            source.sinkConnector().setChanged();
            if (!source.sourceConnector().getConnectorPositions().isEmpty()
                    || !source.sinkConnector().getConnectorPositions().isEmpty()
                    || source.hasLiveCopperWire())
                throw new IllegalStateException("NewAge copper connectors remained connected after controlled cutoff");

            BlockPos substationPos = source.substationPos();
            BlockPos breakerPos = substationPos.below();
            prepareLevel.setBlock(breakerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            BlockState substationState = prepareLevel.getBlockState(substationPos);
            if (!substationState.getValue(TractionSubstationBlock.POWERED))
                throw new IllegalStateException("The real traction substation breaker did not open after placing the redstone block");
            if (TractionPower.nearestSupply(prepareLevel, leadingPosition(train)) != null)
                throw new IllegalStateException("TractionPower still found a live supply with the real breaker open");

            long retainedEnergy = source.substation().energyStored();
            if (retainedEnergy <= 0 || retainedEnergy > MaglevConfig.substationCapacity()
                    || retainedEnergy < MIN_RETAINED_ENERGY)
                throw new IllegalStateException("Real substation did not retain usable finite charge at cutoff: " + retainedEnergy);
            if (retainedEnergy != energyBeforeCut)
                throw new IllegalStateException("The substation energy buffer changed during the controlled wire/breaker cutoff: "
                        + energyBeforeCut + " -> " + retainedEnergy);
            JsonObject pose = vector(leadingPosition(train));
            JsonObject power = new JsonObject();
            power.add("substationPos", blockPos(substationPos));
            power.add("breakerPos", blockPos(breakerPos));
            power.add("powerOrigin", blockPos(POWER_ORIGIN));
            power.add("motorPos", blockPos(source.motorPos()));
            power.add("brushPos", blockPos(source.brushPos()));
            power.add("coilPos", blockPos(source.coilPos()));
            power.add("sourceConnectorPos", blockPos(POWER_ORIGIN.above().relative(Direction.EAST)));
            power.add("sinkConnectorPos", blockPos(substationPos.relative(Direction.EAST)));
            power.addProperty("retainedEnergy", retainedEnergy);
            power.addProperty("capacity", MaglevConfig.substationCapacity());
            power.addProperty("breakerOpen", true);
            power.addProperty("generationStopped", true);
            power.addProperty("wireDisconnected", true);
            power.addProperty("wireConnectedBeforeCut", true);
            power.addProperty("energyBeforeCut", energyBeforeCut);

            checkpoint = new JsonObject();
            checkpoint.addProperty("schemaVersion", 1);
            checkpoint.addProperty("phase", "prepare");
            checkpoint.addProperty("passed", true);
            checkpoint.addProperty("trainId", train.id.toString());
            checkpoint.addProperty("graphId", train.graph.id.toString());
            checkpoint.addProperty("dimension", prepareLevel.dimension().location().toString());
            checkpoint.addProperty("destinationName", built.destinationName);
            checkpoint.addProperty("serverPid", serverPid);
            checkpoint.addProperty("serverSession", serverSession.toString());
            checkpoint.addProperty("gameTime", prepareLevel.getGameTime());
            checkpoint.add("trainOrigin", blockPos(TRAIN_ORIGIN));
            checkpoint.add("power", power);
            JsonObject trainState = new JsonObject();
            trainState.addProperty("styleId", bogeyStyle);
            trainState.addProperty("materialId", material.id.toString());
            trainState.addProperty("trackTypeId", material.trackType.id.toString());
            trainState.addProperty("trackBlockId", BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString());
            trainState.addProperty("tier", TrainTier.MAGLEV.name());
            trainState.addProperty("speed", train.speed);
            trainState.addProperty("targetSpeed", train.targetSpeed);
            trainState.addProperty("lift", tractionLift(train));
            trainState.addProperty("poweredBeforeCut", ((TractionState) train).maglev$traction().powered());
            trainState.addProperty("scheduleDestination", destinationName(train));
            trainState.addProperty("scheduleEntryCount", train.runtime.getSchedule().entries.size());
            trainState.add("leadingPoint", pose);
            trainState.addProperty("distanceMovedBeforeCut", moved);
            trainState.addProperty("liveCarriageUuidBeforeRestart", entity.getUUID().toString());
            checkpoint.add("train", trainState);
            writeJson(checkpointPath, checkpoint);

            JsonObject evidence = new JsonObject();
            evidence.addProperty("trainRegistryResolved", true);
            evidence.addProperty("schedulePersisted", true);
            evidence.addProperty("nativeMovementBlocks", moved);
            evidence.addProperty("nativeSpeed", train.speed);
            evidence.addProperty("nativeLift", tractionLift(train));
            evidence.addProperty("liveCarriageUuid", entity.getUUID().toString());
            evidence.addProperty("substationRetainedEnergy", retainedEnergy);
            evidence.addProperty("generationStopped", true);
            evidence.addProperty("wireDisconnected", true);
            evidence.addProperty("breakerOpened", true);
            evidence.addProperty("checkpointWritten", true);
            JsonObject result = baseResult(true);
            result.addProperty("checkpoint", checkpointPath.toString());
            result.add("evidence", evidence);
            writeJson(resultPath, result);
            LOGGER.info("Restart prepare checkpoint complete: train={}, moved={}, speed={}, lift={}, retainedEnergy={}, serverPid={}, session={}",
                    train.id, moved, train.speed, tractionLift(train), retainedEnergy, serverPid, serverSession);
            finished = true;
            server.halt(false);
        }

        private void captureReloadedState(MinecraftServer server) {
            ServerLevel level = server.overworld();
            if (!level.dimension().location().toString().equals(checkpoint.get("dimension").getAsString()))
                throw new IllegalStateException("Checkpoint dimension is not the dedicated server overworld");
            BlockPos trainOrigin = readBlockPos(checkpoint.getAsJsonObject("trainOrigin"));
            JsonObject power = checkpoint.getAsJsonObject("power");
            BlockPos substationPos = readBlockPos(power.getAsJsonObject("substationPos"));
            BlockPos sourceConnectorPos = readBlockPos(power.getAsJsonObject("sourceConnectorPos"));
            BlockPos sinkConnectorPos = readBlockPos(power.getAsJsonObject("sinkConnectorPos"));
            BlockPos motorPos = readBlockPos(power.getAsJsonObject("motorPos"));
            if (!level.hasChunkAt(trainOrigin) || !level.hasChunkAt(substationPos)
                    || !level.hasChunkAt(sourceConnectorPos) || !level.hasChunkAt(sinkConnectorPos)
                    || !level.hasChunkAt(motorPos))
                return;

            UUID trainId = UUID.fromString(checkpoint.get("trainId").getAsString());
            Train train = Create.RAILWAYS.trains.get(trainId);
            if (train == null)
                return;
            if (train.id == null || !trainId.equals(train.id) || train.carriages == null || train.carriages.isEmpty())
                throw new IllegalStateException("Create railway registry returned an invalid persisted train for " + trainId);
            if (train.graph == null || !train.graph.id.toString().equals(checkpoint.get("graphId").getAsString())
                    || Create.RAILWAYS.trackNetworks.get(train.graph.id) != train.graph)
                throw new IllegalStateException("Reloaded train is not attached to its persisted registered Create track graph");

            BlockEntity stationEntity = level.getBlockEntity(substationPos);
            BlockEntity sourceEntity = level.getBlockEntity(sourceConnectorPos);
            BlockEntity sinkEntity = level.getBlockEntity(sinkConnectorPos);
            BlockEntity motorEntity = level.getBlockEntity(motorPos);
            if (!(stationEntity instanceof TractionSubstationBlockEntity station)
                    || !(sourceEntity instanceof ElectricalConnectorBlockEntity sourceWire)
                    || !(sinkEntity instanceof ElectricalConnectorBlockEntity sinkWire)
                    || !(motorEntity instanceof CreativeMotorBlockEntity motor))
                return;

            JsonObject trainState = checkpoint.getAsJsonObject("train");
            double savedSpeed = trainState.get("speed").getAsDouble();
            double savedTargetSpeed = trainState.get("targetSpeed").getAsDouble();
            float savedLift = trainState.get("lift").getAsFloat();
            Vec3 savedPoint = readVector(trainState.getAsJsonObject("leadingPoint"));
            Vec3 currentPoint = leadingPosition(train);
            String style = train.carriages.get(0).leadingBogey().getStyle().id.toString();
            TrackMaterial material = currentMaterial(train);
            if (!trainState.get("styleId").getAsString().equals(style)
                    || !trainState.get("materialId").getAsString().equals(material.id.toString())
                    || !trainState.get("trackTypeId").getAsString().equals(material.trackType.id.toString()))
                throw new IllegalStateException("Persisted train lost its bogey style, track material, or native track type after disk reload");
            if (Math.abs(train.speed - savedSpeed) > 1.0e-9
                    || Math.abs(train.targetSpeed - savedTargetSpeed) > 1.0e-9
                    || Math.abs(tractionLift(train) - savedLift) > 1.0e-6
                    || currentPoint.distanceTo(savedPoint) > 1.0e-5)
                throw new IllegalStateException("Native train speed/target/lift/track pose did not load from the checkpoint: speed="
                        + train.speed + "/" + savedSpeed + ", target=" + train.targetSpeed + "/" + savedTargetSpeed
                        + ", lift=" + tractionLift(train) + "/" + savedLift
                        + ", pointDelta=" + currentPoint.distanceTo(savedPoint));
            long retainedEnergy = power.get("retainedEnergy").getAsLong();
            if (station.energyStored() != retainedEnergy)
                throw new IllegalStateException("Substation energy did not persist exactly across restart: loaded="
                        + station.energyStored() + ", checkpoint=" + retainedEnergy);
            BlockState stationState = level.getBlockState(substationPos);
            if (!stationState.hasProperty(TractionSubstationBlock.POWERED)
                    || !stationState.getValue(TractionSubstationBlock.POWERED)
                    || !level.getBlockState(substationPos.below()).is(Blocks.REDSTONE_BLOCK))
                throw new IllegalStateException("The persisted traction breaker did not remain open after restart");
            if (!sourceWire.getConnectedConnectors().isEmpty() || !sinkWire.getConnectedConnectors().isEmpty()
                    || !sourceWire.getConnectorPositions().isEmpty() || !sinkWire.getConnectorPositions().isEmpty())
                throw new IllegalStateException("The persisted NewAge copper wire became connected during server reload");
            if (motor.generatedSpeed.value != 0)
                throw new IllegalStateException("The NewAge source resumed generation after the intentional server restart");
            var traction = ((TractionState) train).maglev$traction();
            if (traction.powered() || traction.authoritative())
                throw new IllegalStateException("A newly loaded train began with synthetic authoritative traction state");
            if (TractionPower.nearestSupply(level, currentPoint) != null)
                throw new IllegalStateException("A loaded train found a live traction supply while its persisted breaker was open");
            if (!expectedSchedule(train, checkpoint.get("destinationName").getAsString()))
                throw new IllegalStateException("Create did not restore the persisted train schedule and destination");

            verifyLevel = level;
            reloadedTrain = train;
            reloadedSubstation = station;
            sourceConnector = sourceWire;
            sinkConnector = sinkWire;
            reloadedMotor = motor;
            loadedSpeed = train.speed;
            loadedLift = traction.lift();
            noPowerStartEnergy = station.energyStored();
            observedBrakeSpeed = Math.abs(train.speed);
            observedLandingLift = traction.lift();
            reloadEvidence = new JsonObject();
            reloadEvidence.addProperty("createRegistryTrainResolved", true);
            reloadEvidence.addProperty("trainId", train.id.toString());
            reloadEvidence.addProperty("graphId", train.graph.id.toString());
            reloadEvidence.addProperty("sameTrainUuid", train.id.toString().equals(checkpoint.get("trainId").getAsString()));
            reloadEvidence.addProperty("liveScheduleRestored", true);
            reloadEvidence.addProperty("scheduleDestination", destinationName(train));
            reloadEvidence.addProperty("styleId", style);
            reloadEvidence.addProperty("materialId", material.id.toString());
            reloadEvidence.addProperty("trackTypeId", material.trackType.id.toString());
            reloadEvidence.addProperty("loadedSpeed", train.speed);
            reloadEvidence.addProperty("loadedLift", traction.lift());
            reloadEvidence.addProperty("loadedTargetSpeed", train.targetSpeed);
            reloadEvidence.addProperty("checkpointTargetSpeed", savedTargetSpeed);
            reloadEvidence.add("loadedLeadingPoint", vector(currentPoint));
            reloadEvidence.addProperty("checkpointPoseDistance", currentPoint.distanceTo(savedPoint));
            reloadEvidence.addProperty("substationLoaded", true);
            reloadEvidence.addProperty("retainedEnergy", station.energyStored());
            reloadEvidence.addProperty("breakerOpenAfterReload", true);
            reloadEvidence.addProperty("newAgeWireDisconnectedAfterReload", true);
            reloadEvidence.addProperty("newAgeGenerationStoppedAfterReload", true);
            reloadEvidence.addProperty("supplyUnavailableAfterReload", true);
            if (!level.isPositionEntityTicking(BlockPos.containing(currentPoint)))
                throw new IllegalStateException("The saved carriage chunk was not entity-ticking before the first post-reload train tick");
            reloadEvidence.addProperty("carriageChunkEntityTickingBeforeFirstTrainTick", true);
            verifyStage = VerifyStage.WAITING_FOR_LIVE_CARRIAGE;
            verifyStateTicks = 0;
            LOGGER.info("Restart verify resolved persisted train {} from Create.RAILWAYS before native world ticking; speed={}, lift={}, energy={}, serverPid={}, session={}",
                    train.id, train.speed, traction.lift(), station.energyStored(), serverPid, serverSession);
        }

        private void verifyTick(MinecraftServer server) {
            if (checkpoint == null)
                checkpoint = readCheckpoint();
            if (verifyStage == VerifyStage.WAITING_FOR_RELOAD)
                return;
            verifyStateTicks++;
            if (verifyStateTicks > stageTimeout())
                throw new IllegalStateException("Restart verify stage " + verifyStage + " exceeded its bounded tick limit: " + diagnostics());
            if (Create.RAILWAYS.trains.get(reloadedTrain.id) != reloadedTrain)
                throw new IllegalStateException("The persisted train left Create.RAILWAYS during restart verification");
            if (verifyStage == VerifyStage.NATIVE_BRAKING_AND_LANDING
                    || verifyStage == VerifyStage.NATIVE_POWERED_RESUME) {
                CarriageContraptionEntity currentEntity = liveCarriage(reloadedTrain);
                if (currentEntity == null)
                    throw new IllegalStateException("Persisted native carriage disappeared during braking/resume");
                requireLiveCarriage(verifyLevel, reloadedTrain, currentEntity);
                liveCarriage = currentEntity;
            }
            if (verifyStage != VerifyStage.NATIVE_POWERED_RESUME)
                assertUnpoweredRetainedState();

            if (verifyStage == VerifyStage.WAITING_FOR_LIVE_CARRIAGE) {
                CarriageContraptionEntity entity = liveCarriage(reloadedTrain);
                if (entity == null)
                    return;
                requireLiveCarriage(verifyLevel, reloadedTrain, entity);
                liveCarriage = entity;
                var traction = ((TractionState) reloadedTrain).maglev$traction();
                firstLiveSpeed = Math.abs(reloadedTrain.speed);
                firstLiveLift = traction.lift();
                double physicalLift = assertPhysicalLift(entity, reloadedTrain, firstLiveLift);
                if (firstLiveSpeed <= 0.05 || firstLiveLift <= 0.1f || physicalLift <= 0.1)
                    throw new IllegalStateException("The live carriage appeared only after native braking/landing had finished; "
                            + "speed=" + firstLiveSpeed + ", lift=" + firstLiveLift + ", physicalLift=" + physicalLift);
                sawNonzeroLiveBody = true;
                previousLiveSpeed = firstLiveSpeed;
                previousLiveLift = firstLiveLift;
                reloadEvidence.addProperty("currentLiveCarriageResolvedByUuid", true);
                reloadEvidence.addProperty("currentLiveCarriageUuid", entity.getUUID().toString());
                reloadEvidence.addProperty("liveCarriageTrainId", entity.trainId.toString());
                reloadEvidence.addProperty("liveCarriageEntityClass", entity.getClass().getName());
                verifyStage = VerifyStage.NATIVE_BRAKING_AND_LANDING;
                verifyStateTicks = 0;
                LOGGER.info("Restart verify found live reloaded carriage {} linked to train {}", entity.getUUID(), reloadedTrain.id);
            }

            if (verifyStage == VerifyStage.NATIVE_BRAKING_AND_LANDING) {
                Train train = reloadedTrain;
                var traction = ((TractionState) train).maglev$traction();
                double speed = Math.abs(train.speed);
                double physicalLift = assertPhysicalLift(liveCarriage, train, traction.lift());
                liveBodySamples++;
                if (speed > 0.001 && speed < previousLiveSpeed - 0.0001)
                    liveBrakingSamples++;
                if (physicalLift > 0.001 && traction.lift() < previousLiveLift - 0.0001f)
                    liveLandingSamples++;
                previousLiveSpeed = speed;
                previousLiveLift = traction.lift();
                observedBrakeSpeed = Math.min(observedBrakeSpeed, speed);
                observedLandingLift = Math.min(observedLandingLift, traction.lift());
                if (noPowerStartEnergy != reloadedSubstation.energyStored())
                    throw new IllegalStateException("Retained substation energy changed while the real breaker remained open: start="
                            + noPowerStartEnergy + ", now=" + reloadedSubstation.energyStored());
                if (speed < 0.001 && traction.lift() <= 0.001f && Math.abs(physicalLift) <= 0.001
                        && sawNonzeroLiveBody && liveBrakingSamples >= 2 && liveLandingSamples >= 2) {
                    noPowerEvidence = new JsonObject();
                    noPowerEvidence.addProperty("nativeEmergencyBrakeObserved", true);
                    noPowerEvidence.addProperty("nativeLandingObserved", true);
                    noPowerEvidence.addProperty("liveNonzeroBodyObserved", sawNonzeroLiveBody);
                    noPowerEvidence.addProperty("firstLiveSpeed", firstLiveSpeed);
                    noPowerEvidence.addProperty("firstLiveLift", firstLiveLift);
                    noPowerEvidence.addProperty("liveBodySamples", liveBodySamples);
                    noPowerEvidence.addProperty("liveBrakingSamples", liveBrakingSamples);
                    noPowerEvidence.addProperty("liveLandingSamples", liveLandingSamples);
                    noPowerEvidence.addProperty("landedPhysicalBodyLift", physicalLift);
                    noPowerEvidence.addProperty("loadedSpeed", loadedSpeed);
                    noPowerEvidence.addProperty("stoppedSpeed", train.speed);
                    noPowerEvidence.addProperty("loadedLift", loadedLift);
                    noPowerEvidence.addProperty("landedLift", traction.lift());
                    noPowerEvidence.addProperty("minimumBrakedSpeed", observedBrakeSpeed);
                    noPowerEvidence.addProperty("minimumLiftDuringLanding", observedLandingLift);
                    noPowerEvidence.addProperty("energyBefore", noPowerStartEnergy);
                    noPowerEvidence.addProperty("energyAfter", reloadedSubstation.energyStored());
                    noPowerEvidence.addProperty("breakerRemainedOpen", true);
                    noPowerEvidence.addProperty("scheduleRetainedDuringBraking", expectedSchedule(train,
                            checkpoint.get("destinationName").getAsString()));
                    noPowerEvidence.addProperty("liveCarriageUuid", liveCarriage.getUUID().toString());
                    resumeStartPosition = leadingPosition(train);
                    resumeStartEnergy = reloadedSubstation.energyStored();
                    closeRealBreaker();
                    verifyStage = VerifyStage.NATIVE_POWERED_RESUME;
                    verifyStateTicks = 0;
                    LOGGER.info("Restart verify closed breaker after native braking/landing at pose {}; retained energy={}",
                            resumeStartPosition, resumeStartEnergy);
                }
                return;
            }

            if (verifyStage == VerifyStage.NATIVE_POWERED_RESUME)
                observeResume(server);
        }

        private void assertUnpoweredRetainedState() {
            BlockPos stationPos = reloadedSubstation.getBlockPos();
            BlockState stationState = verifyLevel.getBlockState(stationPos);
            if (!stationState.getValue(TractionSubstationBlock.POWERED)
                    || !verifyLevel.getBlockState(stationPos.below()).is(Blocks.REDSTONE_BLOCK))
                throw new IllegalStateException("The traction breaker opened by prepare was not still open during reload verification");
            if (!sourceConnector.getConnectedConnectors().isEmpty() || !sinkConnector.getConnectedConnectors().isEmpty()
                    || !sourceConnector.getConnectorPositions().isEmpty() || !sinkConnector.getConnectorPositions().isEmpty())
                throw new IllegalStateException("NewAge wire reconnection appeared during restart verification");
            if (reloadedMotor.generatedSpeed.value != 0)
                throw new IllegalStateException("The disconnected NewAge source generated power during restart verification");
            Vec3 point = leadingPosition(reloadedTrain);
            var traction = ((TractionState) reloadedTrain).maglev$traction();
            if (traction.powered() || traction.speedLimit() > 0.001
                    || TractionPower.nearestSupply(verifyLevel, point) != null)
                throw new IllegalStateException("A closed-off supply became available to native traction after reload");
            if (reloadedSubstation.energyStored() != noPowerStartEnergy)
                throw new IllegalStateException("Finite retained charge changed while native emergency braking/landing ran");
            if (!expectedSchedule(reloadedTrain, checkpoint.get("destinationName").getAsString()))
                throw new IllegalStateException("The persisted Create schedule was lost during unpowered braking");
        }

        private void closeRealBreaker() {
            BlockPos stationPos = reloadedSubstation.getBlockPos();
            verifyLevel.setBlock(stationPos.below(), Blocks.STONE.defaultBlockState(), 3);
            BlockState current = verifyLevel.getBlockState(stationPos);
            if (current.getValue(TractionSubstationBlock.POWERED))
                throw new IllegalStateException("Replacing the real redstone breaker did not restore the substation to the closed state");
            if (!verifyLevel.getBlockState(stationPos.below()).is(Blocks.STONE))
                throw new IllegalStateException("The real traction breaker was not replaced by its original stone support");
            if (reloadedSubstation.energyStored() != resumeStartEnergy)
                throw new IllegalStateException("Re-enabling the traction breaker altered retained charge before native train consumption");
        }

        private void observeResume(MinecraftServer server) {
            Train train = reloadedTrain;
            var traction = ((TractionState) train).maglev$traction();
            if (verifyLevel.getBlockState(reloadedSubstation.getBlockPos()).getValue(TractionSubstationBlock.POWERED))
                throw new IllegalStateException("The real traction breaker did not remain closed while the train resumed");
            if (reloadedMotor.generatedSpeed.value != 0 || !sourceConnector.getConnectedConnectors().isEmpty()
                    || !sinkConnector.getConnectedConnectors().isEmpty()
                    || !sourceConnector.getConnectorPositions().isEmpty() || !sinkConnector.getConnectorPositions().isEmpty())
                throw new IllegalStateException("Resume power was not isolated from the stopped/disconnected NewAge generator");
            double moved = resumeStartPosition.distanceTo(leadingPosition(train));
            long energy = reloadedSubstation.energyStored();
            if (traction.authoritative() && traction.powered() && traction.tier() == TrainTier.MAGLEV
                    && traction.lift() > 0.1f && moved > 0.5 && energy < resumeStartEnergy) {
                assertPhysicalLift(liveCarriage, train, traction.lift());
                if (!reloadedSubstation.getBlockPos().equals(TractionPower.nearestSupply(verifyLevel, leadingPosition(train))))
                    throw new IllegalStateException("Resumed native motion was not supplied by the persisted charged substation");
                JsonObject resume = new JsonObject();
                resume.addProperty("breakerClosed", true);
                resume.addProperty("poweredByRetainedSubstation", true);
                resume.addProperty("scheduleRetained", expectedSchedule(train, checkpoint.get("destinationName").getAsString()));
                resume.addProperty("nativePowered", traction.powered());
                resume.addProperty("nativeSpeed", train.speed);
                resume.addProperty("nativeLift", traction.lift());
                resume.addProperty("nativeMovementBlocks", moved);
                resume.addProperty("energyBefore", resumeStartEnergy);
                resume.addProperty("energyAfter", energy);
                resume.addProperty("energyConsumed", resumeStartEnergy - energy);
                resume.addProperty("generatorStillStopped", true);
                resume.addProperty("wireStillDisconnected", true);
                resume.addProperty("liveCarriageUuid", liveCarriage.getUUID().toString());
                finishVerify(server, resume);
            }
        }

        private void finishVerify(MinecraftServer server, JsonObject resumeEvidence) {
            JsonObject evidence = new JsonObject();
            evidence.add("reload", reloadEvidence);
            evidence.add("noPower", noPowerEvidence);
            evidence.add("resume", resumeEvidence);
            releaseOwnedTickets(verifyLevel);
            JsonObject result = baseResult(true);
            result.add("evidence", evidence);
            writeJson(resultPath, result);
            LOGGER.info("Restart verify complete: train={}, loadedSpeed={}, stoppedSpeed={}, resumedSpeed={}, moved={}, lift={}, energyBefore={}, energyAfter={}, serverPid={}, session={}",
                    reloadedTrain.id, loadedSpeed, noPowerEvidence.get("stoppedSpeed").getAsDouble(), resumeEvidence.get("nativeSpeed").getAsDouble(),
                    resumeEvidence.get("nativeMovementBlocks").getAsDouble(), resumeEvidence.get("nativeLift").getAsFloat(),
                    resumeStartEnergy, reloadedSubstation.energyStored(), serverPid, serverSession);
            finished = true;
            server.halt(false);
        }

        private JsonObject readCheckpoint() {
            if (!Files.isRegularFile(checkpointPath))
                throw new IllegalStateException("Prepare checkpoint JSON is missing: " + checkpointPath);
            try (var reader = Files.newBufferedReader(checkpointPath)) {
                JsonObject value = JsonParser.parseReader(reader).getAsJsonObject();
                if (!value.has("passed") || !value.get("passed").getAsBoolean()
                        || !value.has("phase") || !value.get("phase").getAsString().equals("prepare")
                        || !value.has("train") || !value.has("power"))
                    throw new IllegalStateException("Prepare checkpoint is incomplete or failed: " + checkpointPath);
                if (value.get("serverSession").getAsString().equals(serverSession.toString()))
                    throw new IllegalStateException("Verify phase is running in the prepare server session rather than a fresh JVM");
                return value;
            } catch (IOException error) {
                throw new IllegalStateException("Could not read prepare checkpoint " + checkpointPath, error);
            }
        }

        private int stageTimeout() {
            return verifyStage == VerifyStage.NATIVE_POWERED_RESUME ? RESUME_TIMEOUT_TICKS
                    : verifyStage == VerifyStage.NATIVE_BRAKING_AND_LANDING ? NO_POWER_TIMEOUT_TICKS
                    : LIVE_CARRIAGE_GRACE_TICKS;
        }

        private JsonObject baseResult(boolean passed) {
            JsonObject result = new JsonObject();
            result.addProperty("phase", phase);
            result.addProperty("passed", passed);
            result.addProperty("serverPid", serverPid);
            result.addProperty("serverSession", serverSession.toString());
            result.addProperty("serverTicks", serverTicks);
            result.addProperty("gameTime", phase.equals("prepare") && prepareLevel != null
                    ? prepareLevel.getGameTime() : verifyLevel != null ? verifyLevel.getGameTime() : 0);
            result.addProperty("checkpoint", checkpointPath.toString());
            return result;
        }

        private void fail(MinecraftServer server, Throwable error) {
            if (finished)
                return;
            finished = true;
            LOGGER.error("Native restart {} failed: {}. Diagnostics: {}", phase, error.toString(), diagnostics(), error);
            try {
                JsonObject result = baseResult(false);
                result.addProperty("error", error.getClass().getName() + ": " + error.getMessage());
                result.addProperty("diagnostics", diagnostics());
                if (checkpoint != null && phase.equals("verify"))
                    result.addProperty("checkpointTrainId", checkpoint.get("trainId").getAsString());
                writeJson(resultPath, result);
            } catch (Throwable writeError) {
                LOGGER.error("Could not write failed restart phase result to {}", resultPath, writeError);
            }
            server.halt(false);
        }

        private String diagnostics() {
            StringBuilder value = new StringBuilder("phase=").append(phase)
                    .append(", serverTicks=").append(serverTicks)
                    .append(", serverPid=").append(serverPid)
                    .append(", serverSession=").append(serverSession);
            if (prepareLevel != null) value.append(", prepareGameTime=").append(prepareLevel.getGameTime());
            if (source != null) {
                value.append(", sourceWire=").append(source.hasLiveCopperWire())
                        .append(", sourceEnergy=").append(source.substation().energyStored())
                        .append(", sourceGeneration=").append(source.motor().generatedSpeed.value);
            }
            if (prepareTrain != null) {
                value.append(", train=").append(prepareTrain.id)
                        .append(", speed=").append(prepareTrain.speed)
                        .append(", position=").append(safeLeadingPosition(prepareTrain));
                if (prepareTrain.carriages != null && !prepareTrain.carriages.isEmpty())
                    value.append(", lift=").append(tractionLift(prepareTrain));
            }
            if (reloadedTrain != null) {
                value.append(", reloadedTrain=").append(reloadedTrain.id)
                        .append(", speed=").append(reloadedTrain.speed)
                        .append(", position=").append(safeLeadingPosition(reloadedTrain))
                        .append(", lift=").append(tractionLift(reloadedTrain))
                        .append(", stage=").append(verifyStage);
                if (verifyLevel != null) {
                    var anchors = reloadedTrain.carriages.get(0).getDimensionalIfPresent(verifyLevel.dimension());
                    if (anchors != null && anchors.positionAnchor != null) {
                        BlockPos pos = BlockPos.containing(anchors.positionAnchor);
                        long chunk = new ChunkPos(pos).toLong();
                        value.append(", carriageAnchor=").append(anchors.positionAnchor)
                                .append(", anchorEntityTicking=").append(verifyLevel.isPositionEntityTicking(pos))
                                .append(", anchorChunkLoaded=").append(verifyLevel.hasChunkAt(pos))
                                .append(", anchorEntitiesLoaded=").append(verifyLevel.areEntitiesLoaded(chunk))
                                .append(", anchorChunkForced=").append(verifyLevel.getForcedChunks().contains(chunk))
                                .append(", anchorChunkStatus=").append(verifyLevel.getChunkSource().chunkMap.getChunkDebugData(new ChunkPos(pos)));
                    }
                }
            }
            if (reloadedSubstation != null) value.append(", retainedEnergy=").append(reloadedSubstation.energyStored());
            return value.toString();
        }
    }

    private enum VerifyStage {
        WAITING_FOR_RELOAD,
        WAITING_FOR_LIVE_CARRIAGE,
        NATIVE_BRAKING_AND_LANDING,
        NATIVE_POWERED_RESUME
    }

    private static Train requireRegisteredTrain(UUID id, Train expected) {
        Train registered = Create.RAILWAYS.trains.get(id);
        if (registered == null || !id.equals(registered.id) || expected != null && registered != expected)
            throw new IllegalStateException("Native train UUID is absent from Create's live railway registry: " + id);
        if (registered.graph == null || Create.RAILWAYS.trackNetworks.get(registered.graph.id) != registered.graph)
            throw new IllegalStateException("Native train graph is not registered in Create.RAILWAYS: " + id);
        return registered;
    }

    private static CarriageContraptionEntity liveCarriage(Train train) {
        if (train.carriages == null || train.carriages.isEmpty())
            throw new IllegalStateException("Persisted native train has no carriage data: " + train.id);
        return train.carriages.get(0).anyAvailableEntity();
    }

    private static void requireLiveCarriage(ServerLevel level, Train train, CarriageContraptionEntity entity) {
        Carriage carriage = train.carriages.get(0);
        Entity registered = level.getEntity(entity.getUUID());
        if (!entity.isAlive() || registered != entity || entity.getCarriage() != carriage
                || carriage.train != train || !train.id.equals(entity.trainId))
            throw new IllegalStateException("Live carriage entity is not resolved by its current UUID and linked to the registered train");
    }

    private static double assertPhysicalLift(CarriageContraptionEntity entity, Train train, float lift) {
        if (!(entity instanceof LevitationData data) || Math.abs(data.maglev$lift() - lift) > 0.0001f)
            throw new IllegalStateException("Live carriage entity does not expose the current native maglev lift");
        var anchors = entity.getCarriage().getDimensionalIfPresent(entity.level().dimension());
        if (anchors == null || anchors.positionAnchor == null)
            throw new IllegalStateException("Live reloaded carriage has no native track position anchor");
        var point = entity.getCarriage().getLeadingPoint();
        if (point.edge == null || point.edge.getLength() == 0)
            throw new IllegalStateException("Live carriage leading point is not attached to a native track edge");
        Vec3 normal = point.edge.getNormal(train.graph, point.position / point.edge.getLength());
        Vec3 displacement = entity.position().subtract(anchors.positionAnchor);
        if (displacement.distanceToSqr(normal.scale(lift)) > 0.000001)
            throw new IllegalStateException("Live carriage body did not physically align to native magnetic lift: " + displacement);
        return displacement.dot(normal);
    }

    private static Vec3 leadingPosition(Train train) {
        if (train.carriages == null || train.carriages.isEmpty() || train.graph == null)
            throw new IllegalStateException("Native train has no registered carriage or track graph");
        var point = train.carriages.get(0).getLeadingPoint();
        if (point.edge == null || point.node1 == null || point.node2 == null)
            throw new IllegalStateException("Native train leading point is detached from its saved track graph");
        return point.getPosition(train.graph);
    }

    private static Vec3 safeLeadingPosition(Train train) {
        try {
            return leadingPosition(train);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float tractionLift(Train train) {
        return ((TractionState) train).maglev$traction().lift();
    }

    private static TrackMaterial currentMaterial(Train train) {
        var point = train.carriages.get(0).leadingBogey().leading();
        TrackEdge edge = point.edge;
        if (edge == null)
            throw new IllegalStateException("Native maglev bogey is not on a live track edge");
        return edge.getTrackMaterial();
    }

    private static String destinationName(Train train) {
        if (train.runtime.getSchedule() == null || train.runtime.getSchedule().entries.isEmpty())
            return "";
        if (!(train.runtime.getSchedule().entries.get(0).instruction instanceof DestinationInstruction destination))
            return "";
        return destination.getFilter();
    }

    private static boolean expectedSchedule(Train train, String expectedDestination) {
        return train.runtime != null && train.runtime.getSchedule() != null
                && !train.runtime.paused && !train.runtime.completed
                && !train.runtime.getSchedule().entries.isEmpty()
                && train.runtime.getSchedule().entries.get(0).instruction instanceof DestinationInstruction destination
                && expectedDestination.equals(destination.getFilter());
    }

    private static JsonObject blockPos(BlockPos pos) {
        JsonObject value = new JsonObject();
        value.addProperty("x", pos.getX());
        value.addProperty("y", pos.getY());
        value.addProperty("z", pos.getZ());
        return value;
    }

    private static JsonObject vector(Vec3 pos) {
        JsonObject value = new JsonObject();
        value.addProperty("x", pos.x);
        value.addProperty("y", pos.y);
        value.addProperty("z", pos.z);
        return value;
    }

    private static BlockPos readBlockPos(JsonObject value) {
        return new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt());
    }

    private static Vec3 readVector(JsonObject value) {
        return new Vec3(value.get("x").getAsDouble(), value.get("y").getAsDouble(), value.get("z").getAsDouble());
    }

    private static void writeJson(Path path, JsonObject value) {
        try {
            Path parent = path.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.writeString(path, JSON.toJson(value) + System.lineSeparator());
        } catch (IOException error) {
            throw new IllegalStateException("Could not write restart phase JSON " + path, error);
        }
    }

    private static void releaseOwnedTickets(ServerLevel level) {
        releaseTicketRectangle(level, TRAIN_ORIGIN.getX() - 24, TRAIN_ORIGIN.getX() + 24,
                TRAIN_ORIGIN.getZ() - 24, TRAIN_ORIGIN.getZ() + NativeTrainFixture.ROUTE_LENGTH + 24);
        releaseTicketRectangle(level, POWER_ORIGIN.getX() - 32, POWER_ORIGIN.getX() + 32,
                POWER_ORIGIN.getZ() - 32, POWER_ORIGIN.getZ() + 32);
    }

    private static void releaseTicketRectangle(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++)
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++)
                level.setChunkForced(chunkX, chunkZ, false);
    }
}
