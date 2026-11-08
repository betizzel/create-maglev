package dev.createmaglev.power;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.createmaglev.config.MaglevConfig;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.antarcticgardens.esl.energy.EnergyStorage;
import org.antarcticgardens.esl.transaction.SnapshotParticipant;
import org.antarcticgardens.esl.transaction.TransactionContext;

/** In-world view of a {@link SubstationNetwork} entry: New Age charging, comparator, client sync and goggles. */
public final class TractionSubstationBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private static final String ENERGY_TAG = "Energy";
    private static final int CLIENT_SYNC_INTERVAL = 10;

    /** Charge read from NBT before the server level is known; the network decides whether it applies. */
    private long savedEnergy = -1;
    private long clientEnergy;
    private int lastComparatorSignal = -1;
    private boolean clientSyncPending;
    private long lastClientSync = Long.MIN_VALUE;
    private final EslEnergyStorage energyStorage = new EslEnergyStorage();

    public TractionSubstationBlockEntity(BlockPos pos, BlockState state) {
        super(TractionPower.SUBSTATION_BLOCK_ENTITY, pos, state);
    }

    /** The authoritative network charge on the server; the last synchronized charge on clients. */
    public long energyStored() {
        if (level instanceof ServerLevel) {
            SubstationNetwork.Substation substation = entry();
            return substation == null ? 0L : substation.energy();
        }
        return clientEnergy;
    }

    EnergyStorage energyStorageFor(Direction direction) {
        return energyStorage;
    }

    @Nullable
    private SubstationNetwork.Substation entry() {
        return level instanceof ServerLevel server ? SubstationNetwork.get(server).get(worldPosition) : null;
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel server) {
            // Chunk promotion may still be in progress: use only this block entity's own state, never neighbours.
            SubstationNetwork.get(server).attach(this, savedEnergy, getBlockState().getValue(TractionSubstationBlock.POWERED));
        } else if (savedEnergy >= 0) {
            clientEnergy = savedEnergy;
        }
        savedEnergy = -1;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            SubstationNetwork.get(server).detach(this);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // Mirrors the network so contraptions and 1.0.x-style readers carry the charge with the block.
        tag.putLong(ENERGY_TAG, energyStored());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        long energy = Math.max(0L, tag.getLong(ENERGY_TAG));
        if (level == null) {
            savedEnergy = energy;
        } else if (level instanceof ServerLevel server) {
            SubstationNetwork.get(server).restoreEnergy(this, energy);
        } else {
            clientEnergy = energy;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Called by the network whenever this substation's charge changes, including train draws. */
    void onEnergyChanged() {
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
        clientSyncPending = true;
    }

    int comparatorSignal() {
        long capacity = MaglevConfig.substationCapacity();
        return capacity <= 0 ? 0 : (int) Math.min(15L, energyStored() * 15L / capacity);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, TractionSubstationBlockEntity substation) {
        SubstationNetwork.Substation entry = substation.entry();
        if (entry != null) {
            // The block state is authoritative while loaded, however the property was changed.
            entry.setBreakerOpen(state.getValue(TractionSubstationBlock.POWERED));
        }
        // Also refreshes comparators after charge was drawn while this chunk was unloaded.
        int signal = substation.comparatorSignal();
        if (signal != substation.lastComparatorSignal) {
            substation.lastComparatorSignal = signal;
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
        long now = level.getGameTime();
        if (substation.clientSyncPending && now - substation.lastClientSync >= CLIENT_SYNC_INTERVAL) {
            substation.clientSyncPending = false;
            substation.lastClientSync = now;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean breakerOpen = getBlockState().getValue(TractionSubstationBlock.POWERED);
        tooltip.add(Component.literal("    ").append(Component.translatable("create_maglev.goggles.substation")));
        tooltip.add(Component.literal("     ").append(Component.translatable("create_maglev.goggles.substation.stored",
                energyStored(), MaglevConfig.substationCapacity()).withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("     ").append(Component.translatable(breakerOpen
                        ? "create_maglev.goggles.substation.breaker_open" : "create_maglev.goggles.substation.breaker_closed")
                .withStyle(breakerOpen ? ChatFormatting.RED : ChatFormatting.GREEN)));
        tooltip.add(Component.literal("     ").append(Component.translatable("create_maglev.goggles.substation.output",
                MaglevConfig.substationOutput()).withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("     ").append(Component.translatable("create_maglev.goggles.substation.radius",
                MaglevConfig.supplyRadius()).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    private final class EslEnergyStorage extends SnapshotParticipant<Long> implements EnergyStorage {
        @Override
        public Long createSnapshot() {
            return energyStored();
        }

        @Override
        public void restoreSnapshot(Long snapshot) {
            SubstationNetwork.Substation substation = entry();
            if (substation != null) {
                substation.setEnergy(snapshot);
            }
        }

        @Override
        public long insert(long amount, TransactionContext transaction) {
            SubstationNetwork.Substation substation = entry();
            // An open traction breaker isolates the substation from charging as well as from trains.
            if (amount <= 0L || transaction == null || substation == null || substation.breakerOpen()) {
                return 0L;
            }
            long accepted = Math.min(amount, MaglevConfig.substationCapacity() - substation.energy());
            if (accepted <= 0L) {
                return 0L;
            }
            updateSnapshots(transaction);
            substation.setEnergy(substation.energy() + accepted);
            return accepted;
        }

        @Override
        public long extract(long amount, TransactionContext transaction) {
            return 0L;
        }

        @Override
        public long getStoredEnergy() {
            return energyStored();
        }

        @Override
        public long getCapacity() {
            return MaglevConfig.substationCapacity();
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public boolean supportsInsertion() {
            SubstationNetwork.Substation substation = entry();
            return substation != null && !substation.breakerOpen();
        }
    }
}
