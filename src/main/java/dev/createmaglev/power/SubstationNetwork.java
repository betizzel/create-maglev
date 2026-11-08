package dev.createmaglev.power;

import dev.createmaglev.config.MaglevConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * Every traction substation in one dimension, with its charge and breaker state, persisted with the world.
 * Create keeps simulating trains in unloaded chunks, so supply must not depend on loaded block entities;
 * queries here never touch the world and therefore never load or retain chunks.
 */
public final class SubstationNetwork extends SavedData {
    private static final String DATA_NAME = "create_maglev_substations";

    private final Long2ObjectOpenHashMap<Substation> byPosition = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<Substation>> byChunk = new Long2ObjectOpenHashMap<>();

    public static SubstationNetwork get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SubstationNetwork::load, SubstationNetwork::new, DATA_NAME);
    }

    private static SubstationNetwork load(CompoundTag tag) {
        SubstationNetwork network = new SubstationNetwork();
        ListTag entries = tag.getList("Substations", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            Substation substation = network.create(BlockPos.of(entry.getLong("Pos")));
            substation.energy = Math.max(0L, entry.getLong("Energy"));
            substation.breakerOpen = entry.getBoolean("BreakerOpen");
        }
        return network;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Substation substation : byPosition.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", substation.pos.asLong());
            entry.putLong("Energy", substation.energy);
            entry.putBoolean("BreakerOpen", substation.breakerOpen);
            entries.add(entry);
        }
        tag.put("Substations", entries);
        return tag;
    }

    @Nullable
    public Substation get(BlockPos pos) {
        return byPosition.get(pos.asLong());
    }

    /**
     * Binds a loaded block entity to its persistent entry. A missing entry is created from the block entity's own
     * saved charge (worlds from 1.0.x, or a substation moved by a contraption). Runs during chunk promotion, so it
     * must only use the block entity's own state.
     */
    Substation attach(TractionSubstationBlockEntity blockEntity, long savedEnergy, boolean breakerOpen) {
        Substation substation = get(blockEntity.getBlockPos());
        if (substation == null) {
            substation = create(blockEntity.getBlockPos().immutable());
            substation.energy = Math.max(0L, savedEnergy);
            setDirty();
        }
        substation.loaded = new WeakReference<>(blockEntity);
        substation.setBreakerOpen(breakerOpen);
        return substation;
    }

    void detach(TractionSubstationBlockEntity blockEntity) {
        Substation substation = get(blockEntity.getBlockPos());
        if (substation != null && substation.loaded != null && substation.loaded.get() == blockEntity) {
            substation.loaded = null;
        }
    }

    /** Explicit block-entity NBT applied to a live substation, e.g. a contraption placing it back down. */
    void restoreEnergy(TractionSubstationBlockEntity blockEntity, long energy) {
        Substation substation = get(blockEntity.getBlockPos());
        if (substation == null) {
            attach(blockEntity, energy, blockEntity.getBlockState().getValue(TractionSubstationBlock.POWERED));
        } else {
            substation.setEnergy(energy);
        }
    }

    void remove(BlockPos pos) {
        Substation substation = byPosition.remove(pos.asLong());
        if (substation == null) {
            return;
        }
        long chunk = chunkKey(pos);
        List<Substation> bucket = byChunk.get(chunk);
        if (bucket != null) {
            bucket.remove(substation);
            if (bucket.isEmpty()) {
                byChunk.remove(chunk);
            }
        }
        setDirty();
    }

    /** Drops entries whose block disappeared without a removal callback; reads only the loading chunk itself. */
    void validate(LevelChunk chunk) {
        List<Substation> bucket = byChunk.get(chunk.getPos().toLong());
        if (bucket == null) {
            return;
        }
        for (Substation substation : List.copyOf(bucket)) {
            if (!chunk.getBlockState(substation.pos).is(TractionPower.SUBSTATION)) {
                remove(substation.pos);
            }
        }
    }

    /** Enabled substations whose supply sphere contains the position, nearest first (ties by position). */
    public void collect(Vec3 position, List<Substation> out) {
        out.clear();
        anyEnabledInRange(position, substation -> {
            out.add(substation);
            return false;
        });
        if (out.size() > 1) {
            out.sort(Comparator.<Substation>comparingDouble(substation -> substation.distanceSquared(position))
                    .thenComparingLong(substation -> substation.pos.asLong()));
        }
    }

    /** True when an enabled substation covering the position currently holds any charge. */
    public boolean hasChargedSupply(Vec3 position) {
        return anyEnabledInRange(position, substation -> substation.energy > 0);
    }

    /** Visits enabled substations covering the position until the visitor returns true. */
    private boolean anyEnabledInRange(Vec3 position, Predicate<Substation> visitor) {
        double radius = MaglevConfig.supplyRadius();
        double radiusSquared = radius * radius;
        int minChunkX = floorChunk(position.x - radius);
        int maxChunkX = floorChunk(position.x + radius);
        int minChunkZ = floorChunk(position.z - radius);
        int maxChunkZ = floorChunk(position.z + radius);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<Substation> bucket = byChunk.get(ChunkPos.asLong(chunkX, chunkZ));
                if (bucket == null) {
                    continue;
                }
                for (Substation substation : bucket) {
                    if (!substation.breakerOpen && substation.distanceSquared(position) <= radiusSquared
                            && visitor.test(substation)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Substation create(BlockPos pos) {
        Substation substation = new Substation(this, pos);
        byPosition.put(pos.asLong(), substation);
        byChunk.computeIfAbsent(chunkKey(pos), ignored -> new ArrayList<>()).add(substation);
        return substation;
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static int floorChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    public static final class Substation {
        private final SubstationNetwork network;
        private final BlockPos pos;
        private long energy;
        private boolean breakerOpen;
        private long outputTick = Long.MIN_VALUE;
        private long deliveredThisTick;
        @Nullable
        private WeakReference<TractionSubstationBlockEntity> loaded;

        private Substation(SubstationNetwork network, BlockPos pos) {
            this.network = network;
            this.pos = pos;
        }

        public BlockPos pos() {
            return pos;
        }

        public long energy() {
            return energy;
        }

        public boolean breakerOpen() {
            return breakerOpen;
        }

        /** Energy still deliverable to trains this server tick: limited by charge and the shared output rating. */
        public long availableOutput(long tick) {
            if (breakerOpen) {
                return 0;
            }
            if (outputTick != tick) {
                outputTick = tick;
                deliveredThisTick = 0;
            }
            return Math.max(0L, Math.min(energy, MaglevConfig.substationOutput() - deliveredThisTick));
        }

        /** Debits energy delivered to a train; callers stay within {@link #availableOutput(long)}. */
        public void deliver(long amount, long tick) {
            if (amount <= 0) {
                return;
            }
            availableOutput(tick);
            deliveredThisTick += amount;
            setEnergy(energy - amount);
        }

        void setEnergy(long value) {
            long next = Math.max(0L, value);
            if (next == energy) {
                return;
            }
            energy = next;
            network.setDirty();
            TractionSubstationBlockEntity blockEntity = loaded == null ? null : loaded.get();
            if (blockEntity != null && !blockEntity.isRemoved()) {
                blockEntity.onEnergyChanged();
            }
        }

        void setBreakerOpen(boolean open) {
            if (breakerOpen != open) {
                breakerOpen = open;
                network.setDirty();
            }
        }

        private double distanceSquared(Vec3 position) {
            double dx = pos.getX() + 0.5D - position.x;
            double dy = pos.getY() + 0.5D - position.y;
            double dz = pos.getZ() + 0.5D - position.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
