package dev.createmaglev.mixin;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import dev.createmaglev.train.TrainTraction;
import dev.createmaglev.train.TractionState;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin implements TractionState {
    @Unique private TrainTraction create_maglev$traction;

    @Override
    public TrainTraction maglev$traction() {
        if (create_maglev$traction == null) create_maglev$traction = new TrainTraction((Train) (Object) this);
        return create_maglev$traction;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void create_maglev$power(Level level, CallbackInfo ci) {
        maglev$traction().tick(level);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Train;tickPassiveSlowdown()V", shift = At.Shift.AFTER))
    private void create_maglev$brakeBeforeMovement(Level level, CallbackInfo ci) {
        maglev$traction().enforceSafety();
    }

    @Inject(method = "approachTargetSpeed", at = @At("HEAD"), cancellable = true)
    private void create_maglev$constrainControl(float accelerationModifier, CallbackInfo ci) {
        TrainTraction traction = maglev$traction();
        if (!traction.managed() || !traction.authoritative()) return;
        traction.constrainTarget();
        // Only the movement hook brakes, so manual and scheduled controllers cannot add
        // propulsion or apply the same emergency brake several times in one server tick.
        if (!traction.powered()) ci.cancel();
    }

    @Inject(method = "burnFuel", at = @At("HEAD"), cancellable = true)
    private void create_maglev$electricNotCoal(CallbackInfo ci) {
        if (maglev$traction().managed()) ci.cancel();
    }

    @Inject(method = "maxSpeed", at = @At("HEAD"), cancellable = true)
    private void create_maglev$topSpeed(CallbackInfoReturnable<Float> cir) {
        TrainTraction traction = maglev$traction();
        // Native controls need the rated capability to express signed driving intent.
        // Route and power constraints are enforced separately before actual movement.
        if (traction.managed()) cir.setReturnValue((float) traction.tier().topSpeed());
    }

    @Inject(method = "maxTurnSpeed", at = @At("HEAD"), cancellable = true)
    private void create_maglev$curveSpeed(CallbackInfoReturnable<Float> cir) {
        if (maglev$traction().managed()) cir.setReturnValue(1.0f);
    }

    @Inject(method = "acceleration", at = @At("HEAD"), cancellable = true)
    private void create_maglev$acceleration(CallbackInfoReturnable<Float> cir) {
        TrainTraction traction = maglev$traction();
        if (traction.managed()) cir.setReturnValue(traction.tier().acceleration());
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void create_maglev$save(DimensionPalette dimensions, CallbackInfoReturnable<CompoundTag> cir) {
        maglev$traction().write(cir.getReturnValue());
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void create_maglev$restore(CompoundTag tag, Map<UUID, TrackGraph> graphs, DimensionPalette dimensions, CallbackInfoReturnable<Train> cir) {
        ((TractionState) cir.getReturnValue()).maglev$traction().read(tag);
    }
}
