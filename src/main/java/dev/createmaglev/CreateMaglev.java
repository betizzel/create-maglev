package dev.createmaglev;

import dev.createmaglev.advancement.MaglevAdvancements;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.power.TractionPower;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CreateMaglev implements ModInitializer {
    public static final String ID = "create_maglev";
    public static final Logger LOGGER = LoggerFactory.getLogger("Create Maglev");

    public static ResourceLocation id(String path) { return new ResourceLocation(ID, path); }

    @Override
    public void onInitialize() {
        MaglevConfig.register();
        MaglevContent.register();
        TractionPower.register();
        MaglevAdvancements.register();
        LOGGER.info("Create Maglev: electric, high-speed rail and magnetic-levitation traction registered");
    }
}
