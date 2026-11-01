package dev.createmaglev.testing;

import com.google.gson.JsonObject;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The external Xvfb recorder owns capture; only AWT Robot produces gameplay key input. */
public final class MaglevTestClient implements ClientModInitializer {
    private final AtomicBoolean failed = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "maglev-recorded-input");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Scene active;
    private volatile String label = "Create Maglev native regression recordings";
    private volatile String description = "";
    private volatile String status = "Waiting for the owned loopback server";
    private boolean introduced;
    private int joinedTicks;

    private static final class Scene {
        final String id, label, description, controller;
        final long inputMillis, observationMillis;
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<Void> controlsReady = new CompletableFuture<>();
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        int controlWaitTicks;

        Scene(JsonObject json) {
            id = json.get("case").getAsString();
            label = json.get("label").getAsString();
            description = json.get("description").getAsString();
            controller = json.get("controller").getAsString();
            inputMillis = json.get("inputMillis").getAsLong();
            observationMillis = json.get("observationMillis").getAsLong();
        }
    }

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("createmaglev.tests.recorded")) return;
        String control = System.getProperty("createmaglev.tests.controlDir");
        if (!"1".equals(System.getenv("MAGLEV_RECORDED_XVFB")) || control == null || !Files.isDirectory(Path.of(control))) {
            throw new IllegalStateException("Recorded inputs require the runner's private Xvfb display and control directory");
        }
        System.setProperty("java.awt.headless", "false");
        ClientPlayNetworking.registerGlobalReceiver(MaglevTestMod.EVENTS, (client, handler, buffer, sender) -> {
            String message = buffer.readUtf();
            client.execute(() -> {
                try { receive(client, MaglevTestMod.JSON.fromJson(message, JsonObject.class)); }
                catch (Throwable error) { fail(client, error); }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            int y = graphics.guiHeight() - 92;
            graphics.fill(4, y - 4, graphics.guiWidth() - 4, graphics.guiHeight() - 8, 0xB0000000);
            graphics.drawString(client.font, label, 8, y, 0xFFFFFF, false);
            graphics.drawString(client.font, status, 8, y + 12, status.startsWith("PASS") ? 0x55FF55 : 0xFFDD66, false);
            int line = y + 26;
            for (var text : client.font.split(Component.literal(description), graphics.guiWidth() - 20)) {
                if (line > graphics.guiHeight() - 20) break;
                graphics.drawString(client.font, text, 8, line, 0xEEEEEE, false);
                line += 10;
            }
        });
    }

    private void tick(Minecraft client) {
        if (failed.get()) return;
        try {
            if (!introduced && client.player != null && client.level != null && ++joinedTicks >= 30) {
                introduced = true;
                send("hello");
            }
            Scene scene = active;
            if (scene == null || !scene.started.isDone() || scene.controlsReady.isDone()
                    || !scene.controller.equals("native-manual-keyboard")) return;
            if (client.player != null && client.level != null) {
                for (var entity : client.level.entitiesForRendering()) {
                    if (!(entity instanceof CarriageContraptionEntity carriage)
                            || carriage.getControllingPlayer().filter(client.player.getUUID()::equals).isEmpty()
                            || carriage.getContraption() == null) continue;
                    var controls = carriage.getContraption().getBlocks().get(NativeTrainFixture.CONTROL_OFFSET);
                    if (controls == null || !AllBlocks.TRAIN_CONTROLS.has(controls.state())) continue;
                    GLFW.glfwFocusWindow(client.getWindow().getWindow());
                    client.setScreen(null);
                    client.mouseHandler.grabMouse();
                    ControlsHandler.startControlling(carriage, NativeTrainFixture.CONTROL_OFFSET);
                    scene.controlsReady.complete(null);
                    return;
                }
            }
            if (++scene.controlWaitTicks > 60) throw new IllegalStateException("Native train-control authorization did not synchronize to the client");
        } catch (Throwable error) { fail(client, error); }
    }

    private void receive(Minecraft client, JsonObject event) {
        switch (event.get("type").getAsString()) {
            case "scene" -> {
                Scene scene = new Scene(event.getAsJsonObject("scene"));
                if (active != null && !active.result.isDone()) throw new IllegalStateException("Recorded scenes overlapped");
                active = scene;
                label = scene.label;
                description = scene.description;
                status = "Prepared; waiting for the recorder";
                client.setScreen(null);
                worker.execute(() -> record(client, scene));
            }
            case "scene_started" -> {
                Scene scene = requireScene(event);
                status = "Executing the labeled native action / real keyboard input";
                scene.started.complete(null);
            }
            case "result" -> {
                Scene scene = requireScene(event);
                status = event.get("passed").getAsBoolean() ? "PASS - native assertions satisfied" : "FAIL - native assertions failed";
                scene.result.complete(event);
            }
            case "complete" -> {
                emit("MAGLEV_TEST_CLIENT_COMPLETE", event);
                worker.shutdown();
                client.stop();
            }
            default -> throw new IllegalArgumentException("Unknown recorded server event: " + event);
        }
    }

    private Scene requireScene(JsonObject event) {
        Scene scene = active;
        if (scene == null || !scene.id.equals(event.get("case").getAsString())) {
            throw new IllegalStateException("Server event does not match the active recorded scene: " + event);
        }
        return scene;
    }

    private void record(Minecraft client, Scene scene) {
        try {
            // Only presentation settles here; the server does not start the mechanism before capture is ready.
            Thread.sleep(1200);
            JsonObject details = new JsonObject();
            details.addProperty("case", scene.id);
            details.addProperty("label", scene.label);
            details.addProperty("description", scene.description);
            details.addProperty("controller", scene.controller);
            JsonObject ready = new JsonObject();
            ready.addProperty("clip", scene.id);
            ready.add("scene", details);
            emit("MAGLEV_TEST_CLIENT_READY", ready);
            scene.started.get(30, TimeUnit.SECONDS);
            if (scene.controller.equals("native-manual-keyboard")) {
                scene.controlsReady.get(10, TimeUnit.SECONDS);
                Robot robot = new Robot();
                try {
                    robot.keyPress(KeyEvent.VK_W);
                    Thread.sleep(scene.inputMillis);
                } finally { robot.keyRelease(KeyEvent.VK_W); }
            }
            Thread.sleep(scene.observationMillis);
            client.execute(() -> send("verify"));
            JsonObject result = scene.result.get(30, TimeUnit.SECONDS);
            JsonObject measured = new JsonObject();
            measured.addProperty("case", scene.id);
            measured.add("result", result);
            emit("MAGLEV_TEST_CLIENT_RESULT", measured);
            Thread.sleep(1000);
            JsonObject done = new JsonObject();
            done.addProperty("case", scene.id);
            emit("MAGLEV_TEST_RECORDING_DONE", done);
        } catch (Throwable error) { fail(client, error); }
    }

    private static void send(String type) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        var buffer = PacketByteBufs.create();
        buffer.writeUtf(MaglevTestMod.JSON.toJson(message));
        ClientPlayNetworking.send(MaglevTestMod.CONTROL, buffer);
    }

    private static void emit(String marker, JsonObject event) {
        System.out.println(marker + " " + MaglevTestMod.JSON.toJson(event));
    }

    private void fail(Minecraft client, Throwable error) {
        if (!failed.compareAndSet(false, true)) return;
        status = "FAIL - " + error;
        JsonObject failure = new JsonObject();
        failure.addProperty("error", error.toString());
        if (active != null) failure.addProperty("case", active.id);
        emit("MAGLEV_TEST_CLIENT_FATAL", failure);
        worker.shutdownNow();
        client.execute(client::stop);
    }
}
