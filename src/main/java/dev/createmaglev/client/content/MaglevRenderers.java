package dev.createmaglev.client.content;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.bogey.BogeyRenderer;
import com.simibubi.create.content.trains.bogey.BogeyVisual;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import com.simibubi.create.content.trains.track.TrackModel;
import dev.createmaglev.content.MaglevContent;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import org.jetbrains.annotations.Nullable;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import com.simibubi.create.content.trains.bogey.BogeyBlockEntityVisual;
import com.simibubi.create.content.trains.track.TrackVisual;

@Environment(EnvType.CLIENT)
public final class MaglevRenderers {
    private static final PartialModel ELECTRIC_DETAIL = partial("electric_detail");
    private static final PartialModel BULLET_DETAIL = partial("bullet_detail");
    private static final PartialModel MAGLEV_DETAIL = partial("maglev_detail");
    private static boolean registered;

    private MaglevRenderers() {}

    private static PartialModel partial(String name) {
        return PartialModel.of(new ResourceLocation(MaglevContent.MOD_ID, "block/bogey/" + name));
    }

    public static void register() {
        if (registered)
            return;
        registered = true;
        CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
            new ResourceLocation(MaglevContent.MOD_ID, "reinforced_track"), TrackModel::new);
        CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
            new ResourceLocation(MaglevContent.MOD_ID, "maglev_track"), TrackModel::new);
        SimpleBlockEntityVisualizer.builder(MaglevContent.BOGEY_BLOCK_ENTITY)
            .factory(BogeyBlockEntityVisual::new).neverSkipVanillaRender().apply();
        SimpleBlockEntityVisualizer.builder(MaglevContent.TRACK_BLOCK_ENTITY)
            .factory(TrackVisual::new).apply();
        ItemTooltipCallback.EVENT.register(MaglevRenderers::addTooltip);
    }

    private static void addTooltip(ItemStack stack, TooltipFlag flag, List<net.minecraft.network.chat.Component> lines) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!MaglevContent.MOD_ID.equals(id.getNamespace()))
            return;
        String key = switch (id.getPath()) {
            case "electric_bogey" -> "tooltip.create_maglev.electric_bogey";
            case "bullet_bogey" -> "tooltip.create_maglev.bullet_bogey";
            case "maglev_bogey" -> "tooltip.create_maglev.maglev_bogey";
            case "reinforced_track" -> "tooltip.create_maglev.reinforced_track";
            case "maglev_track" -> "tooltip.create_maglev.maglev_track";
            case "traction_substation" -> "tooltip.create_maglev.traction_substation";
            case "steel_ingot" -> "tooltip.create_maglev.steel_ingot";
            case "laminated_core" -> "tooltip.create_maglev.laminated_core";
            case "steel_sheet" -> "tooltip.create_maglev.steel_sheet";
            case "wound_coil" -> "tooltip.create_maglev.wound_coil";
            case "traction_motor" -> "tooltip.create_maglev.traction_motor";
            case "control_unit" -> "tooltip.create_maglev.control_unit";
            case "levitation_module" -> "tooltip.create_maglev.levitation_module";
            case "unenergized_levitation_module" -> "tooltip.create_maglev.unenergized_levitation_module";
            default -> null;
        };
        if (key != null)
            lines.add(net.minecraft.network.chat.Component.translatable(key));
    }

    private static void render(PartialModel model, PoseStack poseStack, MultiBufferSource bufferSource, int light,
                               int overlay) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.cutoutMipped());
        CachedBuffers.partial(model, Blocks.AIR.defaultBlockState())
            .light(light)
            .overlay(overlay)
            .renderInto(poseStack, buffer);
    }

    public static final class ElectricRenderer extends StandardBogeyRenderer.Small {
        @Override
        public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                           MultiBufferSource bufferSource, int light, int overlay, boolean inContraption) {
            super.render(bogeyData, wheelAngle, partialTick, poseStack, bufferSource, light, overlay, inContraption);
            MaglevRenderers.render(ELECTRIC_DETAIL, poseStack, bufferSource, light, overlay);
        }
    }

    public static final class BulletRenderer extends StandardBogeyRenderer.Small {
        @Override
        public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                           MultiBufferSource bufferSource, int light, int overlay, boolean inContraption) {
            super.render(bogeyData, wheelAngle, partialTick, poseStack, bufferSource, light, overlay, inContraption);
            MaglevRenderers.render(BULLET_DETAIL, poseStack, bufferSource, light, overlay);
        }
    }

    public static final class MaglevRenderer implements BogeyRenderer {
        @Override
        public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                           MultiBufferSource bufferSource, int light, int overlay, boolean inContraption) {
            MaglevRenderers.render(MAGLEV_DETAIL, poseStack, bufferSource, light, overlay);
        }
    }

    private abstract static class StandardDetailVisual extends StandardBogeyVisual.Small {
        private final TransformedInstance detail;

        StandardDetailVisual(VisualizationContext ctx, float partialTick, boolean inContraption, PartialModel model) {
            super(ctx, partialTick, inContraption);
            detail = ctx.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
                .createInstance();
        }

        @Override
        public void update(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack) {
            super.update(bogeyData, wheelAngle, poseStack);
            detail.setTransform(poseStack).setChanged();
        }

        @Override
        public void hide() {
            super.hide();
            detail.setZeroTransform().setChanged();
        }

        @Override
        public void updateLight(int packedLight) {
            super.updateLight(packedLight);
            detail.light(packedLight).setChanged();
        }

        @Override
        public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
            super.collectCrumblingInstances(consumer);
            consumer.accept(detail);
        }

        @Override
        public void delete() {
            super.delete();
            detail.delete();
        }
    }

    public static final class ElectricVisual extends StandardDetailVisual {
        public ElectricVisual(VisualizationContext ctx, float partialTick, boolean inContraption) {
            super(ctx, partialTick, inContraption, ELECTRIC_DETAIL);
        }
    }

    public static final class BulletVisual extends StandardDetailVisual {
        public BulletVisual(VisualizationContext ctx, float partialTick, boolean inContraption) {
            super(ctx, partialTick, inContraption, BULLET_DETAIL);
        }
    }

    public static final class MaglevVisual implements BogeyVisual {
        private final TransformedInstance detail;

        public MaglevVisual(VisualizationContext ctx, float partialTick, boolean inContraption) {
            detail = ctx.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(MAGLEV_DETAIL))
                .createInstance();
        }

        @Override
        public void update(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack) {
            detail.setTransform(poseStack).setChanged();
        }

        @Override
        public void hide() {
            detail.setZeroTransform().setChanged();
        }

        @Override
        public void updateLight(int packedLight) {
            detail.light(packedLight).setChanged();
        }

        @Override
        public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
            consumer.accept(detail);
        }

        @Override
        public void delete() {
            detail.delete();
        }
    }
}
