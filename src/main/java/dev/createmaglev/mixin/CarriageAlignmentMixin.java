package dev.createmaglev.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TractionState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Carriage.DimensionalCarriageEntity.class, remap = false)
public abstract class CarriageAlignmentMixin {
    @Inject(method = "alignEntity", at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;yaw:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void create_maglev$levitate(CarriageContraptionEntity entity, CallbackInfo ci) {
        Carriage carriage = entity.getCarriage();
        if (carriage == null) return;
        LevitationData data = (LevitationData) entity;
        if (!entity.level().isClientSide) data.maglev$lift(((TractionState) carriage.train).maglev$traction().lift());
        float lift = data.maglev$lift();
        if (lift == 0) return;
        TravellingPoint point = carriage.getLeadingPoint();
        if (point.edge == null || point.edge.getLength() == 0) return;
        entity.setPos(entity.position().add(point.edge.getNormal(carriage.train.graph, point.position / point.edge.getLength()).scale(lift)));
    }
}
