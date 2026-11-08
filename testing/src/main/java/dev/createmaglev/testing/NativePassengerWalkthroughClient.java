package dev.createmaglev.testing;

import com.google.gson.JsonObject;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Explicit test-camera turns; player movement comes only from genuine OS mouse and keyboard input. */
public final class NativePassengerWalkthroughClient {
    private final ArrayBlockingQueue<JsonObject> events = new ArrayBlockingQueue<>(16);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "maglev-passenger-walkthrough-input");
        thread.setDaemon(true);
        return thread;
    });
    private final Path control;
    private volatile String screenClass = "";
    private volatile String stage = "Joining the isolated Homestead station";
    private volatile String description = "A full-sized native Create carriage, not a prebuilt vehicle entity";
    private volatile String cameraLabel = "Camera view only; awaiting station arrival";
    private volatile String motionStatus = "Waiting for native server moving_ready proof";
    private volatile double playerLocalZ = Double.NaN;
    private volatile int unobscuredTicks;
    private volatile PendingCheckpoint pendingCheckpoint;
    private boolean introduced;
    private int joinedTicks;
    private CarriageContraptionEntity observedCarriage;

    private NativePassengerWalkthroughClient(Path control) {
        this.control = control;
    }
    private static final class PendingCheckpoint {
        final JsonObject request;
        final CompletableFuture<Void> sent = new CompletableFuture<>();

        PendingCheckpoint(JsonObject request) {
            this.request = request;
        }
    }


    public static void register() {
        String directory = System.getProperty("createmaglev.tests.controlDir");
        if (!Boolean.getBoolean("createmaglev.tests") || !"1".equals(System.getenv("MAGLEV_RECORDED_XVFB"))
                || directory == null || !Files.isDirectory(Path.of(directory))) {
            throw new IllegalStateException("The passenger walkthrough requires its owned Xvfb test runner");
        }
        System.setProperty("java.awt.headless", "false");
        NativePassengerWalkthroughClient demo = new NativePassengerWalkthroughClient(Path.of(directory));
        ClientPlayNetworking.registerGlobalReceiver(MaglevTestMod.EVENTS, (client, handler, buffer, sender) -> {
            JsonObject event = MaglevTestMod.JSON.fromJson(buffer.readUtf(), JsonObject.class);
            if (!demo.events.offer(event)) throw new IllegalStateException("Passenger walkthrough event queue overflow");
        });
        ClientTickEvents.END_CLIENT_TICK.register(demo::tick);
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            int bottom = graphics.guiHeight() - 8;
            graphics.fill(4, bottom - 58, graphics.guiWidth() - 4, bottom, 0xB0000000);
            graphics.drawString(client.font, demo.stage, 8, bottom - 50, 0xFFFFFF, false);
            graphics.drawString(client.font, demo.description, 8, bottom - 37, 0xCDEEFF, false);
            graphics.drawString(client.font, "Camera: " + demo.cameraLabel, 8, bottom - 24, 0xE9E9E9, false);
            graphics.drawString(client.font, demo.motionStatus
                    + " | unseated | not flying | real Robot W/S input", 8, bottom - 11, 0xEEEEEE, false);
        });
    }

    private void tick(Minecraft client) {
        screenClass = client.screen == null ? "" : client.screen.getClass().getName();
        unobscuredTicks = client.screen == null ? Math.min(unobscuredTicks + 1, 40) : 0;
        if (observedCarriage != null && observedCarriage.isAlive() && client.player != null) {
            var local = observedCarriage.toLocalVector(client.player.position(), 1.0f);
            playerLocalZ = local.z;
            motionStatus = String.format(Locale.ROOT, "Live carriage Z %.1f | player local X/Z %.2f/%.1f",
                    observedCarriage.getZ(), local.x, local.z);
        }
        PendingCheckpoint pending = pendingCheckpoint;
        if (pending != null && client.player != null && client.screen == null) {
            pendingCheckpoint = null;
            try {
                // Vanilla movement/status packets for this tick precede the native assertion request.
                send(pending.request);
                pending.sent.complete(null);
            } catch (RuntimeException error) {
                pending.sent.completeExceptionally(error);
            }
        }
        if (!introduced && client.player != null && client.level != null && ++joinedTicks >= 40) {
            introduced = true;
            worker.execute(() -> run(client));
        }
    }

    private void run(Minecraft client) {
        try {
            Robot robot = new Robot();
            JsonObject joined = new JsonObject();
            joined.addProperty("screen", screenClass);
            emit("MAGLEV_WALKTHROUGH_JOINED", joined);
            awaitFile("onboard.start", 120);
            completeOnboarding(robot);
            long visibleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (unobscuredTicks < 20) {
                if (System.nanoTime() >= visibleDeadline)
                    throw new IllegalStateException("First-join UI did not finish normally: " + screenClass);
                Thread.sleep(50);
            }
            send("hello");
            JsonObject ready = await("ready", null, 120);
            stage = "Exterior view";
            description = "Native station assembly and exterior camera staging; the complete enclosed body is real blocks";
            cameraLabel = "Exterior station view; camera turn only";
            Thread.sleep(5000);
            emit("MAGLEV_WALKTHROUGH_READY", ready);
            awaitFile("record.start", 120);
            Thread.sleep(4500);

            send("board");
            JsonObject boardingReady = await("boarding_ready", null, 30);
            String carriageUuid = boardingReady.getAsJsonObject("evidence").get("carriageUUID").getAsString();
            UUID carriageId = UUID.fromString(carriageUuid);
            turn(client, -90, 0, "East-facing doorway view; camera angle only");
            stage = "Open the side door and step inside";
            description = "Doorway staged once; enter using real mouse and W input, with no scripted position changes";
            click(robot, null, -1, -1, InputEvent.BUTTON3_DOWN_MASK);
            Thread.sleep(600);
            walk(robot, 1600);
            stage = "Boarded in the entrance vestibule";
            description = "The visible interior bulkhead stops the approach beside the clear passenger aisle";
            observeCarriage(client, carriageId);
            turn(client, -30, 0, "Looking past the vestibule toward the windows; camera angle only");
            checkpoint("inside");
            Thread.sleep(1000);

            stage = "Departing: waiting for native moving_ready proof";
            description = "Boarded on foot; no aisle walking begins until the server confirms powered train motion";
            send("depart");
            JsonObject movingReady = await("moving_ready", null, 30);
            if (!movingReady.has("evidence") || !movingReady.get("evidence").isJsonObject())
                throw new IllegalStateException("Native moving_ready omitted its motion evidence: " + movingReady);
            if (Math.abs(movingReady.getAsJsonObject("evidence").get("trainSpeed").getAsDouble()) < 0.01)
                throw new IllegalStateException("Native moving_ready reported a stopped train: " + movingReady);
            emit("MAGLEV_WALKTHROUGH_MOVING_READY", movingReady);

            stage = "Moving carriage: watch fixed scenery through the windows";
            description = "The carriage is moving; side-window views show fixed world scenery passing outside";
            turn(client, -30, 0, "Diagonal aisle and passing side-window scenery; camera angle only");
            Thread.sleep(1800);
            turn(client, 30, 0, "Opposite side windows past the vestibule; camera angle only");
            Thread.sleep(1800);

            turn(client, 0, 0, "Forward aisle view; camera angle only");
            stage = "Moving carriage: walk the aisle toward the rear";
            description = "Real Robot W input on the carriage floor; remain unseated and non-flying";
            Thread.sleep(700);
            // The client's interpolated carriage trails the server's, so the server-side aisle position lags this
            // client-local one (1.6-2.8 blocks observed at 0.4 blocks/tick). Stop near the rear wall (z = 15) so the
            // server's rear region still holds; the return stop at z = 6.0 already carries the same margin.
            walkAisle(robot, 14.0, true);
            checkpoint("rear");

            stage = "At the rear inside the moving carriage";
            description = "Camera turns only to show the roof, overhead lights, seats and side windows";
            turn(client, 0, -20, "Roof and overhead lights; camera angle only");
            Thread.sleep(900);
            turn(client, 90, 20, "Seats and side windows; camera angle only");
            Thread.sleep(1000);
            turn(client, -90, 20, "Opposite seats and windows; camera angle only");
            Thread.sleep(1000);

            turn(client, 180, 0, "Facing back toward the doorway; camera angle only");
            stage = "Moving carriage: walk back along the aisle";
            description = "Return on foot using real W input; stay aboard the moving train";
            Thread.sleep(700);
            walkAisle(robot, 6.0, false);
            checkpoint("returned");
            stage = "Finish inside the moving carriage";
            description = "Both aisle directions complete; still aboard, with no moving-train exit";
            turn(client, 30, 0, "Side-window view while remaining inside; camera angle only");
            Thread.sleep(1000);
            send("finish");
            JsonObject result = await("complete", null, 30);
            if (!result.has("passed") || !result.get("passed").getAsBoolean())
                throw new IllegalStateException("Native passenger walkthrough failed: " + result);
            stage = "PASS: walked forward and back inside the moving carriage";
            description = "Native motion, floor support and the unseated, non-flying return are server-validated";
            emit("MAGLEV_WALKTHROUGH_RESULT", result);
            Thread.sleep(800);
            emit("MAGLEV_WALKTHROUGH_RECORDING_DONE", result);
            awaitFile("record.stop", 30);
            emit("MAGLEV_WALKTHROUGH_COMPLETE", result);
            client.execute(client::stop);
        } catch (Throwable error) {
            stage = "FAIL: " + error;
            JsonObject failure = new JsonObject();
            failure.addProperty("error", error.toString());
            failure.addProperty("screen", screenClass);
            emit("MAGLEV_WALKTHROUGH_FATAL", failure);
            client.execute(client::stop);
        } finally {
            worker.shutdown();
        }
    }

    private void completeOnboarding(Robot robot) throws Exception {
        stage = "Finish Homestead's normal first-join setup";
        if (screenClass.endsWith(".CustomGuiBaseScreen")) {
            click(robot, "Homestead: Start Your Story", 640, 385, InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(1000);
        }
        if (!screenClass.endsWith(".DestinyScreen")) return;
        click(robot, "Choose masculine", 725, 467, InputEvent.BUTTON1_DOWN_MASK);
        click(robot, "Choose Vanilla appearance", 755, 512, InputEvent.BUTTON1_DOWN_MASK);
        click(robot, "Accept character", 640, 584, InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(700);
        click(robot, "Done with customization", 465, 590, InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(1000);
        if (screenClass.endsWith(".DestinyScreen")) {
            click(robot, "Default Somewhere destiny", 448, 388, InputEvent.BUTTON1_DOWN_MASK);
            for (int page = 1; page <= 3; page++)
                click(robot, "Story Next " + page, 640, 484, InputEvent.BUTTON1_DOWN_MASK);
        }
    }

    private static void click(Robot robot, String action, int x, int y, int button) throws InterruptedException {
        if (x >= 0 && y >= 0) robot.mouseMove(x, y);
        Thread.sleep(150);
        robot.mousePress(button);
        try { Thread.sleep(100); }
        finally { robot.mouseRelease(button); }
        Thread.sleep(400);
        if (action != null) {
            JsonObject event = new JsonObject();
            event.addProperty("action", action);
            emit("MAGLEV_WALKTHROUGH_UI", event);
        }
    }

    private void walk(Robot robot, long millis) throws InterruptedException {
        if (!screenClass.isEmpty()) throw new IllegalStateException("A screen occludes the walk: " + screenClass);
        pressKey(robot, KeyEvent.VK_W, millis);
        Thread.sleep(300);
    }

    private void walkAisle(Robot robot, double targetZ, boolean increasing) throws InterruptedException {
        double startZ = playerLocalZ;
        if (!Double.isFinite(startZ)) throw new IllegalStateException("No live carriage-local position for the aisle walk");
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(8);
        robot.keyPress(KeyEvent.VK_W);
        try {
            while (increasing ? playerLocalZ < targetZ : playerLocalZ > targetZ) {
                if (!screenClass.isEmpty()) throw new IllegalStateException("A screen occludes the aisle walk: " + screenClass);
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Held W did not reach the native aisle target: " + playerLocalZ + " vs " + targetZ);
                Thread.sleep(20);
            }
        } finally {
            robot.keyRelease(KeyEvent.VK_W);
        }
        Thread.sleep(300);
        JsonObject input = new JsonObject();
        input.addProperty("action", "Real W input to the native carriage-local aisle target");
        input.addProperty("fromLocalZ", startZ);
        input.addProperty("toLocalZ", playerLocalZ);
        input.addProperty("targetLocalZ", targetZ);
        input.addProperty("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        emit("MAGLEV_WALKTHROUGH_UI", input);
    }


    private void observeCarriage(Minecraft client, UUID carriageId) throws Exception {
        CompletableFuture<Void> observed = new CompletableFuture<>();
        client.execute(() -> {
            try {
                if (client.player == null || client.level == null)
                    throw new IllegalStateException("The live client player or level disappeared after boarding");
                for (var entity : client.level.entitiesForRendering()) {
                    if (!(entity instanceof CarriageContraptionEntity carriage)
                            || !carriageId.equals(carriage.getUUID()) || carriage.getContraption() == null) continue;
                    observedCarriage = carriage;
                    observed.complete(null);
                    return;
                }
                throw new IllegalStateException("The boarding_ready carriage UUID is not visible to the client: " + carriageId);
            } catch (Throwable error) {
                observed.completeExceptionally(error);
            }
        });
        observed.get(10, TimeUnit.SECONDS);
    }

    private static void pressKey(Robot robot, int key, long millis) throws InterruptedException {
        robot.keyPress(key);
        try { Thread.sleep(millis); }
        finally { robot.keyRelease(key); }
    }

    private void turn(Minecraft client, float yaw, float pitch, String label) throws Exception {
        cameraLabel = label;
        CompletableFuture<Void> applied = new CompletableFuture<>();
        client.execute(() -> {
            try {
                if (client.player == null || client.screen != null)
                    throw new IllegalStateException("Cannot stage a view without an unobscured live player");
                GLFW.glfwFocusWindow(client.getWindow().getWindow());
                client.mouseHandler.grabMouse();
                client.player.setYRot(yaw);
                client.player.yRotO = yaw;
                client.player.setXRot(pitch);
                client.player.xRotO = pitch;
                applied.complete(null);
            } catch (Throwable error) { applied.completeExceptionally(error); }
        });
        applied.get(10, TimeUnit.SECONDS);
        Thread.sleep(150);
    }

    private JsonObject checkpoint(String name) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("type", "checkpoint");
        request.addProperty("name", name);
        if (pendingCheckpoint != null) throw new IllegalStateException("Walking checkpoints overlapped");
        PendingCheckpoint pending = new PendingCheckpoint(request);
        pendingCheckpoint = pending;
        pending.sent.get(10, TimeUnit.SECONDS);
        JsonObject result = await("checkpoint", name, 30);
        if (!result.has("passed") || !result.get("passed").getAsBoolean())
            throw new IllegalStateException("Native checkpoint failed: " + result);
        emit("MAGLEV_WALKTHROUGH_CHECKPOINT", result);
        return result;
    }

    private JsonObject await(String type, String name, int timeout) throws Exception {
        JsonObject event = events.poll(timeout, TimeUnit.SECONDS);
        if (event == null) throw new IllegalStateException("Timed out waiting for " + type + ":" + name);
        String actual = event.has("type") ? event.get("type").getAsString() : "";
        if (!actual.equals(type) || name != null && (!event.has("name") || !name.equals(event.get("name").getAsString())))
            throw new IllegalStateException("Unexpected native passenger event while waiting for " + type + ": " + event);
        return event;
    }

    private void awaitFile(String name, int timeout) throws Exception {
        Path file = control.resolve(name);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Recorder did not signal " + name);
            Thread.sleep(50);
        }
        Files.delete(file);
    }

    private static void send(String type) {
        JsonObject request = new JsonObject();
        request.addProperty("type", type);
        send(request);
    }

    private static void send(JsonObject request) {
        var payload = PacketByteBufs.create();
        payload.writeUtf(MaglevTestMod.JSON.toJson(request));
        ClientPlayNetworking.send(MaglevTestMod.CONTROL, payload);
    }

    private static void emit(String marker, JsonObject event) {
        System.out.println(marker + " " + MaglevTestMod.JSON.toJson(event));
    }
}
