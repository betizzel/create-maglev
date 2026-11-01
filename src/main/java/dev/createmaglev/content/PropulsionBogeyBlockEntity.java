package dev.createmaglev.content;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class PropulsionBogeyBlockEntity extends AbstractBogeyBlockEntity {
    public PropulsionBogeyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        if (getBlockState().getBlock() instanceof PropulsionBogeyBlock bogey)
            return bogey.defaultStyle();
        throw new IllegalStateException("Propulsion bogey entity has an unexpected block state");
    }
}
