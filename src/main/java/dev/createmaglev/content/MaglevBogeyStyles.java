package dev.createmaglev.content;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.bogey.BogeyStyle.SizeRenderer;
import dev.createmaglev.client.content.MaglevRenderers;
import dev.createmaglev.train.TrainTier;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import java.util.function.Supplier;

public final class MaglevBogeyStyles {
    public static BogeyStyle ELECTRIC;
    public static BogeyStyle BULLET;
    public static BogeyStyle MAGLEV;

    private static boolean registered;

    private MaglevBogeyStyles() {}

    static void register() {
        MaglevSounds.register();
        if (registered)
            return;
        registered = true;
        ELECTRIC = make("electric", TrainTier.ELECTRIC,
            () -> () -> new SizeRenderer(new MaglevRenderers.ElectricRenderer(), MaglevRenderers.ElectricVisual::new),
            () -> AllSoundEvents.TRAIN2.getMainEvent(), ParticleTypes.CRIT, ParticleTypes.END_ROD);
        BULLET = make("bullet", TrainTier.BULLET,
            () -> () -> new SizeRenderer(new MaglevRenderers.BulletRenderer(), MaglevRenderers.BulletVisual::new),
            () -> AllSoundEvents.TRAIN2.getMainEvent(), ParticleTypes.CRIT, ParticleTypes.END_ROD);
        MAGLEV = make("maglev", TrainTier.MAGLEV,
            () -> () -> new SizeRenderer(new MaglevRenderers.MaglevRenderer(), MaglevRenderers.MaglevVisual::new),
            MaglevSounds::maglevHum, ParticleTypes.END_ROD, ParticleTypes.END_ROD);
    }

    private static BogeyStyle make(String path, TrainTier tier,
            Supplier<Supplier<? extends SizeRenderer>> renderer,
            Supplier<SoundEvent> soundEvent, ParticleOptions contactParticle, ParticleOptions smokeParticle) {
        ResourceLocation id = new ResourceLocation(MaglevContent.MOD_ID, path);
        return new BogeyStyle.Builder(id, new ResourceLocation(MaglevContent.MOD_ID, path))
            .displayName(Component.translatable("bogey.style.create_maglev." + path))
            .size(BogeySizes.SMALL, () -> MaglevContent.bogeyFor(tier), renderer)
            .soundEvent(soundEvent)
            .contactParticle(contactParticle)
            .smokeParticle(smokeParticle)
            .build();
    }

    public static BogeyStyle forTier(TrainTier tier) {
        return switch (tier) {
            case WORKSHOP -> throw new IllegalArgumentException("Workshop bogeys use Create's stock style");
            case ELECTRIC -> require(ELECTRIC, tier);
            case BULLET -> require(BULLET, tier);
            case MAGLEV -> require(MAGLEV, tier);
        };
    }

    private static BogeyStyle require(BogeyStyle style, TrainTier tier) {
        if (style == null)
            throw new IllegalStateException("Maglev bogey styles are not registered for " + tier);
        return style;
    }
}
