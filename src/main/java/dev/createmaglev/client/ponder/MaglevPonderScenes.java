package dev.createmaglev.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.createmaglev.power.TractionSubstationBlock;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlockEntity;
import org.antarcticgardens.cna.content.electricity.wire.WireType;

final class MaglevPonderScenes {
    private static final BlockPos GENERATOR_CONNECTOR = new BlockPos(5, 2, 3);
    private static final BlockPos SUBSTATION_CONNECTOR = new BlockPos(9, 2, 5);
    private static final BlockPos SUBSTATION = new BlockPos(9, 1, 5);
    private static final BlockPos BREAKER_LEVER = new BlockPos(9, 1, 6);
    private static final BlockPos COMPARATOR = new BlockPos(10, 1, 5);

    private MaglevPonderScenes() {
    }

    static void tractionSubstation(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("traction_substation", "Traction Substation");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.62f);
        scene.rotateCameraY(-25);
        scene.addKeyframe();
        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(8);
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        caption(scene, util, "New Age generators feed a traction substation through electrical connectors and copper wire.",
                util.vector().centerOf(7, 2, 4), 110);
        scene.world().modifyBlockEntity(GENERATOR_CONNECTOR, ElectricalConnectorBlockEntity.class, connector -> {
            ElectricalConnectorBlockEntity receiver = (ElectricalConnectorBlockEntity) connector.getLevel()
                    .getBlockEntity(SUBSTATION_CONNECTOR);
            connector.connect(receiver, WireType.COPPER);
        });
        scene.world().modifyKineticSpeed(util.select().fromTo(2, 1, 3, 4, 1, 3), speed -> 32f);

        scene.overlay().chaseBoundingBoxOutline(PonderPalette.BLUE, new Object(),
                new net.minecraft.world.phys.AABB(7, 1, 3, 13, 3, 8), 100);
        caption(scene, util, "Bogey pickups draw from nearby substations within the configured supply radius: 64 blocks by default.",
                util.vector().centerOf(12, 2, 5), 110);

        scene.world().toggleRedstonePower(util.select().position(BREAKER_LEVER));
        scene.world().cycleBlockProperty(SUBSTATION, TractionSubstationBlock.POWERED);
        caption(scene, util, "A redstone signal opens the breaker and isolates supply; stored energy remains in the substation.",
                util.vector().centerOf(SUBSTATION), 110);

        caption(scene, util, "A comparator reads stored charge. Engineer's Goggles show charge, breaker state, and output.",
                util.vector().centerOf(COMPARATOR), 110);


        caption(scene, util, "Each substation has a rated output per tick. Add substations for heavy or many trains; weak supply lowers acceleration and top speed instead of stopping the train.",
                util.vector().centerOf(SUBSTATION), 150);
        scene.markAsFinished();
    }

    static void maglev(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("maglev", "Maglev Guideway");
        scene.configureBasePlate(0, 0, 13);
        scene.scaleSceneView(0.67f);
        scene.rotateCameraY(-25);
        scene.addKeyframe();
        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(8);
        scene.world().showSection(util.select().fromTo(0, 1, 0, 2, 2, 9), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(5, 1, 0, 7, 2, 9), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(9, 1, 0, 11, 2, 9), Direction.DOWN);
        ElementLink<WorldSectionElement> carriage = scene.world().showIndependentSection(
                util.select().fromTo(4, 3, 2, 8, 5, 7), Direction.DOWN);
        scene.idle(20);

        caption(scene, util, "Maglev bogeys run only on the dedicated Maglev Guideway, not ordinary Create track.",
                util.vector().centerOf(6, 1, 5), 110);

        scene.overlay().showBigLine(PonderPalette.GREEN, new Vec3(6.5, 2.35, 2.5), new Vec3(6.5, 2.35, 7.5), 95);
        caption(scene, util, "Levitation and propulsion are separate: magnetic lift raises the carriage, while a linear motor along the guideway drives it forward.",
                util.vector().centerOf(6, 2, 5), 110);

        scene.world().moveSection(carriage, new Vec3(0, 0.25, 0), 20);
        scene.idle(20);
        caption(scene, util, "With traction power available, the whole carriage rises 0.25 blocks above the guideway.",
                util.vector().centerOf(6, 4, 5), 110);

        scene.world().cycleBlockProperty(SUBSTATION, TractionSubstationBlock.POWERED);
        scene.world().moveSection(carriage, new Vec3(0, -0.25, 0), 20);
        scene.idle(40);
        caption(scene, util, "When power is lost, the train brakes and the carriage settles back onto the guideway.",
                util.vector().centerOf(SUBSTATION), 120);

        scene.markAsFinished();
    }

    static void electricAndBulletBogeys(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("electric_bullet_bogeys", "Electric and Bullet Bogeys");
        scene.configureBasePlate(0, 0, 16);
        scene.scaleSceneView(0.62f);
        scene.rotateCameraY(-25);
        scene.addKeyframe();
        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(8);
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);

        caption(scene, util, "Electric bogeys run on ordinary Create track when their pickups receive substation power.",
                util.vector().centerOf(3, 2, 3), 110);

        caption(scene, util, "Bullet bogeys reach their higher speed only on Reinforced Standard Track.",
                util.vector().centerOf(3, 2, 6), 110);

        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().fromTo(9, 1, 1, 14, 1, 4), 100);
        caption(scene, util, "A bogey's top speed also depends on curve radius; a gentle, wide curve permits faster travel than a tight bend.",
                util.vector().centerOf(12, 1, 3), 110);

        caption(scene, util, "Electric bogeys use standard track; reserve reinforced track for bullet bogeys and slow down for tighter curves.",
                util.vector().centerOf(11, 1, 7), 180);
        scene.markAsFinished();
    }

    private static void caption(CreateSceneBuilder scene, SceneBuildingUtil util, String text, Vec3 point, int duration) {
        scene.addKeyframe();
        scene.overlay().showText(duration)
                .text(text)
                .pointAt(point)
                .attachKeyFrame();
        scene.idle(duration);
    }
}
