package dev.createmaglev.content;

import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import dev.createmaglev.train.TrainTier;

public final class PropulsionBogeyBlock extends AbstractBogeyBlock<PropulsionBogeyBlockEntity> {
    private final TrainTier tier;

    public PropulsionBogeyBlock(Block.Properties properties, TrainTier tier) {
        super(properties, BogeySizes.SMALL);
        this.tier = tier;
    }

    public TrainTier tier() {
        return tier;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos railPos = context.getClickedPos().below();
        BlockState railState = context.getLevel().getBlockState(railPos);
        Direction.Axis axis = context.getHorizontalDirection().getAxis();
        if (railState.getBlock() instanceof ITrackBlock track) {
            var axes = track.getTrackAxes(context.getLevel(), railPos, railState);
            if (!axes.isEmpty()) {
                Vec3 direction = axes.get(0);
                axis = Math.abs(direction.x) > Math.abs(direction.z) ? Direction.Axis.X : Direction.Axis.Z;
            }
        }
        return defaultBlockState().setValue(AXIS, axis)
            .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).is(FluidTags.WATER));
    }

    public BogeyStyle defaultStyle() {
        return MaglevBogeyStyles.forTier(tier);
    }

    @Override
    public TrackMaterial.TrackType getTrackType(BogeyStyle style) {
        return tier == TrainTier.MAGLEV ? MaglevContent.MAGLEV_TYPE : TrackMaterial.TrackType.STANDARD;
    }

    @Override
    public double getWheelPointSpacing() {
        return 2;
    }

    @Override
    public double getWheelRadius() {
        return tier == TrainTier.MAGLEV ? 0.5 : 6.5 / 16d;
    }

    @Override
    protected Vec3 getConnectorAnchorOffset() {
        return new Vec3(0, 7 / 32f, 1);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return defaultStyle();
    }

    @Override
    public BlockState getMatchingBogey(Direction upDirection, boolean axisAlongFirst) {
        if (upDirection != Direction.UP)
            return null;
        return defaultBlockState().setValue(AXIS, axisAlongFirst ? Direction.Axis.X : Direction.Axis.Z);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(this);
    }

    @Override
    public Class<PropulsionBogeyBlockEntity> getBlockEntityClass() {
        return PropulsionBogeyBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PropulsionBogeyBlockEntity> getBlockEntityType() {
        return MaglevContent.BOGEY_BLOCK_ENTITY;
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        return new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, new ItemStack(this));
    }
}
