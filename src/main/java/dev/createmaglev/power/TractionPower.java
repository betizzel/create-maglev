package dev.createmaglev.power;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
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
    public static final int SUPPLY_RADIUS = 64;
    private static final double SUPPLY_RADIUS_SQUARED = (double) SUPPLY_RADIUS * SUPPLY_RADIUS;
    private static final ResourceLocation SUBSTATION_ID = new ResourceLocation("create_maglev", "traction_substation");

    public static final Block SUBSTATION = new TractionSubstationBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.0F).requiresCorrectToolForDrops());
    public static final BlockEntityType<TractionSubstationBlockEntity> SUBSTATION_BLOCK_ENTITY =
            BlockEntityType.Builder.of(TractionSubstationBlockEntity::new, SUBSTATION).build(null);

    private static final Map<ServerLevel, Long2ObjectMap<Map<BlockPos, WeakReference<TractionSubstationBlockEntity>>>> LOADED =
            new WeakHashMap<>();
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
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            // Chunk promotion is still in progress here; do not query neighboring blocks.
            if (blockEntity instanceof TractionSubstationBlockEntity station) {
                track(station);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof TractionSubstationBlockEntity station) {
                untrack(station);
            }
        });

    }

    public static TractionSubstationBlockEntity find(ServerLevel level, Vec3 position) {
        Long2ObjectMap<Map<BlockPos, WeakReference<TractionSubstationBlockEntity>>> chunks = LOADED.get(level);
        if (chunks == null) {
            return null;
        }

        int minChunkX = ((int) Math.floor(position.x - SUPPLY_RADIUS)) >> 4;
        int maxChunkX = ((int) Math.floor(position.x + SUPPLY_RADIUS)) >> 4;
        int minChunkZ = ((int) Math.floor(position.z - SUPPLY_RADIUS)) >> 4;
        int maxChunkZ = ((int) Math.floor(position.z + SUPPLY_RADIUS)) >> 4;
        TractionSubstationBlockEntity nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = chunkKey(chunkX, chunkZ);
                Map<BlockPos, WeakReference<TractionSubstationBlockEntity>> bucket = chunks.get(key);
                if (bucket == null) {
                    continue;
                }

                Iterator<Map.Entry<BlockPos, WeakReference<TractionSubstationBlockEntity>>> iterator = bucket.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, WeakReference<TractionSubstationBlockEntity>> entry = iterator.next();
                    BlockPos blockPos = entry.getKey();
                    TractionSubstationBlockEntity station = entry.getValue().get();
                    if (station == null || station.isRemoved() || station.getLevel() != level
                            || !station.getBlockPos().equals(blockPos) || !level.hasChunkAt(blockPos)
                            || level.getBlockState(blockPos).getBlock() != SUBSTATION) {
                        iterator.remove();
                        continue;
                    }

                    if (!station.canSupply()) {
                        continue;
                    }
                    double dx = blockPos.getX() + 0.5D - position.x;
                    double dy = blockPos.getY() + 0.5D - position.y;
                    double dz = blockPos.getZ() + 0.5D - position.z;
                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared > SUPPLY_RADIUS_SQUARED) {
                        continue;
                    }

                    int distanceOrder = Double.compare(distanceSquared, nearestDistanceSquared);
                    if (nearest == null || distanceOrder < 0
                            || (distanceOrder == 0 && comparePositions(blockPos, nearest.getBlockPos()) < 0)) {
                        nearest = station;
                        nearestDistanceSquared = distanceSquared;
                    }
                }

                if (bucket.isEmpty()) {
                    chunks.remove(key);
                }
            }
        }

        if (chunks.isEmpty()) {
            LOADED.remove(level);
        }
        return nearest;
    }

    static void track(TractionSubstationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level) || station.isRemoved()) {
            return;
        }
        Long2ObjectMap<Map<BlockPos, WeakReference<TractionSubstationBlockEntity>>> chunks =
                LOADED.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>());
        long key = chunkKey(station.getBlockPos().getX() >> 4, station.getBlockPos().getZ() >> 4);
        chunks.computeIfAbsent(key, ignored -> new HashMap<>())
                .put(station.getBlockPos(), new WeakReference<>(station));
    }

    static void untrack(TractionSubstationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Long2ObjectMap<Map<BlockPos, WeakReference<TractionSubstationBlockEntity>>> chunks = LOADED.get(level);
        if (chunks == null) {
            return;
        }
        BlockPos blockPos = station.getBlockPos();
        long key = chunkKey(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        Map<BlockPos, WeakReference<TractionSubstationBlockEntity>> bucket = chunks.get(key);
        if (bucket != null) {
            WeakReference<TractionSubstationBlockEntity> current = bucket.get(blockPos);
            if (current != null && (current.get() == null || current.get() == station)) {
                bucket.remove(blockPos);
            }
            if (bucket.isEmpty()) {
                chunks.remove(key);
            }
        }
        if (chunks.isEmpty()) {
            LOADED.remove(level);
        }
    }

    private static int comparePositions(BlockPos first, BlockPos second) {
        int order = Integer.compare(first.getX(), second.getX());
        if (order == 0) {
            order = Integer.compare(first.getY(), second.getY());
        }
        if (order == 0) {
            order = Integer.compare(first.getZ(), second.getZ());
        }
        return order;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    }
}
