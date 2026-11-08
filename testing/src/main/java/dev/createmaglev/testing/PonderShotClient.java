package dev.createmaglev.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import com.mojang.blaze3d.platform.NativeImage;
import com.simibubi.create.AllItems;
import dev.createmaglev.power.TractionPower;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

final class PonderShotClient {
    private static final int[] CAPTURE_TICKS = {45, 160, 280, 500};
    /** Items whose first Ponder scene is the matching entry of SCENE_PATHS. */
    private static final String[] COMPONENTS = {
            "traction_substation", "maglev_bogey", "electric_bogey"
    };
    private static final String[] SCENE_PATHS = {
            "traction_substation", "maglev", "electric_bullet_bogeys"
    };

    private final Path directory;
    private final List<JsonObject> captures = new ArrayList<>();
    private int joinedTicks;
    private int sceneIndex;
    private int captureIndex;
    private int warmupTicks;
    private PonderUI activeUi;
    private boolean indexed;
    private boolean stopped;
    private BlockPos gogglesTarget;
    private int gogglesTicks;

    private PonderShotClient(Path directory) {
        this.directory = directory;
    }

    static boolean registerIfRequested() {
        String value = System.getProperty("createmaglev.tests.ponderShots");
        if (value == null) return false;
        Path directory = Path.of(value);
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("Ponder screenshot output directory must be absolute: " + value);
        }
        directory = directory.normalize();
        try {
            Files.createDirectories(directory);
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("Ponder screenshot output directory must be empty: " + directory);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not prepare Ponder screenshot output directory " + directory, error);
        }
        ClientTickEvents.END_CLIENT_TICK.register(new PonderShotClient(directory)::tick);
        return true;
    }

    private void tick(Minecraft client) {
        if (stopped) return;
        try {
            if (client.player == null || client.level == null) return;
            if (sceneIndex >= COMPONENTS.length) {
                // Create's goggle overlay is purely client-side: helmet slot plus the block entity under the crosshair.
                if (gogglesTarget == null) {
                    placeGogglesTarget(client);
                    return;
                }
                if (++gogglesTicks < 20) return;
                if (!(client.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(gogglesTarget)) {
                    throw new IllegalStateException("The crosshair does not target the goggles substation at " + gogglesTarget);
                }
                captureFrame(client, "04-substation-goggles.png", "create_maglev:traction_substation_goggles", gogglesTicks);
                writeIndex();
                stopped = true;
                client.stop();
                return;
            }
            if (activeUi == null) {
                if (++joinedTicks < 40) return;
                if (!indexed) {
                    PonderIndex.reload();
                    indexed = true;
                }
                ResourceLocation component = new ResourceLocation("create_maglev", COMPONENTS[sceneIndex]);
                activeUi = PonderUI.of(component);
                client.setScreen(activeUi);
                warmupTicks = 10;
                captureIndex = 0;
                return;
            }

            if (client.screen != activeUi) {
                throw new IllegalStateException("Ponder screen closed before its captures were complete");
            }
            if (warmupTicks > 0) {
                warmupTicks--;
                return;
            }

            PonderScene scene = activeUi.getActiveScene();
            ResourceLocation expected = new ResourceLocation("create_maglev", SCENE_PATHS[sceneIndex]);
            if (scene == null || !expected.equals(scene.getId())) {
                throw new IllegalStateException("Ponder opened " + (scene == null ? "no scene" : scene.getId())
                        + " for " + COMPONENTS[sceneIndex] + " instead of " + expected);
            }
            if (captureIndex < CAPTURE_TICKS.length && scene.getCurrentTime() >= CAPTURE_TICKS[captureIndex]) {
                capture(client, scene);
                captureIndex++;
            }
            if (captureIndex < CAPTURE_TICKS.length) return;

            client.setScreen(null);
            activeUi = null;
            sceneIndex++;
            if (sceneIndex < COMPONENTS.length) {
                joinedTicks = 30;
            }
        } catch (Throwable error) {
            fail(client, error);
        }
    }

    private void capture(Minecraft client, PonderScene scene) throws IOException {
        String filename = String.format("%02d-%s-tick-%05d.png", sceneIndex + 1,
                SCENE_PATHS[sceneIndex], scene.getCurrentTime());
        captureFrame(client, filename, scene.getId().toString(), scene.getCurrentTime());
    }

    /** Client-side substation two blocks ahead at eye level, viewed through Engineer's Goggles. */
    private void placeGogglesTarget(Minecraft client) {
        LocalPlayer player = client.player;
        player.setYRot(0);
        player.setXRot(0);
        gogglesTarget = BlockPos.containing(player.getEyePosition().add(0, 0, 2.5));
        client.level.setBlock(gogglesTarget, TractionPower.SUBSTATION.defaultBlockState(), Block.UPDATE_ALL);
        player.getInventory().armor.set(3, AllItems.GOGGLES.asStack());
    }

    private void captureFrame(Minecraft client, String filename, String sceneId, int tick) throws IOException {
        Path target = directory.resolve(filename);
        NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
        try {
            image.writeToFile(target);
        } finally {
            image.close();
        }
        JsonObject capture = new JsonObject();
        capture.addProperty("scene", sceneId);
        capture.addProperty("file", filename);
        capture.addProperty("tick", tick);
        captures.add(capture);
        System.out.println("MAGLEV_PONDER_SHOT " + capture);
    }

    private void writeIndex() throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("modid", "create_maglev");
        index.addProperty("complete", true);
        JsonArray screenshots = new JsonArray();
        captures.forEach(screenshots::add);
        index.add("screenshots", screenshots);
        Files.writeString(directory.resolve("index.json"), MaglevTestMod.JSON.toJson(index) + "\n",
                StandardCharsets.UTF_8);
    }

    private void fail(Minecraft client, Throwable error) {
        stopped = true;
        try {
            Files.writeString(directory.resolve("failure.txt"), error.toString() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            error.printStackTrace();
        }
        error.printStackTrace();
        client.stop();
    }
}
