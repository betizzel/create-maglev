package dev.createmaglev.content;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class MaglevSounds {
    private static final ResourceLocation MAGLEV_HUM_ID = new ResourceLocation(MaglevContent.MOD_ID, "maglev_hum");
    private static SoundEvent maglevHum;

    private MaglevSounds() {}

    static void register() {
        if (maglevHum != null)
            return;
        maglevHum = Registry.register(BuiltInRegistries.SOUND_EVENT, MAGLEV_HUM_ID,
            SoundEvent.createVariableRangeEvent(MAGLEV_HUM_ID));
    }

    static SoundEvent maglevHum() {
        if (maglevHum == null)
            throw new IllegalStateException("Maglev sounds are not registered");
        return maglevHum;
    }
}
