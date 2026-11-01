package dev.createmaglev.content;

import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.AllTags.AllItemTags;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackMaterialFactory;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackRenderer;
import com.simibubi.create.content.trains.track.TrackVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.createmaglev.train.TrainTier;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

public final class MaglevContent {
    public static final String MOD_ID = "create_maglev";
    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public static final TrackMaterial.TrackType MAGLEV_TYPE = new TrackMaterial.TrackType(
        new ResourceLocation(MOD_ID, "maglev"), MaglevTrackBlock::new);
    public static final TrackMaterial REINFORCED_MATERIAL;
    public static final TrackMaterial MAGLEV_MATERIAL;

    private static BlockEntry<TrackBlock> REINFORCED_TRACK_ENTRY;
    private static BlockEntry<TrackBlock> MAGLEV_TRACK_ENTRY;
    private static final BlockEntry<PropulsionBogeyBlock> ELECTRIC_BOGEY_ENTRY;
    private static final BlockEntry<PropulsionBogeyBlock> BULLET_BOGEY_ENTRY;
    private static final BlockEntry<PropulsionBogeyBlock> MAGLEV_BOGEY_ENTRY;
    private static final BlockEntityEntry<PropulsionBogeyBlockEntity> BOGEY_ENTRY;
    private static final BlockEntityEntry<TrackBlockEntity> TRACK_BLOCK_ENTITY_ENTRY;

    private static final ItemEntry<Item> STEEL_INGOT_ENTRY;
    private static final ItemEntry<Item> STEEL_SHEET_ENTRY;
    private static final ItemEntry<Item> LAMINATED_CORE_ENTRY;
    private static final ItemEntry<Item> WOUND_COIL_ENTRY;
    private static final ItemEntry<Item> TRACTION_MOTOR_ENTRY;
    private static final ItemEntry<Item> CONTROL_UNIT_ENTRY;
    private static final ItemEntry<Item> LEVITATION_MODULE_ENTRY;
    private static final ItemEntry<Item> UNENERGIZED_LEVITATION_MODULE_ENTRY;
    private static final ItemEntry<Item> INCOMPLETE_WOUND_COIL_ENTRY;
    private static final ItemEntry<Item> INCOMPLETE_TRACTION_MOTOR_ENTRY;
    private static final ItemEntry<Item> INCOMPLETE_ELECTRIC_BOGEY_ENTRY;
    private static final ItemEntry<Item> INCOMPLETE_BULLET_BOGEY_ENTRY;
    private static final ItemEntry<Item> INCOMPLETE_MAGLEV_BOGEY_ENTRY;

    public static TrackBlock REINFORCED_TRACK;
    public static TrackBlock MAGLEV_TRACK;
    public static PropulsionBogeyBlock ELECTRIC_BOGEY;
    public static PropulsionBogeyBlock BULLET_BOGEY;
    public static PropulsionBogeyBlock MAGLEV_BOGEY;
    public static BlockEntityType<PropulsionBogeyBlockEntity> BOGEY_BLOCK_ENTITY;
    public static BlockEntityType<TrackBlockEntity> TRACK_BLOCK_ENTITY;

    public static Item STEEL_INGOT;
    public static Item STEEL_SHEET;
    public static Item LAMINATED_CORE;
    public static Item WOUND_COIL;
    public static Item TRACTION_MOTOR;
    public static Item CONTROL_UNIT;
    public static Item LEVITATION_MODULE;
    public static Item UNENERGIZED_LEVITATION_MODULE;
    public static Item INCOMPLETE_WOUND_COIL;
    public static Item INCOMPLETE_TRACTION_MOTOR;
    public static Item INCOMPLETE_ELECTRIC_BOGEY;
    public static Item INCOMPLETE_BULLET_BOGEY;
    public static Item INCOMPLETE_MAGLEV_BOGEY;

    private static boolean registered;

    static {
        REINFORCED_MATERIAL = TrackMaterialFactory.make(new ResourceLocation(MOD_ID, "reinforced"))
            .lang("Reinforced Steel")
            .block(NonNullSupplier.lazy(() -> REINFORCED_TRACK_ENTRY))
            .particle(new ResourceLocation("minecraft", "block/iron_block"))
            .sleeper(Items.OAK_PLANKS)
            .rails(Items.IRON_INGOT)
            .trackType(TrackMaterial.TrackType.STANDARD)
            .customBlockFactory(MaglevTrackBlock::new)
            .standardModels()
            .build();
        MAGLEV_MATERIAL = TrackMaterialFactory.make(new ResourceLocation(MOD_ID, "maglev"))
            .lang("Maglev Guideway")
            .block(NonNullSupplier.lazy(() -> MAGLEV_TRACK_ENTRY))
            .particle(new ResourceLocation(MOD_ID, "block/maglev_track"))
            .sleeper(Items.IRON_INGOT)
            .rails(Items.IRON_INGOT)
            .trackType(MAGLEV_TYPE)
            .standardModels()
            .build();

        ELECTRIC_BOGEY_ENTRY = REGISTRATE.block("electric_bogey", p -> new PropulsionBogeyBlock(p, TrainTier.ELECTRIC))
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.sound(SoundType.NETHERITE_BLOCK).noOcclusion())
            .transform(TagGen.pickaxeOnly())
            .blockstate((c, p) -> com.simibubi.create.foundation.data.BlockStateGen.horizontalAxisBlock(c, p,
                state -> p.models().getExistingFile(p.modLoc("block/electric_bogey"))))
            .loot((p, block) -> p.dropSelf(block))
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/electric_bogey")))
            .build()
            .register();
        BULLET_BOGEY_ENTRY = REGISTRATE.block("bullet_bogey", p -> new PropulsionBogeyBlock(p, TrainTier.BULLET))
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.sound(SoundType.NETHERITE_BLOCK).noOcclusion())
            .transform(TagGen.pickaxeOnly())
            .blockstate((c, p) -> com.simibubi.create.foundation.data.BlockStateGen.horizontalAxisBlock(c, p,
                state -> p.models().getExistingFile(p.modLoc("block/bullet_bogey"))))
            .loot((p, block) -> p.dropSelf(block))
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/bullet_bogey")))
            .build()
            .register();
        MAGLEV_BOGEY_ENTRY = REGISTRATE.block("maglev_bogey", p -> new PropulsionBogeyBlock(p, TrainTier.MAGLEV))
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.sound(SoundType.NETHERITE_BLOCK).noOcclusion())
            .transform(TagGen.pickaxeOnly())
            .blockstate((c, p) -> com.simibubi.create.foundation.data.BlockStateGen.horizontalAxisBlock(c, p,
                state -> p.models().getExistingFile(p.modLoc("block/maglev_bogey"))))
            .loot((p, block) -> p.dropSelf(block))
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/maglev_bogey")))
            .build()
            .register();

        REINFORCED_TRACK_ENTRY = REGISTRATE.block("reinforced_track", REINFORCED_MATERIAL::createBlock)
            .properties(p -> p.mapColor(MapColor.METAL).strength(0.8F).sound(SoundType.METAL).noOcclusion().forceSolidOn())
            .transform(TagGen.pickaxeOnly())
            .addLayer(() -> net.minecraft.client.renderer.RenderType::cutoutMipped)
            .tag(AllBlockTags.RELOCATION_NOT_SUPPORTED.tag)
            .tag(AllBlockTags.TRACKS.tag)
            .tag(AllBlockTags.GIRDABLE_TRACKS.tag)
            .loot((p, block) -> p.dropSelf(block))
            .item(TrackBlockItem::new)
            .tag(AllItemTags.TRACKS.tag)
            .model((c, p) -> p.generated(c, p.modLoc("item/reinforced_track")))
            .build()
            .register();
        MAGLEV_TRACK_ENTRY = REGISTRATE.block("maglev_track", MAGLEV_MATERIAL::createBlock)
            .properties(p -> p.mapColor(MapColor.METAL).strength(0.8F).sound(SoundType.METAL).noOcclusion().forceSolidOn())
            .transform(TagGen.pickaxeOnly())
            .addLayer(() -> net.minecraft.client.renderer.RenderType::cutoutMipped)
            .tag(AllBlockTags.RELOCATION_NOT_SUPPORTED.tag)
            .tag(AllBlockTags.TRACKS.tag)
            .tag(AllBlockTags.GIRDABLE_TRACKS.tag)
            .loot((p, block) -> p.dropSelf(block))
            .item(TrackBlockItem::new)
            .tag(AllItemTags.TRACKS.tag)
            .model((c, p) -> p.generated(c, p.modLoc("item/maglev_track")))
            .build()
            .register();

        BOGEY_ENTRY = REGISTRATE.blockEntity("propulsion_bogey", PropulsionBogeyBlockEntity::new)
            .validBlocks(ELECTRIC_BOGEY_ENTRY, BULLET_BOGEY_ENTRY, MAGLEV_BOGEY_ENTRY)
            .renderer(() -> com.simibubi.create.content.trains.bogey.BogeyBlockEntityRenderer::new)
            .register();
        TRACK_BLOCK_ENTITY_ENTRY = REGISTRATE.blockEntity("track", TrackBlockEntity::new)
            .validBlocks(REINFORCED_TRACK_ENTRY, MAGLEV_TRACK_ENTRY)
            .renderer(() -> TrackRenderer::new)
            .register();

        STEEL_INGOT_ENTRY = item("steel_ingot");
        STEEL_SHEET_ENTRY = item("steel_sheet");
        LAMINATED_CORE_ENTRY = item("laminated_core");
        WOUND_COIL_ENTRY = item("wound_coil");
        TRACTION_MOTOR_ENTRY = item("traction_motor");
        CONTROL_UNIT_ENTRY = item("control_unit");
        LEVITATION_MODULE_ENTRY = item("levitation_module");
        UNENERGIZED_LEVITATION_MODULE_ENTRY = item("unenergized_levitation_module");
        INCOMPLETE_WOUND_COIL_ENTRY = incomplete("incomplete_wound_coil");
        INCOMPLETE_TRACTION_MOTOR_ENTRY = incomplete("incomplete_traction_motor");
        INCOMPLETE_ELECTRIC_BOGEY_ENTRY = incomplete("incomplete_electric_bogey");
        INCOMPLETE_BULLET_BOGEY_ENTRY = incomplete("incomplete_bullet_bogey");
        INCOMPLETE_MAGLEV_BOGEY_ENTRY = incomplete("incomplete_maglev_bogey");
    }

    private MaglevContent() {}

    private static ItemEntry<Item> item(String name) {
        return REGISTRATE.item(name, Item::new).register();
    }

    private static ItemEntry<Item> incomplete(String name) {
        return REGISTRATE.item(name, properties -> new Item(properties.stacksTo(1))).register();
    }

    public static void register() {
        if (registered)
            return;
        REGISTRATE.register();
        ELECTRIC_BOGEY = ELECTRIC_BOGEY_ENTRY.get();
        BULLET_BOGEY = BULLET_BOGEY_ENTRY.get();
        MAGLEV_BOGEY = MAGLEV_BOGEY_ENTRY.get();
        REINFORCED_TRACK = REINFORCED_TRACK_ENTRY.get();
        MAGLEV_TRACK = MAGLEV_TRACK_ENTRY.get();
        BOGEY_BLOCK_ENTITY = BOGEY_ENTRY.get();
        TRACK_BLOCK_ENTITY = TRACK_BLOCK_ENTITY_ENTRY.get();
        STEEL_INGOT = STEEL_INGOT_ENTRY.get();
        STEEL_SHEET = STEEL_SHEET_ENTRY.get();
        LAMINATED_CORE = LAMINATED_CORE_ENTRY.get();
        WOUND_COIL = WOUND_COIL_ENTRY.get();
        TRACTION_MOTOR = TRACTION_MOTOR_ENTRY.get();
        CONTROL_UNIT = CONTROL_UNIT_ENTRY.get();
        LEVITATION_MODULE = LEVITATION_MODULE_ENTRY.get();
        UNENERGIZED_LEVITATION_MODULE = UNENERGIZED_LEVITATION_MODULE_ENTRY.get();
        INCOMPLETE_WOUND_COIL = INCOMPLETE_WOUND_COIL_ENTRY.get();
        INCOMPLETE_TRACTION_MOTOR = INCOMPLETE_TRACTION_MOTOR_ENTRY.get();
        INCOMPLETE_ELECTRIC_BOGEY = INCOMPLETE_ELECTRIC_BOGEY_ENTRY.get();
        INCOMPLETE_BULLET_BOGEY = INCOMPLETE_BULLET_BOGEY_ENTRY.get();
        INCOMPLETE_MAGLEV_BOGEY = INCOMPLETE_MAGLEV_BOGEY_ENTRY.get();
        MaglevBogeyStyles.register();
        registerCreativeTab();
        registered = true;
    }

    private static void registerCreativeTab() {
        ResourceLocation id = new ResourceLocation(MOD_ID, "maglev");
        CreativeModeTab tab = FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.create_maglev"))
            .icon(() -> new net.minecraft.world.item.ItemStack(ELECTRIC_BOGEY))
            .displayItems((parameters, output) -> {
                output.accept(STEEL_INGOT);
                output.accept(STEEL_SHEET);
                output.accept(LAMINATED_CORE);
                output.accept(WOUND_COIL);
                output.accept(TRACTION_MOTOR);
                output.accept(CONTROL_UNIT);
                output.accept(UNENERGIZED_LEVITATION_MODULE);
                output.accept(LEVITATION_MODULE);
                output.accept(ELECTRIC_BOGEY);
                output.accept(BULLET_BOGEY);
                output.accept(MAGLEV_BOGEY);
                output.accept(REINFORCED_TRACK);
                output.accept(MAGLEV_TRACK);
                Item substation = BuiltInRegistries.ITEM.get(new ResourceLocation(MOD_ID, "traction_substation"));
                if (substation != Items.AIR)
                    output.accept(substation);
            })
            .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab);
    }

    public static PropulsionBogeyBlock bogeyFor(TrainTier tier) {
        return switch (tier) {
            case WORKSHOP -> throw new IllegalArgumentException("Workshop bogeys use Create's stock bogey blocks");
            case ELECTRIC -> require(ELECTRIC_BOGEY, tier);
            case BULLET -> require(BULLET_BOGEY, tier);
            case MAGLEV -> require(MAGLEV_BOGEY, tier);
        };
    }

    private static PropulsionBogeyBlock require(PropulsionBogeyBlock block, TrainTier tier) {
        if (block == null)
            throw new IllegalStateException("Maglev content is not registered for " + tier);
        return block;
    }
}
