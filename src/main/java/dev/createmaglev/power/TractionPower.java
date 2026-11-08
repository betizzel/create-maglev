package dev.createmaglev.power;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.esl.energy.EnergyStorage;

public final class TractionPower {
    private static final ResourceLocation SUBSTATION_ID = new ResourceLocation("create_maglev", "traction_substation");

    public static final Block SUBSTATION = new TractionSubstationBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.0F).requiresCorrectToolForDrops());
    public static final BlockEntityType<TractionSubstationBlockEntity> SUBSTATION_BLOCK_ENTITY =
            BlockEntityType.Builder.of(TractionSubstationBlockEntity::new, SUBSTATION).build(null);

    private static boolean energyProviderRegistered;

    private TractionPower() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, SUBSTATION_ID, SUBSTATION);
        Registry.register(BuiltInRegistries.ITEM, SUBSTATION_ID, new BlockItem(SUBSTATION, new Item.Properties()));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SUBSTATION_ID, SUBSTATION_BLOCK_ENTITY);
        // Fabric does not order common initializers by dependency. ESL creates its lookup
        // manager in its initializer, so install our provider before world loading instead.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!energyProviderRegistered) {
                EnergyStorage.registerForBlockEntity(
                        TractionSubstationBlockEntity::energyStorageFor, SUBSTATION_BLOCK_ENTITY);
                energyProviderRegistered = true;
            }
        });
        // Entries whose block vanished without a removal callback are dropped when their chunk next loads.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> SubstationNetwork.get(level).validate(chunk));
    }

    /** Enabled substations covering a position, nearest first, whether or not their chunks are loaded. */
    public static void collectSupplies(ServerLevel level, Vec3 position, List<SubstationNetwork.Substation> out) {
        SubstationNetwork.get(level).collect(position, out);
    }

    /** The nearest enabled substation covering a position, or null when the position has no supply. */
    @Nullable
    public static BlockPos nearestSupply(ServerLevel level, Vec3 position) {
        List<SubstationNetwork.Substation> supplies = new ArrayList<>();
        collectSupplies(level, position, supplies);
        return supplies.isEmpty() ? null : supplies.get(0).pos();
    }

    /** True when an enabled substation covering a position holds any charge. */
    public static boolean hasChargedSupply(ServerLevel level, Vec3 position) {
        return SubstationNetwork.get(level).hasChargedSupply(position);
    }

    /** Stored charge of the substation at a position, loaded or not; 0 when there is none. */
    public static long storedEnergy(ServerLevel level, BlockPos pos) {
        SubstationNetwork.Substation substation = SubstationNetwork.get(level).get(pos);
        return substation == null ? 0L : substation.energy();
    }
}
