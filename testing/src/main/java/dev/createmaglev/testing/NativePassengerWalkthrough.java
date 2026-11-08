package dev.createmaglev.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TractionState;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TrainTraction;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in native server half of the full-size, on-foot passenger carriage walkthrough. */
public final class NativePassengerWalkthrough {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativePassengerWalkthrough.class);
    private static final String ENABLE_PROPERTY = "createmaglev.tests.walkthrough";
    private static final String TEST_PROPERTY = "createmaglev.tests";
    private static final String CONTROL_DIR_PROPERTY = "createmaglev.tests.controlDir";
    private static final String RESULTS_PROPERTY = "createmaglev.tests.results";
    private static final BlockPos ORIGIN = new BlockPos(64, -60, 64);
    private static final int RAIL_SETTLE_TICKS = 60;
    private static final int CARRIAGE_WAIT_TICKS = 200;
    private static final int BOARDING_SETTLE_TICKS = 40;
    private static final int MOTION_WAIT_TICKS = 800;
    private static final int CHECKPOINT_SUPPORT_WAIT_TICKS = 80;
    private static final int REQUIRED_STABLE_SUPPORT_TICKS = 3;
    private static final int SOURCE_COUNT = 5;
    private static final int SHUTDOWN_DELAY_TICKS = 100;
    private static final double SERVICE_THROTTLE = 0.1;
    private static final double MIN_CHECKPOINT_CARRIAGE_TRAVEL = 1.0;
    private static final List<String> CHECKPOINT_ORDER = List.of("inside", "rear", "returned");
    private static Scenario active;

    private NativePassengerWalkthrough() {}

    /** Registers nothing unless the dedicated walkthrough property is explicitly enabled. */
    public static void register() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)
                || FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        if (!Boolean.getBoolean(TEST_PROPERTY)) {
            throw new IllegalStateException("The passenger walkthrough requires -Dcreatemaglev.tests=true");
        }
        if (active != null) throw new IllegalStateException("The passenger walkthrough was registered more than once");

        Path controlDirectory = requiredAbsolutePath(CONTROL_DIR_PROPERTY);
        Path resultsFile = requiredAbsolutePath(RESULTS_PROPERTY);
        Scenario scenario = new Scenario(controlDirectory, resultsFile);
        active = scenario;
        ServerPlayNetworking.registerGlobalReceiver(MaglevTestMod.CONTROL, (server, sender, handler, buffer, responseSender) -> {
            final String message;
            try {
                message = buffer.readUtf();
            } catch (RuntimeException error) {
                server.execute(() -> scenario.fail(server, "Malformed walkthrough control packet: " + Scenario.describe(error)));
                return;
            }
            server.execute(() -> scenario.receive(server, sender, message));
        });
        ServerTickEvents.END_SERVER_TICK.register(scenario::tick);
        LOGGER.info("Native passenger walkthrough registered; controlDir={}, results={}", controlDirectory, resultsFile);
    }

    private static Path requiredAbsolutePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The passenger walkthrough requires an explicit -D" + property + " path");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalStateException("The passenger walkthrough requires an absolute -D" + property + " path: " + value);
        }
        return path;
    }

    private static final class Scenario {
        private final Path controlDirectory;
        private final Path resultsFile;
        private final JsonArray checkpoints = new JsonArray();
        private final JsonArray nativeMotionSamples = new JsonArray();
        private final List<NativeNewAgePowerFixture> sources = new ArrayList<>(SOURCE_COUNT);
        private final long[] initialEnergy = new long[SOURCE_COUNT];
        private final long[] departureEnergy = new long[SOURCE_COUNT];
        private final boolean[] wireConnectedBeforeDeparture = new boolean[SOURCE_COUNT];
        private final boolean[] generatorStoppedAtDepart = new boolean[SOURCE_COUNT];
        private final boolean[] breakerOpenedAtDepart = new boolean[SOURCE_COUNT];
        private String phase = "idle";
        private String pendingCheckpoint;
        private ServerPlayer player;
        private UUID playerId;
        private ServerLevel level;
        private NativeTrainFixture.BuiltTrain built;
        private CarriageContraptionEntity carriage;
        private UUID trainId;
        private UUID carriageId;
        private JsonObject geometry;
        private Vec3 exteriorPosition;
        private Vec3 exteriorTarget;
        private Vec3 boardingBaseline;
        private Vec3 boardingBaselineLocal;
        private Vec3 previousWalkingLocal;
        private Vec3 lastWalkingLocal;
        private Vec3 carriagePositionAtReady;
        private Vec3 departCarriagePosition;
        private Vec3 previousMotionCarriagePosition;
        private Vec3 lastMotionDisplacement = Vec3.ZERO;
        private Vec3 insidePosition;
        private Vec3 insidePositionLocal;
        private Vec3 insideCarriagePosition;
        private Vec3 rearPosition;
        private Vec3 rearCarriagePosition;
        private Vec3 returnedPosition;
        private Vec3 returnedCarriagePosition;
        private Vec3 previousMovingLocal;
        private int phaseTick;
        private int boardTicks;
        private int stableBoardTicks;
        private int stableExteriorTicks;
        private int nextCheckpoint;
        private int walkingMovementTicks;
        private int movingWalkingMovementTicks;
        private int movingTicksSampled;
        private int poweredMovingTicks;
        private int physicallyMovingTicks;
        private int stableSupportTicks;
        private int pendingCheckpointTicks;
        private int pendingSupportTicks;
        private int motionWaitTicks;
        private int shutdownTicks = -1;
        private double observedPathLength;
        private double movingWalkingPathLength;
        private double movingCarriagePathLength;
        private double maximumSingleWalkingStep;
        private double maximumMovingWalkingStep;
        private double maximumPlayerDisplacement;
        private double maximumZ;
        private double minimumX;
        private double insideToRearMovingLocalNetAisleProgressBlocks;
        private double rearToReturnedMovingLocalNetAisleProgressBlocks;
        private double maximumObservedSpeed;
        private double minimumObservedSpeed = Double.POSITIVE_INFINITY;
        private double maximumObservedLift;
        private double maximumObservedBodyLift;
        private boolean initialExteriorStaged;
        private boolean boardingTeleportPerformed;
        private boolean interiorDoorOpened;
        private boolean departureStarted;

        Scenario(Path controlDirectory, Path resultsFile) {
            this.controlDirectory = controlDirectory;
            this.resultsFile = resultsFile;
        }

        void receive(MinecraftServer server, ServerPlayer sender, String raw) {
            if (shutdownTicks >= 0) return;
            try {
                var parsed = com.google.gson.JsonParser.parseString(raw);
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("Walkthrough control request must be a JSON object");
                JsonObject request = parsed.getAsJsonObject();
                String type = requiredString(request, "type");
                if ("hello".equals(type)) {
                    begin(server, sender);
                    return;
                }
                requireOwnedPlayer(server, sender);
                switch (type) {
                    case "board" -> board(server);
                    case "depart" -> depart(server);
                    case "checkpoint" -> checkpoint(server, requiredString(request, "name"));
                    case "finish" -> finish(server);
                    default -> throw new IllegalArgumentException("Unsupported passenger walkthrough control type: " + type);
                }
            } catch (Throwable error) {
                fail(server, describe(error));
            }
        }

        void tick(MinecraftServer server) {
            if (shutdownTicks >= 0) {
                if (--shutdownTicks <= 0) server.halt(false);
                return;
            }
            if (player == null) return;
            try {
                requireOwnedPlayer(server, player);
                switch (phase) {
                    case "preparing" -> {
                        int elapsed = server.getTickCount() - phaseTick;
                        if (elapsed > CARRIAGE_WAIT_TICKS) {
                            throw new IllegalStateException("Native NewAge sources did not connect and fully charge during staging: "
                                    + sourceEnergyEvidence());
                        }
                        if (elapsed >= RAIL_SETTLE_TICKS && sourcesReadyToAssemble()) {
                            for (NativeNewAgePowerFixture source : sources) {
                                level.setBlock(source.substationPos().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                            }
                            requireStagedPowerSources(true);
                            built.assemble();
                            phase = "waiting_for_carriage";
                            phaseTick = server.getTickCount();
                        }
                    }
                    case "waiting_for_carriage" -> {
                        requireStagedPowerSources(false);
                        if (server.getTickCount() - phaseTick > CARRIAGE_WAIT_TICKS) {
                            throw new IllegalStateException("Native station assembly completed without a live carriage entity");
                        }
                        CarriageContraptionEntity live = built.liveCarriage();
                        if (live != null && live.getContraption() != null && live.getCarriage() != null) {
                            carriage = live;
                            trainId = built.train.id;
                            carriageId = carriage.getUUID();
                            requireStationaryNativeTrain();
                            geometry = inspectAssembly(true);
                            carriagePositionAtReady = carriage.position();
                            stageExterior();
                            phase = "exterior_staging";
                            phaseTick = server.getTickCount();
                        }
                    }
                    case "exterior_staging" -> settleExterior(server);
                    case "awaiting_board" -> {
                        requirePlayerWalkingMode();
                        requireStationaryNativeTrain();
                        requireStagedPowerSources(false);
                    }
                    case "boarding" -> settleBoarding(server);
                    case "boarding_walk" -> {
                        requirePlayerWalkingMode();
                        requireStationaryNativeTrain();
                        requireStagedPowerSources(false);
                        sampleWalkingPosition();
                        if (pendingCheckpoint != null) processPendingCheckpoint(server);
                    }
                    case "awaiting_depart" -> {
                        requirePlayerWalkingMode();
                        requireStationaryNativeTrain();
                        requireStagedPowerSources(true);
                        if (insidePositionLocal == null
                                || horizontalDistance(localPosition(), insidePositionLocal) > 0.35
                                || Math.abs(localPosition().y - insidePositionLocal.y) > 0.2) {
                            throw new IllegalStateException("The owner moved from the accepted inside checkpoint before depart");
                        }
                    }
                    case "departing" -> {
                        sampleNativeMotion(server, false);
                        if (++motionWaitTicks > MOTION_WAIT_TICKS) {
                            throw new IllegalStateException("Native scheduled train did not reach verified powered physical motion within "
                                    + MOTION_WAIT_TICKS + " ticks; " + motionDiagnostic());
                        }
                        attemptMovingReady(server);
                    }
                    case "moving_walk" -> {
                        sampleNativeMotion(server, true);
                        if (pendingCheckpoint != null) processPendingCheckpoint(server);
                    }
                    default -> { }
                }
            } catch (Throwable error) {
                fail(server, describe(error));
            }
        }

        private void begin(MinecraftServer server, ServerPlayer sender) throws IOException {
            if (!"idle".equals(phase)) throw new IllegalStateException("Walkthrough hello arrived during phase " + phase);
            if (server.getPlayerList().getPlayers().size() != 1 || server.getPlayerList().getPlayers().get(0) != sender) {
                throw new IllegalStateException("The walkthrough requires exactly one connected owned player");
            }
            if (sender.serverLevel() != server.overworld()) {
                throw new IllegalStateException("The owned walkthrough player must complete onboarding in the Overworld");
            }
            player = sender;
            playerId = sender.getUUID();
            level = sender.serverLevel();
            Files.createDirectories(controlDirectory);
            level.setDayTime(6000L);
            level.setWeatherParameters(100000, 0, false, false);
            built = NativePassengerTrainFixture.build(level, ORIGIN);
            for (int i = 0; i < SOURCE_COUNT; i++) {
                NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level,
                        ORIGIN.offset(24, 0, 24 + 48 * i));
                sources.add(source);
                initialEnergy[i] = source.substation().energyStored();
            }
            if (sources.size() != SOURCE_COUNT) throw new IllegalStateException("Passenger demo requires exactly five native NewAge sources");
            level.setDefaultSpawnPos(built.leadingBogeyPos.offset(-8, 2, 3), -90.0f);
            level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, server);
            phase = "preparing";
            phaseTick = server.getTickCount();
            LOGGER.info("Preparing full-size native passenger fixture at {} with five naturally charging sources for player {}",
                    ORIGIN, playerId);
        }

        private void stageExterior() {
            if (player.isSpectator()) {
                throw new IllegalStateException("The walkthrough owner was a spectator before exterior staging");
            }
            player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.getInventory().clearContent();
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

            Vec3 exteriorFeet = worldPosition(NativePassengerTrainFixture.EXTERIOR_FEET);
            exteriorTarget = worldPosition(NativePassengerTrainFixture.EXTERIOR_TARGET);
            double dx = exteriorTarget.x - exteriorFeet.x;
            double eyeHeight = player.getEyePosition().y - player.getY();
            double dy = exteriorTarget.y - (exteriorFeet.y + eyeHeight);
            double dz = exteriorTarget.z - exteriorFeet.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            player.teleportTo(level, exteriorFeet.x, exteriorFeet.y, exteriorFeet.z, yaw, pitch);
            exteriorPosition = player.position();
            initialExteriorStaged = true;
            requirePlayerWalkingMode();
            JsonObject footing = inspectWorldPlatformSupport(player.position());
            if (!footing.get("platformFaceSturdy").getAsBoolean()) {
                throw new IllegalStateException("The exterior camera staging position has no real platform support");
            }
        }
        private void settleExterior(MinecraftServer server) {
            requirePlayerWalkingMode();
            requireStationaryNativeTrain();
            if (server.getTickCount() - phaseTick > BOARDING_SETTLE_TICKS) {
                throw new IllegalStateException("The exterior player staging did not settle on the world platform");
            }
            double horizontalDrift = horizontalDistance(player.position(), exteriorPosition);
            double verticalDrift = Math.abs(player.getY() - exteriorPosition.y);
            if (horizontalDrift > 0.25 || verticalDrift > 0.15) {
                throw new IllegalStateException("The player moved before the exterior ready event");
            }
            JsonObject footing = inspectWorldPlatformSupport(player.position());
            if (player.onGround() && footing.get("supported").getAsBoolean()) stableExteriorTicks++;
            else stableExteriorTicks = 0;
            if (stableExteriorTicks < 2) return;

            phase = "awaiting_board";
            JsonObject ready = new JsonObject();
            ready.addProperty("type", "ready");
            ready.add("geometry", geometry.deepCopy());
            ready.add("evidence", currentEvidence());
            send(ready);
            LOGGER.info("Native passenger carriage is assembled and exterior-staged: train={}, carriage={}, geometry={}",
                    trainId, carriageId, geometry);
        }

        private void board(MinecraftServer server) {
            if (!"awaiting_board".equals(phase)) throw new IllegalStateException("Board request arrived during phase " + phase);
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            requireStationaryNativeTrain();
            if (boardingTeleportPerformed) throw new IllegalStateException("The final boarding-position staging teleport was already used");

            Vec3 boardingFeet = worldPosition(NativePassengerTrainFixture.BOARDING_FEET);
            player.teleportTo(level, boardingFeet.x, boardingFeet.y, boardingFeet.z, -90.0f, 0.0f);
            boardingTeleportPerformed = true;
            boardingBaseline = player.position();
            boardingBaselineLocal = localPosition();
            previousWalkingLocal = boardingBaselineLocal;
            lastWalkingLocal = boardingBaselineLocal;
            maximumZ = boardingBaselineLocal.z;
            minimumX = boardingBaselineLocal.x;
            maximumSingleWalkingStep = 0;
            maximumPlayerDisplacement = 0;
            observedPathLength = 0;
            walkingMovementTicks = 0;
            boardTicks = 0;
            stableBoardTicks = 0;
            phase = "boarding";
            phaseTick = server.getTickCount();
        }
        private void depart(MinecraftServer server) {
            if (!"awaiting_depart".equals(phase)) {
                throw new IllegalStateException("Depart request arrived before the stationary inside checkpoint: phase=" + phase);
            }
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            requireStationaryNativeTrain();
            requireStagedPowerSources(true);
            if (sources.size() != SOURCE_COUNT || built.train == null) {
                throw new IllegalStateException("Native NewAge sources or assembled passenger train are missing at depart");
            }

            for (int i = 0; i < SOURCE_COUNT; i++) {
                NativeNewAgePowerFixture source = sources.get(i);
                departureEnergy[i] = source.substation().energyStored();
                if (!source.hasLiveCopperWire() || departureEnergy[i] <= initialEnergy[i]
                        || source.motor().generatedSpeed.value == 0) {
                    throw new IllegalStateException("NewAge source " + i + " was not naturally charged through its live native generator before depart: "
                            + sourceDiagnostic(i));
                }
                wireConnectedBeforeDeparture[i] = true;
                source.disconnect();
                source.stopGeneration();
                if (source.hasLiveCopperWire() || source.motor().generatedSpeed.value != 0
                        || source.substation().energyStored() != departureEnergy[i]) {
                    throw new IllegalStateException("NewAge source " + i + " did not isolate its native generator without altering stored energy: "
                            + sourceDiagnostic(i));
                }
                generatorStoppedAtDepart[i] = source.motor().generatedSpeed.value == 0;
                BlockPos breaker = source.substationPos().below();
                if (!level.getBlockState(breaker).is(Blocks.REDSTONE_BLOCK)) {
                    throw new IllegalStateException("NewAge source breaker was not the contracted REDSTONE_BLOCK at " + breaker);
                }
                level.setBlock(breaker, Blocks.STONE.defaultBlockState(), 3);
                if (!level.getBlockState(breaker).is(Blocks.STONE)
                        || level.hasNeighborSignal(source.substationPos())) {
                    throw new IllegalStateException("Opening native traction breaker failed at " + breaker);
                }
                breakerOpenedAtDepart[i] = level.getBlockState(breaker).is(Blocks.STONE)
                        && !level.hasNeighborSignal(source.substationPos());
            }

            built.train.throttle = SERVICE_THROTTLE;
            built.scheduleToDestination();
            if (built.train.runtime.getSchedule() == null) {
                throw new IllegalStateException("Create did not retain the actual destination schedule at depart");
            }
            departureStarted = true;
            departCarriagePosition = carriage.position();
            previousMotionCarriagePosition = departCarriagePosition;
            previousMovingLocal = insidePositionLocal;
            lastWalkingLocal = insidePositionLocal;
            previousWalkingLocal = insidePositionLocal;
            motionWaitTicks = 0;
            phaseTick = server.getTickCount();
            phase = "departing";
            LOGGER.info("Passenger departure activated through Create schedule: train={}, carriage={}, destination={}, throttle={}, sources={}",
                    trainId, carriageId, built.destinationName, built.train.throttle, sourceEnergyEvidence());
        }

        private boolean sourcesReadyToAssemble() {
            if (sources.size() != SOURCE_COUNT) {
                throw new IllegalStateException("Expected exactly " + SOURCE_COUNT + " native NewAge sources; found " + sources.size());
            }
            for (int i = 0; i < SOURCE_COUNT; i++) {
                NativeNewAgePowerFixture source = sources.get(i);
                if (!source.isStillLoaded() || level.getBlockEntity(source.substationPos()) != source.substation()) {
                    throw new IllegalStateException("Native NewAge source " + i + " lost its owned staging block entities: "
                            + sourceDiagnostic(i));
                }
                BlockPos breaker = source.substationPos().below();
                if (!level.getBlockState(breaker).is(Blocks.STONE)
                        || level.hasNeighborSignal(source.substationPos())
                        || source.motor().generatedSpeed.value == 0) {
                    throw new IllegalStateException("NewAge sources must charge with their real breaker enabled before assembly: "
                            + sourceDiagnostic(i));
                }
                if (!source.hasLiveCopperWire()
                        || source.substation().energyStored() < MaglevConfig.substationCapacity()) return false;
            }
            return true;
        }

        private void requireStagedPowerSources(boolean requireNaturalCharge) {
            if (sources.size() != SOURCE_COUNT) {
                throw new IllegalStateException("Expected exactly " + SOURCE_COUNT + " native NewAge sources; found " + sources.size());
            }
            for (int i = 0; i < SOURCE_COUNT; i++) {
                NativeNewAgePowerFixture source = sources.get(i);
                if (!source.isStillLoaded() || level.getBlockEntity(source.substationPos()) != source.substation()) {
                    throw new IllegalStateException("Native NewAge source " + i + " lost its loaded owned block entities: "
                            + sourceDiagnostic(i));
                }
                if (!source.hasLiveCopperWire() || source.motor().generatedSpeed.value == 0) {
                    throw new IllegalStateException("Native NewAge source " + i + " is not naturally generating through its copper wire: "
                            + sourceDiagnostic(i));
                }
                BlockPos breaker = source.substationPos().below();
                if (!level.getBlockState(breaker).is(Blocks.REDSTONE_BLOCK)
                        || !level.hasNeighborSignal(source.substationPos())) {
                    throw new IllegalStateException("Staging breaker must keep native traction supply disabled at " + breaker);
                }
                long energy = source.substation().energyStored();
                if (energy < initialEnergy[i] || energy > MaglevConfig.substationCapacity()
                        || requireNaturalCharge && energy <= initialEnergy[i]) {
                    throw new IllegalStateException("NewAge source " + i + " did not retain naturally generated charge while staging: "
                            + sourceDiagnostic(i));
                }
            }
            if (built != null && built.train != null && Math.abs(built.train.speed) > 0.001) {
                throw new IllegalStateException("A breaker-isolated passenger train moved before depart");
            }
        }

        private void requireIsolatedPowerSources() {
            if (sources.size() != SOURCE_COUNT) throw new IllegalStateException("Native source isolation state is incomplete");
            for (int i = 0; i < SOURCE_COUNT; i++) {
                NativeNewAgePowerFixture source = sources.get(i);
                BlockPos breaker = source.substationPos().below();
                long energy = source.substation().energyStored();
                if (!source.isStillLoaded() || level.getBlockEntity(source.substationPos()) != source.substation()
                        || source.hasLiveCopperWire() || source.motor().generatedSpeed.value != 0
                        || !level.getBlockState(breaker).is(Blocks.STONE)
                        || level.hasNeighborSignal(source.substationPos())
                        || energy > departureEnergy[i]) {
                    throw new IllegalStateException("Native NewAge source " + i
                            + " was not isolated with finite non-increasing charge during the trip: " + sourceDiagnostic(i));
                }
            }
        }

        private void attemptMovingReady(MinecraftServer server) {
            requireNativeTrainIdentity();
            requireIsolatedPowerSources();
            if (built.train.runtime.getSchedule() == null) {
                throw new IllegalStateException("Native Create schedule disappeared before moving_ready");
            }
            Vec3 local = localPosition();
            if (insidePosition == null || player.getZ() - insidePosition.z < 0.5
                    || local.z < 2.0 || local.z > 5.0) return;
            if (Math.abs(built.train.speed) <= 0.02 || movingTicksSampled < 3 || poweredMovingTicks < 3
                    || physicallyMovingTicks < 3 || movingCarriagePathLength < 1.0
                    || carriage.position().z - departCarriagePosition.z < 1.0) return;

            TrainTraction traction = currentTraction();
            if (!traction.authoritative() || !traction.managed() || traction.tier() != TrainTier.MAGLEV
                    || !traction.powered() || traction.lift() < 0.1f) return;
            if (!(carriage instanceof LevitationData bodyLift)) {
                throw new IllegalStateException("Live native carriage does not expose synchronized maglev body lift");
            }
            float physicalLift = bodyLift.maglev$lift();
            maximumObservedLift = Math.max(maximumObservedLift, traction.lift());
            maximumObservedBodyLift = Math.max(maximumObservedBodyLift, physicalLift);
            if (physicalLift < 0.1f || Math.abs(physicalLift - traction.lift()) > 0.0001f) return;

            JsonObject event = new JsonObject();
            event.addProperty("type", "moving_ready");
            event.add("evidence", movingEvidence(local, traction, physicalLift));
            phase = "moving_walk";
            previousMovingLocal = local;
            previousWalkingLocal = local;
            lastWalkingLocal = local;
            send(event);
            LOGGER.info("Native passenger train reached powered physical motion; train={}, carriage={}, speed={}, bodyPosition={}, displacement={}",
                    trainId, carriageId, built.train.speed, carriage.position(),
                    horizontalDistance(carriage.position(), departCarriagePosition));
        }

        private void sampleNativeMotion(MinecraftServer server, boolean observeWalking) {
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            requireNativeTrainIdentity();
            requireIsolatedPowerSources();
            if (!departureStarted || built.train.runtime.getSchedule() == null
                    || Math.abs(built.train.throttle - SERVICE_THROTTLE) > 0.0001) {
                throw new IllegalStateException("Native Create schedule or the 0.1 service throttle disappeared during motion");
            }

            Vec3 carriagePosition = carriage.position();
            Vec3 carriageStep = previousMotionCarriagePosition == null
                    ? Vec3.ZERO : carriagePosition.subtract(previousMotionCarriagePosition);
            previousMotionCarriagePosition = carriagePosition;
            lastMotionDisplacement = carriageStep;
            double horizontalCarriageStep = Math.hypot(carriageStep.x, carriageStep.z);
            double speed = Math.abs(built.train.speed);
            if (speed > 0.001) {
                movingTicksSampled++;
                maximumObservedSpeed = Math.max(maximumObservedSpeed, speed);
                minimumObservedSpeed = Math.min(minimumObservedSpeed, speed);
                if (tractionPowered()) poweredMovingTicks++;
                if (horizontalCarriageStep > 0.001) {
                    physicallyMovingTicks++;
                    movingCarriagePathLength += horizontalCarriageStep;
                }
            }

            Vec3 local = localPosition();
            if (Math.abs(local.y - (NativePassengerTrainFixture.FLOOR_Y + 1.0)) > 0.35) {
                throw new IllegalStateException("Moving passenger feet left the actual carriage floor height: " + local);
            }
            JsonObject support = inspectNativeFloorSupport(local);
            updateStableSupport(support);
            if (observeWalking) sampleMovingWalkingPosition(local, horizontalCarriageStep);

            TrainTraction traction = currentTraction();
            if (speed > 0.001 && (!traction.authoritative() || !traction.powered())) {
                throw new IllegalStateException("Scheduled passenger train moved without authoritative powered maglev traction");
            }
            if (carriage instanceof LevitationData bodyLift) {
                maximumObservedLift = Math.max(maximumObservedLift, traction.lift());
                maximumObservedBodyLift = Math.max(maximumObservedBodyLift, bodyLift.maglev$lift());
                if (speed > 0.001 && Math.abs(bodyLift.maglev$lift() - traction.lift()) > 0.0001f) {
                    throw new IllegalStateException("Live body lift diverged from native powered traction while moving");
                }
            } else if (speed > 0.001) {
                throw new IllegalStateException("Live native carriage lost its synchronized maglev lift interface");
            }

            JsonObject sample = new JsonObject();
            sample.addProperty("serverTick", server.getTickCount());
            sample.addProperty("trainSpeed", built.train.speed);
            sample.addProperty("trainSpeedUnits", "blocks_per_tick");
            sample.addProperty("serviceThrottleFraction", SERVICE_THROTTLE);
            sample.addProperty("trainThrottle", built.train.throttle);
            sample.addProperty("ratedMaximumSpeed", built.train.maxSpeed());
            sample.addProperty("tractionAuthoritative", traction.authoritative());
            sample.addProperty("tractionPowered", traction.powered());
            sample.addProperty("tractionLift", traction.lift());
            sample.addProperty("tractionSpeedLimit", traction.speedLimit());
            sample.addProperty("carriageLift", carriage instanceof LevitationData body ? body.maglev$lift() : 0.0f);
            sample.add("carriageWorldPosition", vector(carriagePosition));
            sample.add("carriageWorldStep", vector(carriageStep));
            sample.addProperty("carriageHorizontalStep", horizontalCarriageStep);
            sample.addProperty("carriageWorldDisplacementFromDepart",
                    horizontalDistance(carriagePosition, departCarriagePosition));
            sample.addProperty("carriageWorldForwardDisplacementFromDepart",
                    carriagePosition.z - departCarriagePosition.z);
            sample.addProperty("cumulativeCarriagePathLength", movingCarriagePathLength);
            sample.add("playerWorldPosition", vector(player.position()));
            sample.add("playerCarriageLocalPosition", vector(local));
            sample.addProperty("playerOnGround", player.onGround());
            sample.addProperty("nativeFloorSupport", support.get("supported").getAsBoolean());
            sample.addProperty("stableNativeSupportTicks", stableSupportTicks);
            sample.add("sourceEnergy", sourceEnergyEvidence());
            nativeMotionSamples.add(sample);
        }

        private void sampleMovingWalkingPosition(Vec3 local, double horizontalCarriageStep) {
            if (previousMovingLocal == null) previousMovingLocal = local;
            double dx = local.x - previousMovingLocal.x;
            double dy = local.y - previousMovingLocal.y;
            double dz = local.z - previousMovingLocal.z;
            double horizontalStep = Math.hypot(dx, dz);
            if (horizontalStep > 1.25 || Math.abs(dy) > 1.0) {
                throw new IllegalStateException("Observed discontinuous local player movement during scheduled train motion: "
                        + dx + "," + dy + "," + dz);
            }
            if (horizontalStep > 0.02) {
                walkingMovementTicks++;
                movingWalkingMovementTicks++;
                observedPathLength += horizontalStep;
                movingWalkingPathLength += horizontalStep;
            }
            maximumSingleWalkingStep = Math.max(maximumSingleWalkingStep, horizontalStep);
            maximumMovingWalkingStep = Math.max(maximumMovingWalkingStep, horizontalStep);
            maximumPlayerDisplacement = Math.max(maximumPlayerDisplacement,
                    horizontalDistance(local, boardingBaselineLocal));
            maximumZ = Math.max(maximumZ, local.z);
            minimumX = Math.min(minimumX, local.x);
            if (horizontalCarriageStep > 0.001) {
                if (insidePositionLocal != null && rearPosition == null) {
                    insideToRearMovingLocalNetAisleProgressBlocks += dz;
                } else if (rearPosition != null && returnedPosition == null) {
                    rearToReturnedMovingLocalNetAisleProgressBlocks += dz;
                }
            }
            previousMovingLocal = local;
            previousWalkingLocal = local;
            lastWalkingLocal = local;
        }

        private void updateStableSupport(JsonObject support) {
            if (support.get("supported").getAsBoolean()) stableSupportTicks++;
            else stableSupportTicks = 0;
        }

        private TrainTraction currentTraction() {
            if (!(built.train instanceof TractionState state)) {
                throw new IllegalStateException("The native Create train does not expose the production maglev traction state");
            }
            return state.maglev$traction();
        }

        private boolean tractionPowered() {
            return built != null && built.train != null && built.train instanceof TractionState state
                    && state.maglev$traction().powered();
        }

        private JsonObject movingEvidence(Vec3 local, TrainTraction traction, float physicalLift) {
            JsonObject evidence = new JsonObject();
            evidence.addProperty("playerUUID", playerId.toString());
            evidence.addProperty("trainUUID", trainId.toString());
            evidence.addProperty("carriageUUID", carriageId.toString());
            evidence.addProperty("trainSpeed", built.train.speed);
            evidence.addProperty("trainSpeedUnits", "blocks_per_tick");
            evidence.addProperty("serviceThrottleFraction", SERVICE_THROTTLE);
            evidence.addProperty("trainThrottle", built.train.throttle);
            evidence.addProperty("ratedMaximumSpeed", built.train.maxSpeed());
            evidence.addProperty("tractionPowered", traction.powered());
            evidence.addProperty("tractionAuthoritative", traction.authoritative());
            evidence.addProperty("tractionManaged", traction.managed());
            evidence.addProperty("tractionTier", traction.tier().name());
            evidence.addProperty("tractionLift", traction.lift());
            evidence.addProperty("tractionSpeedLimit", traction.speedLimit());
            evidence.addProperty("carriageLift", physicalLift);
            evidence.addProperty("schedulePresent", built.train.runtime.getSchedule() != null);
            evidence.addProperty("destination", built.destinationName);
            evidence.addProperty("forwardConductor", built.train.hasForwardConductor());
            evidence.addProperty("backwardConductor", built.train.hasBackwardConductor());
            evidence.add("carriageWorldPosition", vector(carriage.position()));
            evidence.add("carriageWorldPositionAtDepart", vector(departCarriagePosition));
            evidence.addProperty("carriageWorldDisplacementFromBoarding",
                    horizontalDistance(carriage.position(), carriagePositionAtReady));
            evidence.addProperty("carriageWorldDisplacementFromDepart",
                    horizontalDistance(carriage.position(), departCarriagePosition));
            evidence.addProperty("carriageWorldForwardDisplacementFromDepart",
                    carriage.position().z - departCarriagePosition.z);
            evidence.addProperty("movingTicksSampled", movingTicksSampled);
            evidence.addProperty("poweredMovingTicks", poweredMovingTicks);
            evidence.addProperty("physicallyMovingTicks", physicallyMovingTicks);
            evidence.addProperty("cumulativeCarriagePathLength", movingCarriagePathLength);
            evidence.addProperty("energyAtDepart", totalDepartureEnergy());
            evidence.addProperty("energyAtMovingReady", totalCurrentEnergy());
            evidence.add("sources", sourceEnergyEvidence());
            evidence.add("playerCarriageLocalPosition", vector(local));
            evidence.addProperty("nativeMotionSampleCount", nativeMotionSamples.size());
            return evidence;
        }

        private JsonArray sourceEnergyEvidence() {
            JsonArray evidence = new JsonArray();
            for (int i = 0; i < sources.size(); i++) {
                NativeNewAgePowerFixture source = sources.get(i);
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.add("origin", blockPos(source.origin()));
                item.add("substation", blockPos(source.substationPos()));
                item.addProperty("initialEnergy", initialEnergy[i]);
                item.addProperty("energyAtDepart", departureEnergy[i]);
                item.addProperty("energyChargedWhileStaging",
                        Math.max(0, departureEnergy[i] - initialEnergy[i]));
                item.addProperty("energyNow", source.substation().energyStored());
                item.addProperty("wireConnectedBeforeDeparture", wireConnectedBeforeDeparture[i]);
                item.addProperty("wireConnectedNow", source.hasLiveCopperWire());
                item.addProperty("generatorStoppedAtDepart", generatorStoppedAtDepart[i]);
                item.addProperty("generatorSpeedNow", source.motor().generatedSpeed.value);
                item.addProperty("breakerOpenedAtDepart", breakerOpenedAtDepart[i]);
                item.addProperty("breakerBlockNow", blockId(level.getBlockState(source.substationPos().below())));
                item.addProperty("breakerBlocksSupplyNow", level.hasNeighborSignal(source.substationPos()));
                evidence.add(item);
            }
            return evidence;
        }

        private String sourceDiagnostic(int index) {
            if (index < 0 || index >= sources.size()) return "source index " + index + " is unavailable";
            NativeNewAgePowerFixture source = sources.get(index);
            return "origin=" + source.origin() + ", substation=" + source.substationPos()
                    + ", initialEnergy=" + initialEnergy[index]
                    + ", energyAtDepart=" + departureEnergy[index]
                    + ", energyNow=" + source.substation().energyStored()
                    + ", wireConnected=" + source.hasLiveCopperWire()
                    + ", generatorSpeed=" + source.motor().generatedSpeed.value
                    + ", breaker=" + blockId(level.getBlockState(source.substationPos().below()))
                    + ", breakerBlocksSupply=" + level.hasNeighborSignal(source.substationPos());
        }

        private long totalDepartureEnergy() {
            long total = 0;
            for (long energy : departureEnergy) total += energy;
            return total;
        }

        private long totalCurrentEnergy() {
            long total = 0;
            for (NativeNewAgePowerFixture source : sources) total += source.substation().energyStored();
            return total;
        }

        private String motionDiagnostic() {
            return "phase=" + phase + ", speed=" + (built == null || built.train == null ? "n/a" : built.train.speed)
                    + ", throttle=" + (built == null || built.train == null ? "n/a" : built.train.throttle)
                    + ", samples=" + nativeMotionSamples.size() + ", movingTicks=" + movingTicksSampled
                    + ", poweredTicks=" + poweredMovingTicks + ", physicalTicks=" + physicallyMovingTicks
                    + ", carriage=" + (carriage == null ? "n/a" : carriage.position())
                    + ", departPosition=" + departCarriagePosition + ", carriagePath=" + movingCarriagePathLength
                    + ", lift=" + (built == null || built.train == null || !(built.train instanceof TractionState state)
                            ? "n/a" : state.maglev$traction().lift())
                    + ", energy=" + sourceEnergyEvidence();
        }


        private void settleBoarding(MinecraftServer server) {
            requirePlayerWalkingMode();
            requireStationaryNativeTrain();
            requireStagedPowerSources(false);
            if (++boardTicks > BOARDING_SETTLE_TICKS) {
                throw new IllegalStateException("The final boarding stage did not settle on the exterior world platform");
            }
            double horizontalDrift = horizontalDistance(player.position(), boardingBaseline);
            double verticalDrift = Math.abs(player.getY() - boardingBaseline.y);
            if (horizontalDrift > 0.25 || verticalDrift > 0.15) {
                throw new IllegalStateException("The player moved before boarding_ready; the staged baseline was not stationary");
            }
            JsonObject footing = inspectWorldPlatformSupport(player.position());
            if (player.onGround() && footing.get("supported").getAsBoolean()) stableBoardTicks++;
            else stableBoardTicks = 0;
            if (stableBoardTicks < 2) return;

            boardingBaseline = player.position();
            boardingBaselineLocal = localPosition();
            previousWalkingLocal = boardingBaselineLocal;
            lastWalkingLocal = boardingBaselineLocal;
            maximumZ = boardingBaselineLocal.z;
            minimumX = boardingBaselineLocal.x;
            phase = "boarding_walk";
            JsonObject event = new JsonObject();
            event.addProperty("type", "boarding_ready");
            event.add("baseline", poseEvidence(boardingBaseline, boardingBaselineLocal));
            event.add("evidence", currentEvidence());
            send(event);
            LOGGER.info("Passenger walk baseline captured for player {} at {}", playerId, boardingBaselineLocal);
        }

        private void checkpoint(MinecraftServer server, String name) {
            if (!"boarding_walk".equals(phase) && !"moving_walk".equals(phase)) {
                throw new IllegalStateException("Checkpoint arrived outside physical walking observation: phase=" + phase);
            }
            if (pendingCheckpoint != null) {
                throw new IllegalStateException("Checkpoint " + name + " arrived while " + pendingCheckpoint + " is awaiting stable server support");
            }
            if (nextCheckpoint >= CHECKPOINT_ORDER.size() || !CHECKPOINT_ORDER.get(nextCheckpoint).equals(name)) {
                throw new IllegalStateException("Expected checkpoint "
                        + (nextCheckpoint >= CHECKPOINT_ORDER.size() ? "none" : CHECKPOINT_ORDER.get(nextCheckpoint))
                        + " but received " + name);
            }
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            if ("inside".equals(name)) {
                requireStationaryNativeTrain();
                requireStagedPowerSources(false);
            } else {
                requireMovingState();
            }
            validateCheckpointTarget(name, localPosition());
            pendingCheckpoint = name;
            pendingCheckpointTicks = 0;
            pendingSupportTicks = 0;
        }

        private void processPendingCheckpoint(MinecraftServer server) {
            String name = pendingCheckpoint;
            if (name == null) return;
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            if ("inside".equals(name)) requireStationaryNativeTrain();
            else requireMovingState();

            if (++pendingCheckpointTicks > CHECKPOINT_SUPPORT_WAIT_TICKS) {
                JsonObject lastSupport = inspectNativeFloorSupport(localPosition());
                throw new IllegalStateException("Checkpoint " + name + " never acquired a stable native server-supported pose "
                        + "within " + CHECKPOINT_SUPPORT_WAIT_TICKS + " ticks; playerOnGround="
                        + player.onGround() + ", pendingStableTicks=" + pendingSupportTicks + ", support=" + lastSupport);
            }

            Vec3 local = localPosition();
            validateCheckpointTarget(name, local);
            JsonObject footing = inspectNativeFloorSupport(local);
            if (!footing.get("supported").getAsBoolean()) {
                pendingSupportTicks = 0;
                return;
            }
            pendingSupportTicks++;
            stableSupportTicks = pendingSupportTicks;
            if (pendingSupportTicks < REQUIRED_STABLE_SUPPORT_TICKS) return;

            validateCheckpointPose(name, local, footing);
            if ("inside".equals(name)) {
                interiorDoorOpened = true;
                insidePosition = player.position();
                insidePositionLocal = local;
                insideCarriagePosition = carriage.position();
                insideToRearMovingLocalNetAisleProgressBlocks = 0;
                rearToReturnedMovingLocalNetAisleProgressBlocks = 0;
                phase = "awaiting_depart";
            } else if ("rear".equals(name)) {
                rearPosition = local;
                rearToReturnedMovingLocalNetAisleProgressBlocks = 0;
                rearCarriagePosition = carriage.position();
            } else if ("returned".equals(name)) {
                returnedPosition = local;
                returnedCarriagePosition = carriage.position();
            }

            JsonObject evidence = checkpointEvidence(name, local, footing);
            JsonObject checkpoint = new JsonObject();
            checkpoint.addProperty("name", name);
            checkpoint.addProperty("passed", true);
            checkpoint.add("evidence", evidence.deepCopy());
            checkpoints.add(checkpoint);
            nextCheckpoint++;
            pendingCheckpoint = null;
            pendingCheckpointTicks = 0;
            pendingSupportTicks = 0;

            JsonObject event = new JsonObject();
            event.addProperty("type", "checkpoint");
            event.addProperty("name", name);
            event.addProperty("passed", true);
            event.add("evidence", evidence);
            send(event);
            LOGGER.info("Native passenger checkpoint {} passed after {} stable supported ticks: {}", name,
                    REQUIRED_STABLE_SUPPORT_TICKS, evidence);
        }

        private void validateCheckpointTarget(String name, Vec3 local) {
            if (local.x < 0.1 || local.x > 0.9) {
                throw new IllegalStateException("Player is not centered in the physical carriage aisle at checkpoint "
                        + name + ": localX=" + local.x + " (expected 0.1..0.9)");
            }
            switch (name) {
                case "inside" -> {
                    if (local.z < 2.7 || local.z > 4.8) {
                        throw new IllegalStateException("Inside checkpoint pose is outside the doorway target: " + local);
                    }
                    if (boardingBaselineLocal == null || local.x - boardingBaselineLocal.x < 1.5
                            || Math.abs(local.z - boardingBaselineLocal.z) > 1.25) {
                        throw new IllegalStateException("Inside checkpoint did not show physical eastward boarding progress: " + local);
                    }
                }
                case "rear" -> {
                    if (local.z < 11.0 || local.z > 14.5) {
                        throw new IllegalStateException("Rear checkpoint pose did not reach the aisle rear: " + local);
                    }
                }
                case "returned" -> {
                    if (local.z < 2.5 || local.z > 5.0) {
                        throw new IllegalStateException("Returned checkpoint pose did not reverse down the aisle: " + local);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown passenger checkpoint: " + name);
            }
        }

        private void validateCheckpointPose(String name, Vec3 local, JsonObject footing) {
            if (!footing.get("supported").getAsBoolean()) {
                throw new IllegalStateException("Player lacked stable real native-floor support at checkpoint " + name
                        + ": " + footing);
            }
            if (Math.abs(local.y - (NativePassengerTrainFixture.FLOOR_Y + 1.0)) > 0.12) {
                throw new IllegalStateException("Player feet were not stably at native carriage floor height at checkpoint "
                        + name + ": " + local);
            }
            if (!"inside".equals(name)) {
                requireMovingState();
                Vec3 referencePosition = "rear".equals(name) ? insideCarriagePosition : rearCarriagePosition;
                double carriageTravel = referencePosition == null ? 0 : horizontalDistance(carriage.position(), referencePosition);
                double forwardTravel = referencePosition == null ? 0 : carriage.position().z - referencePosition.z;
                if (carriageTravel < MIN_CHECKPOINT_CARRIAGE_TRAVEL
                        || forwardTravel < MIN_CHECKPOINT_CARRIAGE_TRAVEL) {
                    throw new IllegalStateException("Checkpoint " + name + " lacks physical forward live-carriage translation "
                            + "since the preceding walking checkpoint: horizontal=" + carriageTravel + ", worldZ=" + forwardTravel);
                }
                if (Math.abs(built.train.speed) <= 0.001
                        || Math.hypot(lastMotionDisplacement.x, lastMotionDisplacement.z) <= 0.001) {
                    throw new IllegalStateException("Checkpoint " + name + " was not sampled during nonzero physically translated train motion");
                }
            }
            switch (name) {
                case "inside" -> {
                    BlockState door = blockState(carriage, NativePassengerTrainFixture.DOOR_LOWER_POS);
                    if (door == null || !(door.getBlock() instanceof DoorBlock) || !door.getValue(DoorBlock.OPEN)) {
                        throw new IllegalStateException("The native carriage door was not opened by the on-foot interaction");
                    }
                }
                case "rear" -> {
                    if (insidePositionLocal == null || local.z - insidePositionLocal.z < 6.0
                            || insideToRearMovingLocalNetAisleProgressBlocks < 6.0) {
                        throw new IllegalStateException("Rear checkpoint lacks at least 6 blocks of net forward aisle progress "
                                + "during physically translated carriage ticks: inside=" + insidePositionLocal + ", rear=" + local
                                + ", insideToRearMovingLocalNetAisleProgressBlocks="
                                + insideToRearMovingLocalNetAisleProgressBlocks);
                    }
                }
                case "returned" -> {
                    if (rearPosition == null || rearPosition.z - local.z < 6.0
                            || rearToReturnedMovingLocalNetAisleProgressBlocks > -6.0) {
                        throw new IllegalStateException("Returned checkpoint lacks at least 6 blocks of net backward aisle progress "
                                + "during physically translated carriage ticks: rear=" + rearPosition + ", returned=" + local
                                + ", rearToReturnedMovingLocalNetAisleProgressBlocks="
                                + rearToReturnedMovingLocalNetAisleProgressBlocks);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown passenger checkpoint: " + name);
            }
        }

        private void finish(MinecraftServer server) throws IOException {
            if (!"moving_walk".equals(phase)) throw new IllegalStateException("Finish request arrived during phase " + phase);
            if (pendingCheckpoint != null) throw new IllegalStateException("Finish arrived while a checkpoint is still awaiting stable support");
            if (nextCheckpoint != CHECKPOINT_ORDER.size()) {
                throw new IllegalStateException("Walkthrough finish requires exactly inside, rear, and returned checkpoints in order");
            }
            requireOwnedPlayer(server, player);
            requirePlayerWalkingMode();
            requireMovingState();
            Vec3 finalLocal = localPosition();
            validateCheckpointTarget("returned", finalLocal);
            JsonObject floorSupport = inspectNativeFloorSupport(finalLocal);
            if (!floorSupport.get("supported").getAsBoolean()
                    || stableSupportTicks < REQUIRED_STABLE_SUPPORT_TICKS
                    || Math.abs(finalLocal.y - (NativePassengerTrainFixture.FLOOR_Y + 1.0)) > 0.12
                    || returnedPosition == null
                    || Math.abs(finalLocal.y - returnedPosition.y) > 0.12) {
                throw new IllegalStateException("Final player state is not the stable, supported moving-carriage return pose: "
                        + finalLocal + ", support=" + floorSupport + ", stableTicks=" + stableSupportTicks);
            }
            if (walkingMovementTicks < 8 || observedPathLength < 8.0
                    || movingWalkingMovementTicks < 8 || movingWalkingPathLength < 10.0
                    || insideToRearMovingLocalNetAisleProgressBlocks < 6.0
                    || rearToReturnedMovingLocalNetAisleProgressBlocks > -6.0
                    || maximumZ - boardingBaselineLocal.z < 7.0 || !interiorDoorOpened) {
                throw new IllegalStateException("Native on-foot evidence does not prove a complete positive and negative aisle traversal "
                        + "during physically moving ticks; " + motionDiagnostic());
            }
            if (returnedCarriagePosition == null
                    || horizontalDistance(carriage.position(), returnedCarriagePosition) <= 0.001
                    || Math.hypot(lastMotionDisplacement.x, lastMotionDisplacement.z) <= 0.001) {
                throw new IllegalStateException("The live carriage did not physically translate after the returned checkpoint");
            }
            if (totalDepartureEnergy() <= totalCurrentEnergy()) {
                throw new IllegalStateException("Native traction consumed no measured finite NewAge energy during the moving walkthrough");
            }
            JsonObject finalGeometry = inspectAssembly(false);
            if (finalGeometry.get("bodyBlockCount").getAsInt() != geometry.get("bodyBlockCount").getAsInt()) {
                throw new IllegalStateException("The assembled carriage body changed block count during the walkthrough");
            }
            JsonObject evidence = currentEvidence();
            evidence.add("finalGeometry", finalGeometry);
            evidence.add("finalPose", poseEvidence(player.position(), finalLocal));
            evidence.add("finalNativeFloorSupport", floorSupport);
            evidence.addProperty("allThreeCheckpointsPassed", true);
            evidence.addProperty("nativeAisleDisplacementObserved", maximumZ - boardingBaselineLocal.z);
            evidence.addProperty("physicalWalkingMovementTicks", walkingMovementTicks);
            evidence.addProperty("movingPhysicalWalkingMovementTicks", movingWalkingMovementTicks);
            evidence.addProperty("observedWalkingPathLength", observedPathLength);
            evidence.addProperty("movingWalkingPathLength", movingWalkingPathLength);
            evidence.addProperty("worldFloorRemovedAcrossInterior", true);
            evidence.addProperty("completeAssembledEnclosure", true);
            evidence.addProperty("sameLiveTrainAndCarriage", true);

            JsonObject report = new JsonObject();
            report.addProperty("complete", true);
            report.addProperty("passed", true);
            report.add("evidence", evidence.deepCopy());
            report.add("checkpoints", checkpoints.deepCopy());
            report.add("nativeMotionSamples", nativeMotionSamples.deepCopy());
            writeReport(report);

            JsonObject event = new JsonObject();
            event.addProperty("type", "complete");
            event.addProperty("passed", true);
            event.add("evidence", evidence);
            event.add("checkpoints", checkpoints.deepCopy());
            send(event);
            phase = "complete";
            shutdownTicks = SHUTDOWN_DELAY_TICKS;
            LOGGER.info("Native moving passenger walkthrough completed; retained train={}, carriage={}, report={}",
                    trainId, carriageId, resultsFile);
        }

        private JsonObject inspectAssembly(boolean requireClosedDoor) {
            requireNativeTrainIdentity();
            if (carriage == null || !carriage.isAlive() || level.getEntity(carriageId) != carriage
                    || built.liveCarriage() != carriage || carriage.getContraption() == null) {
                throw new IllegalStateException("The train UUID no longer resolves to the same live native carriage");
            }
            if (built.train == null || !built.train.id.equals(trainId)
                    || !carriage.trainId.equals(trainId) || carriage.getCarriage() == null
                    || carriage.getCarriage().train != built.train) {
                throw new IllegalStateException("The assembled native train/carriage identity changed");
            }

            var blocks = carriage.getContraption().getBlocks();
            int bodyBlockCount = 0;
            int outsideEnvelopeBlockCount = 0;
            int windowBlockCount = 0;
            int bogeyBlockCount = 0;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int expectedMinX = -(NativePassengerTrainFixture.WIDTH / 2);
            int expectedMaxX = expectedMinX + NativePassengerTrainFixture.WIDTH - 1;
            int expectedMinZ = -3;
            int expectedMaxZ = expectedMinZ + NativePassengerTrainFixture.LENGTH - 1;
            int floorY = NativePassengerTrainFixture.FLOOR_Y;
            int roofY = NativePassengerTrainFixture.HEIGHT - 1;

            for (var entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue().state();
                boolean inEnvelope = pos.getX() >= expectedMinX && pos.getX() <= expectedMaxX
                        && pos.getY() >= floorY && pos.getY() <= roofY
                        && pos.getZ() >= expectedMinZ && pos.getZ() <= expectedMaxZ;
                if (!inEnvelope) {
                    outsideEnvelopeBlockCount++;
                    if (state.getBlock() instanceof com.simibubi.create.content.trains.bogey.AbstractBogeyBlock<?>) {
                        bogeyBlockCount++;
                    }
                    continue;
                }
                bodyBlockCount++;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
                String blockPath = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                if (blockPath.contains("glass")) windowBlockCount++;
            }

            if (bodyBlockCount == 0 || minX != expectedMinX || maxX != expectedMaxX
                    || minY != floorY || maxY != roofY || minZ != expectedMinZ || maxZ != expectedMaxZ) {
                throw new IllegalStateException("Native carriage body bounds do not match the fixed passenger geometry: "
                        + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
            }

            int floorBlockCount = 0;
            for (int x = expectedMinX; x <= expectedMaxX; x++) {
                for (int z = expectedMinZ; z <= expectedMaxZ; z++) {
                    BlockPos floorPos = new BlockPos(x, floorY, z);
                    BlockState floor = blockState(carriage, floorPos);
                    if (floor == null || floor.isAir()) {
                        throw new IllegalStateException("Native passenger floor is missing at " + floorPos);
                    }
                    BlockPos worldFloor = worldBlock(carriage, floorPos);
                    if (!floor.isFaceSturdy(level, worldFloor, Direction.UP)) {
                        throw new IllegalStateException("Native passenger floor is not physically supportive at " + floorPos);
                    }
                    if (!level.getBlockState(worldFloor).isAir()) {
                        throw new IllegalStateException("A world block remains under the assembled native floor at " + worldFloor);
                    }
                    floorBlockCount++;
                }
            }

            int shellBlockCount = 0;
            for (int y = floorY + 1; y < roofY; y++) {
                for (int z = expectedMinZ; z <= expectedMaxZ; z++) {
                    for (int x = expectedMinX; x <= expectedMaxX; x += NativePassengerTrainFixture.WIDTH - 1) {
                        BlockPos shell = new BlockPos(x, y, z);
                        if (isDoorCell(shell)) continue;
                        requireBodyBlock(shell, "side wall");
                        shellBlockCount++;
                    }
                }
                for (int x = expectedMinX + 1; x < expectedMaxX; x++) {
                    for (int z = expectedMinZ; z <= expectedMaxZ; z += NativePassengerTrainFixture.LENGTH - 1) {
                        BlockPos shell = new BlockPos(x, y, z);
                        if (isDoorCell(shell)) continue;
                        requireBodyBlock(shell, "end wall");
                        shellBlockCount++;
                    }
                }
            }

            int roofBlockCount = 0;
            for (int x = expectedMinX; x <= expectedMaxX; x++) {
                for (int z = expectedMinZ; z <= expectedMaxZ; z++) {
                    requireBodyBlock(new BlockPos(x, roofY, z), "roof");
                    roofBlockCount++;
                }
            }
            for (int z = 3; z <= 13; z++) {
                for (int y = floorY + 1; y < roofY; y++) {
                    if (blockState(carriage, new BlockPos(0, y, z)) != null) {
                        throw new IllegalStateException("The central passenger aisle is obstructed at x=0,y=" + y + ",z=" + z);
                    }
                }
            }

            BlockPos doorLowerPos = NativePassengerTrainFixture.DOOR_LOWER_POS;
            BlockPos doorUpperPos = doorLowerPos.above();
            BlockState doorLower = blockState(carriage, doorLowerPos);
            BlockState doorUpper = blockState(carriage, doorUpperPos);
            if (doorLower == null || doorUpper == null || !(doorLower.getBlock() instanceof DoorBlock)
                    || doorUpper.getBlock() != doorLower.getBlock()
                    || doorLower.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                    || doorUpper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER) {
                throw new IllegalStateException("The native carriage does not contain the contracted two-block operable doorway");
            }
            if (requireClosedDoor && doorLower.getValue(DoorBlock.OPEN)) {
                throw new IllegalStateException("The passenger doorway was not closed in the initial exterior staging");
            }
            if (windowBlockCount < 6) {
                throw new IllegalStateException("The native enclosed body is missing real window blocks: found " + windowBlockCount);
            }

            JsonObject result = new JsonObject();
            result.addProperty("width", NativePassengerTrainFixture.WIDTH);
            result.addProperty("length", NativePassengerTrainFixture.LENGTH);
            result.addProperty("height", NativePassengerTrainFixture.HEIGHT);
            result.addProperty("floorY", floorY);
            result.addProperty("roofY", roofY);
            result.addProperty("minX", minX);
            result.addProperty("maxX", maxX);
            result.addProperty("minY", minY);
            result.addProperty("maxY", maxY);
            result.addProperty("minZ", minZ);
            result.addProperty("maxZ", maxZ);
            result.addProperty("bodyBlockCount", bodyBlockCount);
            result.addProperty("contraptionBlockCount", blocks.size());
            result.addProperty("outsideEnvelopeBlockCount", outsideEnvelopeBlockCount);
            result.addProperty("outsideEnvelopeBogeyBlockCount", bogeyBlockCount);
            result.addProperty("floorBlockCount", floorBlockCount);
            result.addProperty("roofBlockCount", roofBlockCount);
            result.addProperty("shellBlockCount", shellBlockCount);
            result.addProperty("windowBlockCount", windowBlockCount);
            result.addProperty("doorLower", blockId(doorLower));
            result.addProperty("doorInitiallyClosed", !doorLower.getValue(DoorBlock.OPEN));
            result.addProperty("doorOperableTwoBlockState", true);
            result.addProperty("interiorWorldFloorRemoved", true);
            result.addProperty("centralAisleClear", true);
            result.addProperty("trainUUID", trainId.toString());
            result.addProperty("carriageUUID", carriageId.toString());
            result.addProperty("carriageEntityAlive", carriage.isAlive());
            result.addProperty("carriageEntityResolvesByUUID", level.getEntity(carriageId) == carriage);
            result.addProperty("trainSpeedAtInspection", built.train.speed);
            result.addProperty("schedulePresent", built.train.runtime.getSchedule() != null);
            result.addProperty("unpoweredAndUnscheduledAtInspection", built.train.runtime.getSchedule() == null
                    && Math.abs(built.train.speed) <= 0.001);
            result.addProperty("stationaryAtInspection", Math.abs(built.train.speed) <= 0.001
                    && built.train.runtime.getSchedule() == null);
            result.addProperty("doorOpenAtInspection", doorLower.getValue(DoorBlock.OPEN));
            return result;
        }

        private JsonObject inspectNativeFloorSupport(Vec3 local) {
            if (local.x < -1.5 || local.x > 2.5 || local.z < -3.0 || local.z > 15.0) {
                throw new IllegalStateException("Player is outside the native body support envelope: " + local);
            }
            BlockPos localFloor = new BlockPos(floor(local.x), NativePassengerTrainFixture.FLOOR_Y, floor(local.z));
            BlockState floor = blockState(carriage, localFloor);
            if (floor == null || floor.isAir()) {
                throw new IllegalStateException("No captured native contraption floor exists beneath the player at " + localFloor);
            }
            BlockPos worldFloor = worldBlock(carriage, localFloor);
            BlockState worldState = level.getBlockState(worldFloor);
            boolean worldFloorRemoved = worldState.isAir();
            boolean sturdyNativeFloor = floor.isFaceSturdy(level, worldFloor, Direction.UP);
            var floorShape = floor.getCollisionShape(level, worldFloor);
            boolean hasNativeCollision = !floorShape.isEmpty();
            if (!hasNativeCollision) throw new IllegalStateException("Native captured floor has no collision at " + localFloor);
            double contactPlaneY = localFloor.getY() + floorShape.max(Direction.Axis.Y);
            boolean floorHeightMatches = hasNativeCollision && Math.abs(local.y - contactPlaneY) <= 0.025;
            // Create collides players on the client and sends ClientMotionPacket. Vanilla's server
            // ground flag can reset against the empty world cell; retain it as evidence, not a floor test.
            boolean supported = worldFloorRemoved && sturdyNativeFloor && hasNativeCollision && floorHeightMatches;
            JsonObject evidence = new JsonObject();
            evidence.addProperty("kind", "native_contraption_floor");
            evidence.add("localBlock", blockPos(localFloor));
            evidence.add("worldBlock", blockPos(worldFloor));
            evidence.addProperty("contraptionBlock", blockId(floor));
            evidence.addProperty("worldBlockIsAir", worldFloorRemoved);
            evidence.addProperty("contraptionFloorFaceSturdy", sturdyNativeFloor);
            evidence.addProperty("nativeFloorHasCollisionShape", hasNativeCollision);
            evidence.addProperty("playerFeetAtFloorHeight", floorHeightMatches);
            evidence.addProperty("nativeCollisionTopY", contactPlaneY);
            evidence.addProperty("feetToNativeCollisionTop", local.y - contactPlaneY);
            evidence.addProperty("playerOnGround", player.onGround());
            evidence.addProperty("supported", supported);
            return evidence;
        }

        private JsonObject inspectWorldPlatformSupport(Vec3 feet) {
            BlockPos floorPos = BlockPos.containing(feet.x, feet.y, feet.z).below();
            BlockState state = level.getBlockState(floorPos);
            Vec3 local = localPosition();
            BlockPos localFloor = new BlockPos(floor(local.x), NativePassengerTrainFixture.FLOOR_Y, floor(local.z));
            BlockPos expectedWorldFloor = worldBlock(carriage, localFloor);
            boolean withinPlatform = localFloor.getX() >= -13 && localFloor.getX() <= -3
                    && localFloor.getZ() >= -8 && localFloor.getZ() <= 18;
            boolean expectedBlockMatches = floorPos.equals(expectedWorldFloor);
            boolean platformMaterial = state.is(Blocks.POLISHED_ANDESITE) || state.is(Blocks.CYAN_CONCRETE)
                    || state.is(Blocks.YELLOW_CONCRETE) || state.is(Blocks.LIGHT_GRAY_CONCRETE);
            boolean sturdy = state.isFaceSturdy(level, floorPos, Direction.UP);
            boolean supported = sturdy && platformMaterial && withinPlatform && expectedBlockMatches && player.onGround();
            JsonObject evidence = new JsonObject();
            evidence.addProperty("kind", "world_platform");
            evidence.add("localBlock", blockPos(localFloor));
            evidence.add("worldBlock", blockPos(floorPos));
            evidence.add("expectedWorldBlock", blockPos(expectedWorldFloor));
            evidence.addProperty("worldBlockState", blockId(state));
            evidence.addProperty("worldBlockIsAir", state.isAir());
            evidence.addProperty("platformMaterial", platformMaterial);
            evidence.addProperty("withinContractPlatformBounds", withinPlatform);
            evidence.addProperty("expectedBlockMatchesPlayerFloor", expectedBlockMatches);
            evidence.addProperty("playerOnGround", player.onGround());
            evidence.addProperty("supported", supported);
            evidence.addProperty("platformFaceSturdy", sturdy);
            return evidence;
        }

        private JsonObject checkpointEvidence(String name, Vec3 local, JsonObject support) {
            JsonObject evidence = new JsonObject();
            TrainTraction traction = currentTraction();
            boolean moving = !"inside".equals(name);
            Vec3 previousCheckpointCarriage = "rear".equals(name) ? insideCarriagePosition
                    : "returned".equals(name) ? rearCarriagePosition : carriage.position();
            evidence.addProperty("playerUUID", playerId.toString());
            evidence.addProperty("trainUUID", trainId.toString());
            evidence.addProperty("carriageUUID", carriageId.toString());
            evidence.addProperty("trainIdentityMatches", Create.RAILWAYS.trains.get(trainId) == built.train
                    && built.train.id.equals(trainId));
            evidence.addProperty("carriageIdentityMatches", level.getEntity(carriageId) == carriage
                    && built.liveCarriage() == carriage && carriage.trainId.equals(trainId));
            evidence.addProperty("playerAliveAndOwned", level.getEntity(playerId) == player
                    && level.getServer().getPlayerList().getPlayer(playerId) == player && player.isAlive());
            evidence.addProperty("playerCreative", player.isCreative());
            evidence.addProperty("playerSpectator", player.isSpectator());
            evidence.addProperty("playerMayFly", player.getAbilities().mayfly);
            evidence.addProperty("playerFlying", player.getAbilities().flying);
            evidence.addProperty("playerMounted", player.isPassenger() || player.getVehicle() != null);
            evidence.addProperty("playerOnGround", player.onGround());
            evidence.addProperty("stableNativeSupportTicks", stableSupportTicks);
            evidence.add("worldPosition", vector(player.position()));
            evidence.add("carriageLocalPosition", vector(local));
            evidence.add("carriageWorldPosition", vector(carriage.position()));
            evidence.addProperty("carriageWorldDisplacementSincePreviousCheckpoint",
                    horizontalDistance(carriage.position(), previousCheckpointCarriage));
            evidence.addProperty("carriageWorldForwardDisplacementSincePreviousCheckpoint",
                    carriage.position().z - previousCheckpointCarriage.z);
            evidence.add("support", support);
            evidence.addProperty("walkingMovementTicksObserved", walkingMovementTicks);
            evidence.addProperty("movingWalkingMovementTicksObserved", movingWalkingMovementTicks);
            evidence.addProperty("observedPathLength", observedPathLength);
            evidence.addProperty("movingWalkingPathLength", movingWalkingPathLength);
            evidence.addProperty("insideToRearMovingLocalNetAisleProgressBlocks",
                    insideToRearMovingLocalNetAisleProgressBlocks);
            evidence.addProperty("rearToReturnedMovingLocalNetAisleProgressBlocks",
                    rearToReturnedMovingLocalNetAisleProgressBlocks);
            evidence.addProperty("maximumAisleZ", maximumZ);
            evidence.addProperty("moving", moving);
            evidence.addProperty("trainSpeed", built.train.speed);
            evidence.addProperty("trainThrottle", built.train.throttle);
            evidence.addProperty("schedulePresent", built.train.runtime.getSchedule() != null);
            evidence.addProperty("tractionPowered", traction.powered());
            evidence.addProperty("tractionLift", traction.lift());
            evidence.addProperty("carriageLift", carriage instanceof LevitationData body ? body.maglev$lift() : 0.0f);
            evidence.addProperty("movingTicksSampled", movingTicksSampled);
            evidence.addProperty("nativeDoorOpenAtInterior", interiorDoorOpened || "inside".equals(name));
            evidence.add("sourceEnergy", sourceEnergyEvidence());
            return evidence;
        }

        private void sampleWalkingPosition() {
            Vec3 local = localPosition();
            double dx = local.x - previousWalkingLocal.x;
            double dy = local.y - previousWalkingLocal.y;
            double dz = local.z - previousWalkingLocal.z;
            double horizontalStep = Math.hypot(dx, dz);
            if (horizontalStep > 1.25 || Math.abs(dy) > 1.0) {
                throw new IllegalStateException("Observed discontinuous player movement after boarding_ready: " + dx + "," + dy + "," + dz);
            }
            if (horizontalStep > 0.02) walkingMovementTicks++;
            observedPathLength += horizontalStep;
            maximumSingleWalkingStep = Math.max(maximumSingleWalkingStep, horizontalStep);
            maximumPlayerDisplacement = Math.max(maximumPlayerDisplacement, horizontalDistance(local, boardingBaselineLocal));
            maximumZ = Math.max(maximumZ, local.z);
            minimumX = Math.min(minimumX, local.x);
            previousWalkingLocal = local;
            lastWalkingLocal = local;
        }

        private void requirePlayerWalkingMode() {
            if (player == null || !player.isAlive() || player.isSpectator() || !player.isCreative()
                    || player.getAbilities().mayfly || player.getAbilities().flying
                    || player.isPassenger() || player.getVehicle() != null) {
                throw new IllegalStateException("The owned player is not alive, unmounted, non-spectator creative walking with flight disabled");
            }
        }

        private void requireNativeTrainIdentity() {
            if (built == null || built.train == null || carriage == null || trainId == null || carriageId == null) {
                throw new IllegalStateException("The native passenger train has not reached its assembled identity state");
            }
            if (!trainId.equals(built.train.id) || Create.RAILWAYS.trains.get(trainId) != built.train
                    || built.train.graph == null
                    || Create.RAILWAYS.trackNetworks.get(built.train.graph.id) != built.train.graph) {
                throw new IllegalStateException("The owned Create train UUID or native track graph no longer resolves to the same live registry objects");
            }
            if (!trainId.equals(carriage.trainId) || carriage.getCarriage() == null
                    || carriage.getCarriage().train != built.train
                    || !carriageId.equals(carriage.getUUID()) || !carriage.isAlive()
                    || level.getEntity(carriageId) != carriage || built.liveCarriage() != carriage) {
                throw new IllegalStateException("The live carriage UUID no longer resolves to the same native entity and train ownership");
            }
        }

        private void requireStationaryNativeTrain() {
            requireNativeTrainIdentity();
            if (Math.abs(built.train.speed) > 0.001 || built.train.runtime.getSchedule() != null) {
                throw new IllegalStateException("The passenger train must remain stationary and unscheduled before depart");
            }
            if (carriagePositionAtReady != null
                    && horizontalDistance(carriage.position(), carriagePositionAtReady) > 0.1) {
                throw new IllegalStateException("The native carriage translated while the player was in stationary staging");
            }
            TrainTraction traction = currentTraction();
            if (traction.authoritative() && (traction.powered() || traction.lift() > 0.001f
                    || traction.speedLimit() > 0.001)) {
                throw new IllegalStateException("Breaker-isolated passenger train unexpectedly powered or lifted before depart: powered="
                        + traction.powered() + ", lift=" + traction.lift() + ", speedLimit=" + traction.speedLimit());
            }
            if (carriage instanceof LevitationData bodyLift && bodyLift.maglev$lift() > 0.001f) {
                throw new IllegalStateException("Unpowered passenger carriage body retained physical maglev lift before depart: "
                        + bodyLift.maglev$lift());
            }
        }

        private void requireMovingState() {
            requireNativeTrainIdentity();
            requireIsolatedPowerSources();
            if (!departureStarted || built.train.runtime.getSchedule() == null
                    || Math.abs(built.train.speed) <= 0.02
                    || Math.abs(built.train.throttle - SERVICE_THROTTLE) > 0.0001) {
                throw new IllegalStateException("Passenger aisle checkpoint requires the active native schedule, 0.1 service throttle, and nonzero train speed");
            }
            double horizontalStep = Math.hypot(lastMotionDisplacement.x, lastMotionDisplacement.z);
            if (horizontalStep <= 0.001 || movingTicksSampled == 0) {
                throw new IllegalStateException("Passenger aisle checkpoint lacks actual UUID-resolved carriage translation on its sampled tick");
            }
            TrainTraction traction = currentTraction();
            if (!traction.authoritative() || !traction.managed() || traction.tier() != TrainTier.MAGLEV
                    || !traction.powered() || traction.lift() < 0.1f
                    || !(carriage instanceof LevitationData bodyLift)
                    || bodyLift.maglev$lift() < 0.1f
                    || Math.abs(bodyLift.maglev$lift() - traction.lift()) > 0.0001f) {
                throw new IllegalStateException("Moving passenger train lacks synchronized authoritative powered maglev traction/lift: "
                        + "authoritative=" + traction.authoritative() + ", managed=" + traction.managed()
                        + ", tier=" + traction.tier() + ", powered=" + traction.powered()
                        + ", trainLift=" + traction.lift() + ", bodyLift="
                        + (carriage instanceof LevitationData data ? data.maglev$lift() : -1.0f));
            }
        }

        private void requireOwnedPlayer(MinecraftServer server, ServerPlayer sender) {
            if (player == null || playerId == null) {
                throw new IllegalStateException("Walkthrough control arrived before the owned client's hello");
            }
            if (sender != player || !playerId.equals(sender.getUUID())) {
                throw new IllegalStateException("Only the single owned walkthrough player may control this scenario");
            }
            if (server.getPlayerList().getPlayers().size() != 1 || server.getPlayerList().getPlayer(playerId) != player
                    || level == null || player.serverLevel() != level || level.getEntity(playerId) != player || !player.isAlive()) {
                throw new IllegalStateException("The owned walkthrough player disconnected, changed worlds, or lost exclusive ownership");
            }
        }

        private Vec3 localPosition() {
            if (carriage == null || carriage.getContraption() == null) {
                throw new IllegalStateException("Cannot transform player pose without the live native carriage");
            }
            return carriage.toLocalVector(player.position(), 1.0f);
        }

        private Vec3 worldPosition(Vec3 offset) {
            BlockPos leading = built.leadingBogeyPos;
            return new Vec3(leading.getX() + offset.x, leading.getY() + offset.y, leading.getZ() + offset.z);
        }

        private static BlockPos worldBlock(CarriageContraptionEntity body, BlockPos local) {
            Vec3 world = body.toGlobalVector(Vec3.atCenterOf(local), 1.0f);
            return BlockPos.containing(world.x, world.y, world.z);
        }

        private static BlockState blockState(CarriageContraptionEntity body, BlockPos local) {
            if (body == null || body.getContraption() == null) return null;
            var info = body.getContraption().getBlocks().get(local);
            return info == null ? null : info.state();
        }

        private void requireBodyBlock(BlockPos local, String part) {
            BlockState state = blockState(carriage, local);
            if (state == null || state.isAir()) {
                throw new IllegalStateException("Native passenger " + part + " is missing at " + local);
            }
        }

        private static boolean isDoorCell(BlockPos pos) {
            BlockPos lower = NativePassengerTrainFixture.DOOR_LOWER_POS;
            return pos.equals(lower) || pos.equals(lower.above());
        }

        private JsonObject poseEvidence(Vec3 world, Vec3 local) {
            JsonObject pose = new JsonObject();
            pose.add("world", vector(world));
            pose.add("carriageLocal", vector(local));
            pose.addProperty("playerUUID", playerId.toString());
            pose.addProperty("creative", player.isCreative());
            pose.addProperty("spectator", player.isSpectator());
            pose.addProperty("mayFly", player.getAbilities().mayfly);
            pose.addProperty("flying", player.getAbilities().flying);
            pose.addProperty("mounted", player.isPassenger() || player.getVehicle() != null);
            pose.addProperty("onGround", player.onGround());
            return pose;
        }

        private JsonObject currentEvidence() {
            JsonObject evidence = new JsonObject();
            evidence.addProperty("phase", phase);
            evidence.addProperty("playerUUID", playerId == null ? "" : playerId.toString());
            evidence.addProperty("trainUUID", trainId == null ? "" : trainId.toString());
            evidence.addProperty("carriageUUID", carriageId == null ? "" : carriageId.toString());
            evidence.addProperty("oneOwnedPlayer", player != null && playerId != null && level != null
                    && level.getServer().getPlayerList().getPlayers().size() == 1
                    && level.getServer().getPlayerList().getPlayer(playerId) == player);
            evidence.addProperty("playerAlive", player != null && player.isAlive());
            evidence.addProperty("playerCreative", player != null && player.isCreative());
            evidence.addProperty("playerSpectator", player != null && player.isSpectator());
            evidence.addProperty("playerMayFly", player != null && player.getAbilities().mayfly);
            evidence.addProperty("playerFlying", player != null && player.getAbilities().flying);
            evidence.addProperty("playerMounted", player != null && (player.isPassenger() || player.getVehicle() != null));
            evidence.addProperty("playerOnGround", player != null && player.onGround());
            evidence.addProperty("initialExteriorStaged", initialExteriorStaged);
            evidence.addProperty("boardingTeleportPerformed", boardingTeleportPerformed);
            evidence.addProperty("departureStarted", departureStarted);
            evidence.addProperty("walkingMovementTicksObserved", walkingMovementTicks);
            evidence.addProperty("movingWalkingMovementTicksObserved", movingWalkingMovementTicks);
            evidence.addProperty("observedPathLength", observedPathLength);
            evidence.addProperty("movingWalkingPathLength", movingWalkingPathLength);
            evidence.addProperty("maximumSingleWalkingStep", maximumSingleWalkingStep);
            evidence.addProperty("maximumMovingWalkingStep", maximumMovingWalkingStep);
            evidence.addProperty("maximumPlayerDisplacement", maximumPlayerDisplacement);
            evidence.addProperty("maximumAisleZ", maximumZ);
            evidence.addProperty("minimumLocalX", minimumX);
            evidence.addProperty("insideToRearMovingLocalNetAisleProgressBlocks",
                    insideToRearMovingLocalNetAisleProgressBlocks);
            evidence.addProperty("rearToReturnedMovingLocalNetAisleProgressBlocks",
                    rearToReturnedMovingLocalNetAisleProgressBlocks);
            evidence.addProperty("interiorDoorOpenedByWalkthrough", interiorDoorOpened);
            evidence.addProperty("checkpointIndex", nextCheckpoint);
            evidence.addProperty("pendingCheckpoint", pendingCheckpoint == null ? "" : pendingCheckpoint);
            evidence.addProperty("pendingCheckpointTicks", pendingCheckpointTicks);
            evidence.addProperty("pendingStableSupportTicks", pendingSupportTicks);
            evidence.addProperty("nativeMotionSampleCount", nativeMotionSamples.size());
            evidence.addProperty("movingTicksSampled", movingTicksSampled);
            evidence.addProperty("poweredMovingTicks", poweredMovingTicks);
            evidence.addProperty("physicallyMovingTicks", physicallyMovingTicks);
            evidence.addProperty("movingCarriagePathLength", movingCarriagePathLength);
            evidence.addProperty("maximumObservedSpeed", maximumObservedSpeed);
            evidence.addProperty("minimumObservedSpeed", Double.isFinite(minimumObservedSpeed) ? minimumObservedSpeed : 0);
            evidence.addProperty("maximumObservedLift", maximumObservedLift);
            evidence.addProperty("maximumObservedBodyLift", maximumObservedBodyLift);
            evidence.addProperty("stableNativeSupportTicks", stableSupportTicks);
            evidence.addProperty("energyAtDepart", totalDepartureEnergy());
            evidence.addProperty("energyNow", totalCurrentEnergy());
            evidence.add("sourceEnergy", sourceEnergyEvidence());
            if (geometry != null) evidence.add("geometry", geometry.deepCopy());
            if (exteriorPosition != null) evidence.add("exteriorPose", poseEvidence(exteriorPosition,
                    carriage == null ? Vec3.ZERO : carriage.toLocalVector(exteriorPosition, 1.0f)));
            if (exteriorTarget != null) evidence.add("exteriorViewTarget", vector(exteriorTarget));
            if (boardingBaseline != null) evidence.add("boardingBaseline", poseEvidence(boardingBaseline, boardingBaselineLocal));
            if (insidePosition != null) evidence.add("insidePose", poseEvidence(insidePosition, insidePositionLocal));
            if (rearPosition != null) evidence.add("rearCarriageLocalPosition", vector(rearPosition));
            if (returnedPosition != null) evidence.add("returnedCarriageLocalPosition", vector(returnedPosition));
            if (lastWalkingLocal != null) evidence.add("lastWalkingLocalPosition", vector(lastWalkingLocal));
            if (carriage != null) {
                evidence.addProperty("carriageEntityAlive", carriage.isAlive());
                evidence.addProperty("carriageEntityResolvesByUUID", level.getEntity(carriageId) == carriage);
                evidence.add("carriageWorldPosition", vector(carriage.position()));
                evidence.add("lastMotionDisplacement", vector(lastMotionDisplacement));
                evidence.addProperty("carriageWorldDisplacementFromBoarding",
                        horizontalDistance(carriage.position(), carriagePositionAtReady));
                evidence.addProperty("carriageWorldDisplacementFromDepart", departCarriagePosition == null ? 0
                        : horizontalDistance(carriage.position(), departCarriagePosition));
                evidence.addProperty("carriageWorldForwardDisplacementFromDepart", departCarriagePosition == null ? 0
                        : carriage.position().z - departCarriagePosition.z);
                if (player != null) evidence.add("playerCarriageLocalPosition", vector(localPosition()));
            }
            if (built != null && built.train != null) {
                evidence.addProperty("trainRegisteredByUUID", Create.RAILWAYS.trains.get(trainId) == built.train);
                evidence.addProperty("trainSpeed", built.train.speed);
                evidence.addProperty("trainThrottle", built.train.throttle);
                evidence.addProperty("ratedMaximumSpeed", built.train.maxSpeed());
                evidence.addProperty("schedulePresent", built.train.runtime.getSchedule() != null);
                evidence.addProperty("destination", built.destinationName);
                evidence.addProperty("forwardConductor", built.train.hasForwardConductor());
                evidence.addProperty("backwardConductor", built.train.hasBackwardConductor());
                if (built.train instanceof TractionState state) {
                    TrainTraction traction = state.maglev$traction();
                    evidence.addProperty("tractionAuthoritative", traction.authoritative());
                    evidence.addProperty("tractionManaged", traction.managed());
                    evidence.addProperty("tractionTier", traction.tier().name());
                    evidence.addProperty("tractionPowered", traction.powered());
                    evidence.addProperty("tractionLift", traction.lift());
                    evidence.addProperty("tractionSpeedLimit", traction.speedLimit());
                    evidence.addProperty("tractionStatus", traction.status());
                }
                if (carriage instanceof LevitationData bodyLift) {
                    evidence.addProperty("carriageLift", bodyLift.maglev$lift());
                }
            }
            return evidence;
        }

        private void fail(MinecraftServer server, String reason) {
            if (shutdownTicks >= 0) return;
            phase = "failed";
            LOGGER.error("Native passenger walkthrough failed: {}", reason);
            JsonObject evidence;
            try {
                evidence = currentEvidence();
            } catch (Throwable evidenceError) {
                evidence = new JsonObject();
                evidence.addProperty("evidenceError", describe(evidenceError));
            }
            JsonObject report = new JsonObject();
            report.addProperty("complete", false);
            report.addProperty("passed", false);
            report.addProperty("phase", phase);
            report.addProperty("error", reason);
            report.add("evidence", evidence);
            report.add("checkpoints", checkpoints.deepCopy());
            report.add("nativeMotionSamples", nativeMotionSamples.deepCopy());
            try {
                writeReport(report);
            } catch (IOException writeError) {
                LOGGER.error("Could not persist passenger walkthrough failure to {}", resultsFile, writeError);
                report.addProperty("resultWriteError", describe(writeError));
            }
            JsonObject event = new JsonObject();
            event.addProperty("type", "error");
            event.addProperty("passed", false);
            event.addProperty("error", reason);
            event.add("evidence", evidence.deepCopy());
            event.add("checkpoints", checkpoints.deepCopy());
            try {
                send(event);
            } catch (Throwable sendError) {
                LOGGER.warn("Could not send walkthrough failure event to the owned client: {}", describe(sendError));
            }
            shutdownTicks = SHUTDOWN_DELAY_TICKS;
        }

        private void send(JsonObject event) {
            if (player == null || level == null || level.getServer().getPlayerList().getPlayer(playerId) != player) {
                throw new IllegalStateException("Owned walkthrough client disconnected before event " + event.get("type"));
            }
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
            payload.writeUtf(MaglevTestMod.JSON.toJson(event));
            ServerPlayNetworking.send(player, MaglevTestMod.EVENTS, payload);
        }

        private void writeReport(JsonObject report) throws IOException {
            Path parent = resultsFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(resultsFile, MaglevTestMod.JSON.toJson(report) + System.lineSeparator());
        }

        private static String requiredString(JsonObject object, String key) {
            if (!object.has(key) || !object.get(key).isJsonPrimitive()
                    || !object.getAsJsonPrimitive(key).isString() || object.get(key).getAsString().isBlank()) {
                throw new IllegalArgumentException("Walkthrough control request requires a non-empty string '" + key + "'");
            }
            return object.get(key).getAsString();
        }

        private static int floor(double value) {
            return (int) Math.floor(value);
        }

        private static double horizontalDistance(Vec3 first, Vec3 second) {
            return Math.hypot(first.x - second.x, first.z - second.z);
        }

        private static JsonObject vector(Vec3 value) {
            JsonObject result = new JsonObject();
            result.addProperty("x", value.x);
            result.addProperty("y", value.y);
            result.addProperty("z", value.z);
            return result;
        }

        private static JsonObject blockPos(BlockPos value) {
            JsonObject result = new JsonObject();
            result.addProperty("x", value.getX());
            result.addProperty("y", value.getY());
            result.addProperty("z", value.getZ());
            return result;
        }

        private static String blockId(BlockState state) {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }

        private static String describe(Throwable error) {
            String message = error.getMessage();
            return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        }
    }
}
