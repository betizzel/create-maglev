package dev.createmaglev.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSounds;
import dev.createmaglev.train.TractionState;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TrainTraction;
import javax.annotation.Nullable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Create gives every moving consist a minecart-style rolling loop, a seated wheel loop, steam chuffs and an
 * arrival steam hiss. Electric traction has no steam engine, and a levitating maglev has no wheels on rails.
 */
@Mixin(value = CarriageSounds.class, remap = false)
public abstract class CarriageSoundsMixin {
    @Shadow
    CarriageContraptionEntity entity;

    @ModifyArg(method = "tick", index = 2, at = @At(value = "INVOKE", remap = true,
            target = "Lcom/simibubi/create/content/trains/entity/CarriageSounds;playIfMissing(Lnet/minecraft/client/Minecraft;Lcom/simibubi/create/content/trains/entity/CarriageSounds$LoopingSound;Lnet/minecraft/sounds/SoundEvent;)Lcom/simibubi/create/content/trains/entity/CarriageSounds$LoopingSound;"))
    private SoundEvent create_maglev$silenceMaglevWheelLoops(SoundEvent sound) {
        boolean wheelLoop = sound == AllSoundEvents.TRAIN.getMainEvent() || sound == AllSoundEvents.TRAIN3.getMainEvent();
        TrainTraction traction = create_maglev$traction();
        return wheelLoop && traction != null && traction.tier() == TrainTier.MAGLEV ? SoundEvents.EMPTY : sound;
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE", remap = true,
            target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V"))
    private boolean create_maglev$noSteamChuffs(AllSoundEvents.SoundEntry sound, Level level, Vec3 position, float volume,
                                                float pitch, boolean fade) {
        return sound != AllSoundEvents.STEAM || !create_maglev$electricTraction();
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private boolean create_maglev$noArrivalSteamHiss(Level level, double x, double y, double z, SoundEvent sound,
                                                     SoundSource source, float volume, float pitch, boolean delayed) {
        return sound != SoundEvents.LAVA_EXTINGUISH || !create_maglev$electricTraction();
    }

    private boolean create_maglev$electricTraction() {
        TrainTraction traction = create_maglev$traction();
        return traction != null && traction.managed();
    }

    @Nullable
    private TrainTraction create_maglev$traction() {
        Carriage carriage = entity == null ? null : entity.getCarriage();
        return carriage == null || carriage.train == null ? null : ((TractionState) carriage.train).maglev$traction();
    }
}
