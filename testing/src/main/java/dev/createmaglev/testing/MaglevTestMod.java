package dev.createmaglev.testing;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.power.TractionPower;
import dev.createmaglev.train.TractionState;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TrainTraction;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicitly test-only entrypoint and native loopback-client/server recorder coordinator. */
public final class MaglevTestMod implements ModInitializer {
    public static final ResourceLocation CONTROL = new ResourceLocation("create_maglev_tests", "control");
    public static final ResourceLocation EVENTS = new ResourceLocation("create_maglev_tests", "events");
    public static final Gson JSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(MaglevTestMod.class);
    private static final RecordedSuite RECORDED = new RecordedSuite();
    private static long lastTickNanos;
    /** Harness scenes measure debits from a full buffer while generation may still run; keep the 1.0.x size. */
    private static final int HARNESS_SUBSTATION_CAPACITY = 8192;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("createmaglev.tests")) {
            throw new IllegalStateException("The test helper must only load with -Dcreatemaglev.tests=true");
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MaglevConfig.SUBSTATION_CAPACITY.set(HARNESS_SUBSTATION_CAPACITY);
            LOGGER.info("Test harness pinned substation capacity to {}", MaglevConfig.substationCapacity());
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            paceNativeTick();
        });
        NativeRestartScenario.register();
        NativePassengerWalkthrough.register();
        if (Boolean.getBoolean("createmaglev.tests.recorded")) {
            ServerPlayNetworking.registerGlobalReceiver(CONTROL, (server, player, handler, buffer, responseSender) -> {
                final String message;
                try {
                    message = buffer.readUtf();
                } catch (RuntimeException error) {
                    server.execute(() -> RECORDED.fail(server, "Malformed client control packet: " + error.getMessage()));
                    return;
                }
                server.execute(() -> RECORDED.receive(server, player, message));
            });
            ServerTickEvents.END_SERVER_TICK.register(RECORDED::tick);
        }
        LOGGER.info("Create Maglev test helper initialized; recorded={}", Boolean.getBoolean("createmaglev.tests.recorded"));
    }

    private static void paceNativeTick() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            long deadline = lastTickNanos + 50_000_000L;
            while (deadline - now > 0L) {
                LockSupport.parkNanos(deadline - now);
                now = System.nanoTime();
            }
        }
        lastTickNanos = System.nanoTime();
    }

    private static final class RecordedSuite {
        private static final List<String> CASES = List.of(
                "stock-control", "electric-powered", "bullet-powered",
                "maglev-powered", "maglev-unpowered", "maglev-loss", "maglev-rider");
        private final ArrayList<JsonObject> results = new ArrayList<>();
        private final Path controlDir = Path.of(System.getProperty("createmaglev.tests.controlDir", "testing/build/recording-control"));
        private final Path reportFile = Path.of(System.getProperty("createmaglev.tests.results", "testing/build/reports/recorded-results.json"));
        private ServerPlayer player;
        private ServerLevel level;
        private NativeTrainFixture.BuiltTrain built;
        private NativeNewAgePowerFixture supply;
        private NativeRiderScenario rider;
        private int caseIndex;
        private int phaseTick;
        private int verifyTick;
        private int lossStartedTick = -1;
        private int completeCountdown = -1;
        private String phase = "idle";
        private String currentCase;
        private boolean wireDisconnected;
        private boolean sawPower;
        private boolean sawUnpoweredAfterLoss;
        private boolean carriagePersisted = true;
        private double moved;
        private double maxSpeed;
        private double speedAtLoss;
        private double slowestAfterLoss = Double.POSITIVE_INFINITY;
        private int brakingSamples;
        private float maximumLift;
        private double maximumBodyLift;
        private long startingEnergy;
        private net.minecraft.world.phys.Vec3 initialPosition;
        private String preparationError;

        void receive(MinecraftServer server, ServerPlayer sender, String raw) {
            if (completeCountdown >= 0) return;
            try {
                var parsed = com.google.gson.JsonParser.parseString(raw);
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("Client request must be a JSON object");
                JsonObject request = parsed.getAsJsonObject();
                String type = requiredString(request, "type");
                if (type.equals("client_failure")) {
                    fail(server, "Recorded client failed: " + optionalString(request, "error", "unspecified client error"));
                    return;
                }
                if (type.equals("hello")) {
                    if (player != null && !player.getUUID().equals(sender.getUUID())) {
                        throw new IllegalStateException("Only the owned offline test client may drive recorded cases");
                    }
                    if (!"idle".equals(phase)) throw new IllegalStateException("Duplicate recorded-client hello during " + phase);
                    player = sender;
                    level = sender.serverLevel();
                    prepareCase(server);
                    return;
                }
                if (player == null || !player.getUUID().equals(sender.getUUID())) {
                    throw new IllegalStateException("Control request did not come from the owned test client");
                }
                if (type.equals("verify")) {
                    if (!"observing".equals(phase)) throw new IllegalStateException("Verification arrived outside an active scene");
                    verifyTick = server.getTickCount();
                    finishCase(server);
                    return;
                }
                throw new IllegalArgumentException("Unsupported client control type: " + type);
            } catch (Throwable error) {
                fail(server, error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        void tick(MinecraftServer server) {
            if (completeCountdown >= 0) {
                if (--completeCountdown <= 0) server.halt(false);
                return;
            }
            if (player == null) return;
            try {
                switch (phase) {
                    case "preparing" -> {
                        if (server.getTickCount() - phaseTick >= 50) {
                            if (supply != null && supply.substation().energyStored() <= 0) {
                                throw new IllegalStateException("Native NewAge source did not charge before recording scene " + currentCase);
                            }
                            sendScene();
                            phase = "waiting_start";
                        }
                    }
                    case "waiting_start" -> {
                        Path start = controlDir.resolve(currentCase + ".start");
                        if (Files.exists(start)) {
                            Files.delete(start);
                            beginAction(server);
                        }
                    }
                    case "observing" -> sampleNativeState(server);
                    case "waiting_advance" -> {
                        Path advance = controlDir.resolve(currentCase + ".advance");
                        if (Files.exists(advance)) {
                            Files.delete(advance);
                            caseIndex++;
                            if (caseIndex == CASES.size()) finishAll(server);
                            else prepareCase(server);
                        }
                    }
                    default -> { }
                }
            } catch (Throwable error) {
                fail(server, error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        private void releaseFixture() {
            if (built == null) return;
            var entity = built.liveCarriage();
            if (entity != null) entity.stopControlling(built.controlsLocalPos);
            if (supply != null) {
                supply.stopGeneration();
                supply.removeOwnedTickets();
            }
            if (player != null) player.stopRiding();
            built.discardRecordingTrain();
            built.releaseRecordingTickets();
            built = null;
            supply = null;
            rider = null;
        }

        private void prepareCase(MinecraftServer server) {
            releaseFixture();
            if (caseIndex >= CASES.size()) throw new IllegalStateException("Recorded test case index exceeded its case contract");
            currentCase = CASES.get(caseIndex);
            int region = caseIndex;
            BlockPos origin = new BlockPos(512 + region * 512, 64, 512);
            Block track;
            TrackMaterial material;
            Block bogey;
            switch (currentCase) {
                case "stock-control" -> { track = AllBlocks.TRACK.get(); material = TrackMaterial.ANDESITE; bogey = AllBlocks.SMALL_BOGEY.get(); }
                case "electric-powered" -> { track = AllBlocks.TRACK.get(); material = TrackMaterial.ANDESITE; bogey = MaglevContent.ELECTRIC_BOGEY; }
                case "bullet-powered" -> { track = MaglevContent.REINFORCED_TRACK; material = MaglevContent.REINFORCED_MATERIAL; bogey = MaglevContent.BULLET_BOGEY; }
                case "maglev-powered", "maglev-loss", "maglev-rider" -> { track = MaglevContent.MAGLEV_TRACK; material = MaglevContent.MAGLEV_MATERIAL; bogey = MaglevContent.MAGLEV_BOGEY; }
                case "maglev-unpowered" -> { track = MaglevContent.MAGLEV_TRACK; material = MaglevContent.MAGLEV_MATERIAL; bogey = MaglevContent.MAGLEV_BOGEY; }
                default -> throw new IllegalStateException("No fixture declared for " + currentCase);
            }
            built = NativeTrainFixture.build(level, origin, (TrackBlock) track, material,
                    "recorded-" + currentCase, bogey, isManualCase(currentCase));
            if ("maglev-rider".equals(currentCase)) NativeTrainFixture.addPassengerSeat(built);
            boolean powered = !"maglev-unpowered".equals(currentCase) && !"stock-control".equals(currentCase);
            supply = powered ? NativeNewAgePowerFixture.build(level, origin.offset(24, 0, 40)) : null;
            rider = null;
            // A real perch keeps the view stable without per-tick player teleports.
            BlockPos perch = built.origin.offset(24, 4, 14);
            level.setBlock(perch, Blocks.STONE.defaultBlockState(), 3);
            player.teleportTo(level, perch.getX() + 0.5, perch.getY() + 1,
                    perch.getZ() + 0.5, 90.0f, 10.0f);
            phaseTick = server.getTickCount();
            phase = "preparing";
            wireDisconnected = false;
            sawPower = false;
            sawUnpoweredAfterLoss = false;
            carriagePersisted = true;
            moved = 0;
            maxSpeed = 0;
            speedAtLoss = 0;
            slowestAfterLoss = Double.POSITIVE_INFINITY;
            brakingSamples = 0;
            maximumLift = 0;
            maximumBodyLift = 0;
            startingEnergy = 0;
            initialPosition = null;
            lossStartedTick = -1;
            preparationError = null;
            LOGGER.info("Preparing isolated native recorded scene {} at {}", currentCase, origin);
        }

        private void sendScene() {
            try {
                if ("maglev-rider".equals(currentCase)) rider = new NativeRiderScenario(level, built, supply, player);
                built.assemble();
                if (supply != null) startingEnergy = supply.substation().energyStored();
                JsonObject scene = new JsonObject();
                scene.addProperty("case", currentCase);
                scene.addProperty("label", label(currentCase));
                scene.addProperty("description", description(currentCase)
                        + (isManualCase(currentCase) ? "" : " Static side camera."));
                scene.addProperty("controller", controller(currentCase));
                scene.addProperty("inputMillis", rider != null ? 4000 : 1500);
                scene.addProperty("observationMillis", "maglev-loss".equals(currentCase) ? 7000 : rider != null ? 2000 : 1500);
                JsonObject event = new JsonObject();
                event.addProperty("type", "scene");
                event.add("scene", scene);
                send(event);
            } catch (RuntimeException | Error error) {
                preparationError = error.getClass().getSimpleName() + ": " + error.getMessage();
                throw error;
            }
        }

        private void beginAction(MinecraftServer server) {
            if (supply != null && !"maglev-loss".equals(currentCase)) {
                // Measured stored energy was genuinely generated and delivered over the native
                // wire. Isolate that charged buffer so replenishment cannot conceal its use.
                supply.disconnect();
                supply.stopGeneration();
            }
            if (isManualCase(currentCase)) {
                built.activateNativeControls(player);
                if (rider != null) rider.begin(server);
                for (int direction = 0; direction < 2; direction++) {
                    var carriage = built.train.carriages.get(0);
                    var front = direction == 0 ? carriage.getLeadingPoint() : carriage.getTrailingPoint();
                    var probe = new TravellingPoint(front.node1, front.node2, front.edge, front.position, front.upsideDown);
                    double advanced = probe.travel(built.train.graph, direction == 0 ? 2 : -2,
                            (graph, choices) -> choices.getSecond().get(0));
                    var point = probe.getPosition(built.train.graph);
                    var provider = TractionPower.nearestSupply(level, point);
                    LOGGER.info("Native manual route {}: point={}, travelled={}, blocked={}, supplier={}",
                            direction == 0 ? "forward" : "backward", point, advanced, probe.blocked,
                            provider == null ? "none" : provider);
                }
            } else {
                built.scheduleToDestination();
            }
            if ("maglev-loss".equals(currentCase)) lossStartedTick = server.getTickCount();
            phaseTick = server.getTickCount();
            phase = "observing";
            JsonObject started = new JsonObject();
            started.addProperty("type", "scene_started");
            started.addProperty("case", currentCase);
            started.addProperty("controller", controller(currentCase));
            send(started);
        }

        private void sampleNativeState(MinecraftServer server) {
            if (built.train == null) throw new IllegalStateException("Native station assembly failed to retain a train");
            var carriage = built.liveCarriage();
            if (carriage == null || carriage.isRemoved()) {
                carriagePersisted = false;
                throw new IllegalStateException("The live native train actor disappeared during " + currentCase);
            }
            if (rider != null) rider.tick(server);
            if (initialPosition == null) initialPosition = carriage.position();
            moved = Math.max(moved, carriage.position().distanceTo(initialPosition));
            maxSpeed = Math.max(maxSpeed, Math.abs(built.train.speed));
            TrainTraction traction = ((TractionState) built.train).maglev$traction();
            sawPower |= traction.powered();
            maximumLift = Math.max(maximumLift, traction.lift());
            maximumBodyLift = Math.max(maximumBodyLift, bodyLift());
            if ("maglev-loss".equals(currentCase) && !wireDisconnected
                    && server.getTickCount() - lossStartedTick >= 30
                    && traction.powered() && traction.lift() >= 0.25f && Math.abs(built.train.speed) > 0.2) {
                if (supply == null) throw new IllegalStateException("Power-loss scene has no genuine NewAge source");
                speedAtLoss = Math.abs(built.train.speed);
                supply.disconnect();
                supply.stopGeneration();
                // Opening the real redstone traction breaker isolates even a charged buffer.
                // Cutting a charging wire alone must not magically erase stored energy.
                level.setBlock(supply.substationPos().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                wireDisconnected = true;
            }
            if (wireDisconnected && !traction.powered()) {
                sawUnpoweredAfterLoss = true;
                double speed = Math.abs(built.train.speed);
                if (speed > 0.001 && speed < speedAtLoss - 0.001) brakingSamples++;
                slowestAfterLoss = Math.min(slowestAfterLoss, speed);
            }
        }

        private double bodyLift() {
            var entity = built.liveCarriage();
            var carriage = entity.getCarriage();
            var anchors = carriage.getDimensionalIfPresent(level.dimension());
            if (anchors == null || anchors.positionAnchor == null) throw new IllegalStateException("Live carriage has no native track anchor");
            var point = carriage.getLeadingPoint();
            return entity.position().subtract(anchors.positionAnchor)
                    .dot(point.edge.getNormal(carriage.train.graph, point.position / point.edge.getLength()));
        }

        private void finishCase(MinecraftServer server) {
            if (built == null || built.train == null) throw new IllegalStateException("Cannot verify a case without a live assembled train");
            TrainTraction traction = ((TractionState) built.train).maglev$traction();
            double finalBodyLift = bodyLift();
            boolean passed;
            JsonObject evidence = new JsonObject();
            evidence.addProperty("controller", controller(currentCase));
            evidence.addProperty("tier", traction.tier().name().toLowerCase(java.util.Locale.ROOT));
            evidence.addProperty("nativeScheduleActive", built.train.navigation.isActive());
            evidence.addProperty("distanceMoved", moved);
            evidence.addProperty("maximumSpeed", maxSpeed);
            evidence.addProperty("poweredObserved", sawPower);
            evidence.addProperty("currentPowered", traction.powered());
            evidence.addProperty("maximumLift", maximumLift);
            evidence.addProperty("maximumPhysicalBodyLift", maximumBodyLift);
            evidence.addProperty("finalPhysicalBodyLift", finalBodyLift);
            evidence.addProperty("liveCarriage", carriagePersisted && built.liveCarriage() != null);
            if (supply != null) {
                evidence.addProperty("energyBeforeAction", startingEnergy);
                evidence.addProperty("energyRemaining", supply.substation().energyStored());
                evidence.addProperty("copperWireConnected", supply.hasLiveCopperWire());
            }
            if (rider != null) evidence.add("rider", rider.evidence());
            switch (currentCase) {
                case "stock-control" -> passed = moved > 1.0 && built.train.navigation.isActive() && carriagePersisted
                        && !traction.managed() && maximumBodyLift < 0.001 && supply == null;
                case "electric-powered" -> passed = moved > 1.0 && sawPower && maxSpeed <= TrainTier.ELECTRIC.topSpeed() + 0.001
                        && supply.substation().energyStored() < startingEnergy && built.liveCarriage() != null;
                case "bullet-powered" -> passed = moved > 1.0 && sawPower && maxSpeed <= TrainTier.BULLET.topSpeed() + 0.001
                        && supply.substation().energyStored() < startingEnergy && built.liveCarriage() != null;
                case "maglev-powered" -> passed = moved > 1.0 && sawPower && maximumLift >= 0.25f && maximumBodyLift > 0.1
                        && maxSpeed <= TrainTier.MAGLEV.topSpeed() + 0.001
                        && supply.substation().energyStored() < startingEnergy && built.liveCarriage() != null;
                case "maglev-unpowered" -> passed = !sawPower && !traction.powered() && moved < 0.001
                        && traction.speedLimit() == 0 && maximumBodyLift < 0.001 && built.liveCarriage() != null;
                case "maglev-loss" -> passed = wireDisconnected && !supply.hasLiveCopperWire() && sawPower
                        && sawUnpoweredAfterLoss && speedAtLoss > 0.2 && brakingSamples >= 3
                        && Math.abs(built.train.speed) <= 0.001 && traction.lift() == 0
                        && maximumBodyLift > 0.1 && Math.abs(finalBodyLift) < 0.001
                        && supply.substation().energyStored() > 0 && moved > 0 && built.liveCarriage() != null;
                case "maglev-rider" -> passed = rider != null && rider.passed()
                        && sawPower && maximumBodyLift > 0.1 && Math.abs(finalBodyLift) < 0.001
                        && !traction.powered() && Math.abs(built.train.speed) <= 0.001
                        && supply.substation().energyStored() > 0
                        && supply.substation().energyStored() < startingEnergy;
                default -> throw new IllegalStateException("No verifier declared for " + currentCase);
            }
            evidence.addProperty("wireDisconnectedDuringRun", wireDisconnected);
            evidence.addProperty("unpoweredAfterDisconnect", sawUnpoweredAfterLoss);
            evidence.addProperty("speedAtPowerLoss", speedAtLoss);
            evidence.addProperty("slowestAfterPowerLoss", slowestAfterLoss == Double.POSITIVE_INFINITY ? -1 : slowestAfterLoss);
            evidence.addProperty("intermediateBrakingSamples", brakingSamples);
            evidence.addProperty("finalLift", traction.lift());
            evidence.addProperty("finalSpeed", Math.abs(built.train.speed));
            evidence.addProperty("preparedAtServerTick", phaseTick);
            evidence.addProperty("verifiedAtServerTick", verifyTick);
            evidence.addProperty("finalSafetyLimit", traction.speedLimit());
            evidence.addProperty("finalTargetSpeed", built.train.targetSpeed);
            evidence.addProperty("cameraMode", isManualCase(currentCase)
                    ? "native controlled-player view" : "static side view from an off-track perch");
            JsonObject result = new JsonObject();
            result.addProperty("type", "result");
            result.addProperty("case", currentCase);
            result.addProperty("passed", passed);
            result.add("evidence", evidence);
            results.add(result.deepCopy());
            writeReport(false);
            send(result);
            phase = "waiting_advance";
            LOGGER.info("Recorded native case {} passed={} evidence={}", currentCase, passed, evidence);
        }

        private void finishAll(MinecraftServer server) {
            releaseFixture();
            writeReport(true);
            JsonObject complete = new JsonObject();
            complete.addProperty("type", "complete");
            complete.addProperty("passed", results.size() == CASES.size() && results.stream().allMatch(r -> r.get("passed").getAsBoolean()));
            complete.addProperty("count", results.size());
            send(complete);
            phase = "complete";
            completeCountdown = 40;
        }


        private void send(JsonObject event) {
            if (player == null || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
                throw new IllegalStateException("Owned recorded test client disconnected before event " + event);
            }
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
            payload.writeUtf(JSON.toJson(event));
            ServerPlayNetworking.send(player, EVENTS, payload);
        }

        private void writeReport(boolean complete) {
            try {
                Files.createDirectories(reportFile.toAbsolutePath().getParent());
                JsonObject report = new JsonObject();
                report.addProperty("complete", complete);
                JsonArray array = new JsonArray();
                results.forEach(result -> array.add(result.deepCopy()));
                report.add("results", array);
                Files.writeString(reportFile, JSON.toJson(report) + System.lineSeparator());
            } catch (IOException error) {
                throw new IllegalStateException("Could not persist native recorded results to " + reportFile, error);
            }
        }

        private void fail(MinecraftServer server, String reason) {
            LOGGER.error("Recorded Maglev regression runner failed: {}", reason);
            JsonObject result = new JsonObject();
            result.addProperty("type", "result");
            result.addProperty("case", currentCase == null ? "runner" : currentCase);
            result.addProperty("passed", false);
            JsonObject evidence = new JsonObject();
            evidence.addProperty("error", reason);
            if (preparationError != null) evidence.addProperty("preparationError", preparationError);
            result.add("evidence", evidence);
            if (player != null && currentCase != null && !"waiting_advance".equals(phase)) {
                try { send(result); } catch (RuntimeException ignored) { }
            }
            try {
                if (!results.isEmpty()) writeReport(false);
            } catch (RuntimeException ignored) { }
            server.halt(false);
            completeCountdown = 0;
        }

        private static String label(String id) {
            return switch (id) {
                case "stock-control" -> "Stock Create bogey: native scheduled control";
                case "electric-powered" -> "Electric tier: powered native manual controls";
                case "bullet-powered" -> "Bullet tier: reinforced native scheduled route";
                case "maglev-powered" -> "Maglev tier: powered dedicated guideway";
                case "maglev-unpowered" -> "Maglev tier: unpowered keyboard drive attempt";
                case "maglev-loss" -> "Maglev tier: traction breaker loss, gradual braking, and landing";
                case "maglev-rider" -> "Maglev tier: rider rise, braking, and landing";
                default -> id;
            };
        }

        private static String description(String id) {
            return switch (id) {
                case "stock-control" -> "The server assembles a real Create station consist, then starts its native schedule after capture begins.";
                case "electric-powered" -> "The server authorizes Create controls; real W input drives the electric consist using measured NewAge-generated charge isolated from replenishment.";
                case "bullet-powered" -> "The server starts a native reinforced-track schedule using measured NewAge-generated charge isolated from replenishment.";
                case "maglev-powered" -> "The server starts a native guideway schedule using measured NewAge-generated charge; authoritative body lift and movement are checked.";
                case "maglev-unpowered" -> "Real W input attempts to drive a dedicated-guideway maglev without a substation; it must remain stopped with no lift.";
                case "maglev-loss" -> "After powered movement and lift begin, the server disconnects the wire and opens the redstone traction breaker; stored energy is retained while the train brakes and lands.";
                case "maglev-rider" -> "Real W input drives a natively seated passenger while the server closes and opens the actual traction breaker; seat ownership and support through rise, motion, and landing are checked.";
                default -> id;
            };
        }

        private static boolean isManualCase(String id) {
            return "electric-powered".equals(id) || "maglev-unpowered".equals(id) || "maglev-rider".equals(id);
        }

        private static String controller(String id) {
            return isManualCase(id) ? "native-manual-keyboard" : "native-schedule";
        }

        private static String requiredString(JsonObject object, String key) {
            if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
                throw new IllegalArgumentException("Request field '" + key + "' must be a string");
            }
            return object.get(key).getAsString();
        }

        private static String optionalString(JsonObject object, String key, String fallback) {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
        }
    }
}
