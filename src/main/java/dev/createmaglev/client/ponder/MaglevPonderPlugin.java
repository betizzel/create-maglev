package dev.createmaglev.client.ponder;

import net.minecraft.resources.ResourceLocation;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;

public final class MaglevPonderPlugin implements PonderPlugin {
    private static final ResourceLocation TRACTION_POWER = id("traction_power");
    private static final ResourceLocation MAGLEV = id("maglev");
    private static final ResourceLocation BOGEYS = id("bogeys");

    private static final ResourceLocation SUBSTATION = id("traction_substation");
    private static final ResourceLocation ELECTRIC_BOGEY = id("electric_bogey");
    private static final ResourceLocation BULLET_BOGEY = id("bullet_bogey");
    private static final ResourceLocation MAGLEV_BOGEY = id("maglev_bogey");
    private static final ResourceLocation REINFORCED_TRACK = id("reinforced_track");
    private static final ResourceLocation MAGLEV_TRACK = id("maglev_track");
    private static final ResourceLocation LEVITATION_MODULE = id("levitation_module");
    private static final ResourceLocation UNENERGIZED_LEVITATION_MODULE = id("unenergized_levitation_module");

    @Override
    public String getModId() {
        return "create_maglev";
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // Scenes attach only to this addon's items; other mods' items keep their own Ponder lists.
        // Registration order is each item's scene order: bogeys open on their own scene before supply.
        helper.forComponents(ELECTRIC_BOGEY, BULLET_BOGEY, REINFORCED_TRACK)
                .addStoryBoard("electric_bullet_bogeys", MaglevPonderScenes::electricAndBulletBogeys, BOGEYS);
        helper.forComponents(SUBSTATION, ELECTRIC_BOGEY, BULLET_BOGEY)
                .addStoryBoard("traction_substation", MaglevPonderScenes::tractionSubstation, TRACTION_POWER);
        helper.forComponents(MAGLEV_BOGEY, MAGLEV_TRACK, LEVITATION_MODULE, UNENERGIZED_LEVITATION_MODULE)
                .addStoryBoard("maglev", MaglevPonderScenes::maglev, MAGLEV);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TRACTION_POWER)
                .title("Traction Power")
                .description("Learn how substations supply electric and maglev trains.")
                .addToIndex()
                .icon(SUBSTATION)
                .register();
        helper.registerTag(MAGLEV)
                .title("Maglev Guideway")
                .description("See how the dedicated guideway separates levitation from propulsion.")
                .addToIndex()
                .icon(MAGLEV_TRACK)
                .register();
        helper.registerTag(BOGEYS)
                .title("Electric and Bullet Bogeys")
                .description("Compare standard and reinforced track speed limits.")
                .addToIndex()
                .icon(BULLET_BOGEY)
                .register();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("create_maglev", path);
    }
}
