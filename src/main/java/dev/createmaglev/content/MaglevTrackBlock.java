package dev.createmaglev.content;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.content.trains.track.TrackMaterial;

public final class MaglevTrackBlock extends TrackBlock {
    public MaglevTrackBlock(Properties properties, TrackMaterial material) {
        super(properties, material);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (!state.getValue(HAS_BE))
            return null;
        return MaglevContent.TRACK_BLOCK_ENTITY.create(pos, state);
    }

    @Override
    public BlockEntityType<? extends TrackBlockEntity> getBlockEntityType() {
        return MaglevContent.TRACK_BLOCK_ENTITY;
    }
}
