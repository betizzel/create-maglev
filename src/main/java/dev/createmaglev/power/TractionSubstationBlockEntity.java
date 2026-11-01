package dev.createmaglev.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.antarcticgardens.esl.energy.EnergyStorage;
import org.antarcticgardens.esl.transaction.SnapshotParticipant;
import org.antarcticgardens.esl.transaction.Transaction;
import org.antarcticgardens.esl.transaction.TransactionContext;
import org.antarcticgardens.esl.transaction.TransactionStack;

public final class TractionSubstationBlockEntity extends BlockEntity {
    public static final long CAPACITY = 8192L;
    private static final String ENERGY_TAG = "Energy";

    private long energyStored;
    private int lastComparatorSignal;
    private final EslEnergyStorage energyStorage = new EslEnergyStorage();

    public TractionSubstationBlockEntity(BlockPos pos, BlockState state) {
        super(TractionPower.SUBSTATION_BLOCK_ENTITY, pos, state);
    }

    public long energyStored() {
        return energyStored;
    }

    public boolean consume(long amount) {
        if (amount < 0 || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!serverLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Traction-substation energy must be consumed on the server thread");
        }
        try (Transaction transaction = TransactionStack.get().openOuter()) {
            if (!consume(amount, transaction)) {
                return false;
            }
            transaction.commit();
            return true;
        }
    }

    public boolean consume(long amount, TransactionContext transaction) {
        if (amount < 0 || transaction == null || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!serverLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Traction-substation energy must be consumed on the server thread");
        }
        return energyStorage.consume(amount, transaction);
    }

    EnergyStorage energyStorageFor(Direction direction) {
        return energyStorage;
    }

    boolean canSupply() {
        return !isRemoved() && level instanceof ServerLevel serverLevel
                && serverLevel.hasChunkAt(worldPosition)
                && serverLevel.getBlockState(worldPosition).getBlock() == TractionPower.SUBSTATION
                && !serverLevel.hasNeighborSignal(worldPosition);
    }

    @Override
    public void setRemoved() {
        TractionPower.untrack(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong(ENERGY_TAG, energyStored);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energyStored = Math.max(0L, Math.min(CAPACITY, tag.getLong(ENERGY_TAG)));
        lastComparatorSignal = comparatorSignal(energyStored);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }


    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void energyChanged() {
        setChanged();
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        int signal = comparatorSignal(energyStored);
        if (signal != lastComparatorSignal) {
            lastComparatorSignal = signal;
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
    }

    private static int comparatorSignal(long energy) {
        return (int) (energy * 15L / CAPACITY);
    }

    private final class EslEnergyStorage extends SnapshotParticipant<Long> implements EnergyStorage {
        @Override
        public Long createSnapshot() {
            return energyStored;
        }

        @Override
        public void restoreSnapshot(Long snapshot) {
            energyStored = snapshot;
        }

        @Override
        protected void onFinalCommit() {
            energyChanged();
        }

        @Override
        public long insert(long amount, TransactionContext transaction) {
            if (amount <= 0L || transaction == null || !canSupply()) {
                return 0L;
            }
            long accepted = Math.min(amount, CAPACITY - energyStored);
            if (accepted <= 0L) {
                return 0L;
            }
            updateSnapshots(transaction);
            energyStored += accepted;
            return accepted;
        }

        boolean consume(long amount, TransactionContext transaction) {
            if (amount < 0L || transaction == null || !canSupply() || energyStored < amount) {
                return false;
            }
            if (amount != 0L) {
                updateSnapshots(transaction);
                energyStored -= amount;
            }
            return true;
        }

        @Override
        public long extract(long amount, TransactionContext transaction) {
            return 0L;
        }

        @Override
        public long getStoredEnergy() {
            return energyStored;
        }

        @Override
        public long getCapacity() {
            return CAPACITY;
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public boolean supportsInsertion() {
            return canSupply();
        }
    }
}
