package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.createmaglev.power.TractionPower;
import dev.createmaglev.power.TractionSubstationBlockEntity;
import java.util.List;
import java.util.ArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.CNABlocks;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import net.minecraft.world.level.block.DirectionalBlock;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlockEntity;
import org.antarcticgardens.cna.content.electricity.generation.brushes.CarbonBrushesBlockEntity;
import org.antarcticgardens.cna.content.electricity.generation.coil.GeneratorCoilBlockEntity;
import org.antarcticgardens.cna.content.electricity.wire.WireType;

/** Actual Create New Age mechanical generation, carbon-brush output, and native ESL network wiring. */
public final class NativeNewAgePowerFixture {
    private static final List<NativeNewAgePowerFixture> PENDING_WIRES = new ArrayList<>();
    static {
        ServerTickEvents.END_WORLD_TICK.register(level -> PENDING_WIRES.removeIf(fixture -> {
            if (fixture.level != level) return false;
            if (fixture.sourceConnector.getNetwork() == null || fixture.sinkConnector.getNetwork() == null) return false;
            fixture.sourceConnector.connect(fixture.sinkConnector, WireType.COPPER);
            if (!fixture.hasLiveCopperWire()) throw new IllegalStateException("Native NewAge copper wire did not connect");
            return true;
        }));
    }
    private final ServerLevel level;
    private final BlockPos origin;
    private final BlockPos motorPos;
    private final BlockPos brushPos;
    private final BlockPos coilPos;
    private final BlockPos substationPos;
    private final CreativeMotorBlockEntity motor;
    private final GeneratorCoilBlockEntity coil;
    private final CarbonBrushesBlockEntity brushes;
    private final TractionSubstationBlockEntity substation;
    private final ElectricalConnectorBlockEntity sourceConnector;
    private final ElectricalConnectorBlockEntity sinkConnector;

    private NativeNewAgePowerFixture(ServerLevel level, BlockPos origin, BlockPos motorPos, BlockPos brushPos,
                                     BlockPos coilPos, BlockPos substationPos, CreativeMotorBlockEntity motor,
                                     GeneratorCoilBlockEntity coil, CarbonBrushesBlockEntity brushes,
                                     TractionSubstationBlockEntity substation,
                                     ElectricalConnectorBlockEntity sourceConnector,
                                     ElectricalConnectorBlockEntity sinkConnector) {
        this.level = level;
        this.origin = origin;
        this.motorPos = motorPos;
        this.brushPos = brushPos;
        this.coilPos = coilPos;
        this.substationPos = substationPos;
        this.motor = motor;
        this.coil = coil;
        this.brushes = brushes;
        this.substation = substation;
        this.sourceConnector = sourceConnector;
        this.sinkConnector = sinkConnector;
    }

    public static NativeNewAgePowerFixture build(ServerLevel level, BlockPos origin) {
        forceRegion(level, origin);
        BlockPos motorPos = origin;
        BlockPos brushPos = origin.above();
        BlockPos coilPos = origin.above(2);
        BlockPos substationPos = origin.relative(Direction.EAST, 5);
        BlockPos sourceConnectorPos = brushPos.relative(Direction.EAST);
        BlockPos sinkConnectorPos = substationPos.relative(Direction.EAST);

        level.setBlock(motorPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP), 3);
        level.setBlock(brushPos, CNABlocks.CARBON_BRUSHES.get().defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP), 3);
        level.setBlock(coilPos, CNABlocks.GENERATOR_COIL.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y), 3);
        BlockEntity coilEntity = level.getBlockEntity(coilPos);
        BlockEntity motorEntity = level.getBlockEntity(motorPos);
        BlockEntity brushEntity = level.getBlockEntity(brushPos);
        if (!(coilEntity instanceof GeneratorCoilBlockEntity generator)
                || !(motorEntity instanceof CreativeMotorBlockEntity creativeMotor)
                || !(brushEntity instanceof CarbonBrushesBlockEntity carbonBrushes)) {
            throw new IllegalStateException("NewAge generator, motor, or carbon-brush block entity was not created");
        }
        List<BlockPos> magnets = List.copyOf(generator.magnetPositions);
        for (BlockPos magnetPos : magnets) {
            level.setBlock(magnetPos, CNABlocks.LAYERED_MAGNET.get().defaultBlockState(), 3);
        }

        level.setBlock(substationPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(substationPos, TractionPower.SUBSTATION.defaultBlockState(), 3);
        BlockEntity stationEntity = level.getBlockEntity(substationPos);
        if (!(stationEntity instanceof TractionSubstationBlockEntity station)) {
            throw new IllegalStateException("Traction substation block entity was not created");
        }

        level.setBlock(sourceConnectorPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(sinkConnectorPos.below(), Blocks.STONE.defaultBlockState(), 3);
        BlockState connectorState = CNABlocks.ELECTRICAL_CONNECTOR.get().defaultBlockState()
                .setValue(DirectionalBlock.FACING, Direction.EAST);
        level.setBlock(sourceConnectorPos, connectorState, 3);
        level.setBlock(sinkConnectorPos, connectorState, 3);
        BlockEntity sourceEntity = level.getBlockEntity(sourceConnectorPos);
        BlockEntity sinkEntity = level.getBlockEntity(sinkConnectorPos);
        if (!(sourceEntity instanceof ElectricalConnectorBlockEntity source)
                || !(sinkEntity instanceof ElectricalConnectorBlockEntity sink)) {
            throw new IllegalStateException("NewAge electrical connector block entities were not created");
        }

        creativeMotor.generatedSpeed.value = 128;
        creativeMotor.updateGeneratedRotation();
        NativeNewAgePowerFixture fixture = new NativeNewAgePowerFixture(level, origin, motorPos, brushPos, coilPos, substationPos,
                creativeMotor, generator, carbonBrushes, station, source, sink);
        PENDING_WIRES.add(fixture);
        return fixture;
    }

    private static void forceRegion(ServerLevel level, BlockPos origin) {
        int minChunkX = (origin.getX() - 32) >> 4;
        int maxChunkX = (origin.getX() + 32) >> 4;
        int minChunkZ = (origin.getZ() - 32) >> 4;
        int maxChunkZ = (origin.getZ() + 32) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    public ServerLevel level() { return level; }
    public BlockPos origin() { return origin; }
    public BlockPos motorPos() { return motorPos; }
    public BlockPos brushPos() { return brushPos; }
    public BlockPos coilPos() { return coilPos; }
    public BlockPos substationPos() { return substationPos; }
    public CreativeMotorBlockEntity motor() { return motor; }
    public GeneratorCoilBlockEntity coil() { return coil; }
    public CarbonBrushesBlockEntity brushes() { return brushes; }
    public TractionSubstationBlockEntity substation() { return substation; }
    public AbstractElectricalConnector sourceConnector() { return sourceConnector; }
    public AbstractElectricalConnector sinkConnector() { return sinkConnector; }
    public boolean hasLiveCopperWire() {
        return sourceConnector.getConnectedConnectors().get(sinkConnector) == WireType.COPPER
                && sinkConnector.getConnectedConnectors().get(sourceConnector) == WireType.COPPER;
    }
    public void stopGeneration() {
        motor.generatedSpeed.value = 0;
        motor.updateGeneratedRotation();
    }
    public void disconnect() {
        sourceConnector.disconnect(sinkConnector);
        sinkConnector.disconnect(sourceConnector);
        if (sourceConnector.getNetwork() != null) sourceConnector.getNetwork().destroy();
    }
    public boolean isStillLoaded() {
        return level.hasChunkAt(origin) && level.getBlockEntity(coilPos) == coil
                && level.getBlockEntity(brushPos) == brushes && level.getBlockEntity(substationPos) == substation;
    }
    public static TractionSubstationBlockEntity find(ServerLevel level, BlockPos origin) {
        return TractionPower.find(level, Vec3.atCenterOf(origin));
    }

    public void removeOwnedTickets() {
        int minChunkX = (origin.getX() - 32) >> 4;
        int maxChunkX = (origin.getX() + 32) >> 4;
        int minChunkZ = (origin.getZ() - 32) >> 4;
        int maxChunkZ = (origin.getZ() + 32) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, false);
            }
        }
    }
}
