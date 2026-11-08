package dev.createmaglev.mixin;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TractionState;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class CarriageLevitationMixin extends OrientedContraptionEntity implements LevitationData {
    @Unique private static final EntityDataAccessor<Float> CREATE_MAGLEV_LIFT = SynchedEntityData.defineId(CarriageContraptionEntity.class, EntityDataSerializers.FLOAT);

    protected CarriageLevitationMixin(EntityType<?> type, Level level) { super(type, level); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void create_maglev$defineLift(EntityType<?> type, Level level, CallbackInfo ci) { entityData.define(CREATE_MAGLEV_LIFT, 0f); }

    @Override
    public float maglev$lift() { return entityData.get(CREATE_MAGLEV_LIFT); }

    @Override
    public void maglev$lift(float lift) {
        if (maglev$lift() != lift) entityData.set(CREATE_MAGLEV_LIFT, lift);
    }

    @Inject(method = "control", at = @At("RETURN"))
    private void create_maglev$explainPower(BlockPos controls, Collection<Integer> held, Player player, CallbackInfoReturnable<Boolean> cir) {
        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        if (player.level().isClientSide || entity.getCarriage() == null || player.tickCount % 20 != 0) return;
        var traction = ((TractionState) entity.getCarriage().train).maglev$traction();
        if (traction.managed() && !traction.status().equals("powered")) {
            player.displayClientMessage(traction.statusMessage(), true);
        }
    }
}
