package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.createmaglev.content.MaglevContent;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.level.GameType;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.antarcticgardens.cna.CNABlocks;
import org.antarcticgardens.cna.CNAItems;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlock;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlockEntity;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorMode;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.antarcticgardens.cna.content.energising.EnergiserBehaviour;
import org.antarcticgardens.cna.content.energising.EnergiserBlockEntity;

/** Exercises real crafting, NewAge electrical processing, and Create belt transport together. */
public final class NativeEnergisingTests implements FabricGameTest {
    private static final int REGION_SPACING = 512;
    private static final int BELT_START_X = 2;
    private static final int BELT_END_X = 20;
    private static final int ENERGISER_X = 6;
    private static final int BELT_Y_OFFSET = 1;
    private static final int ENERGISER_Y_OFFSET = 3;
    private static final long REQUIRED_UNPOWERED_TICKS = 200;

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 380, attempts = 1)
    public void craftsModulesAndEnergisesOnlyTheNativelyPoweredBeltInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 8);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin);
        EnergiserLane powered = null;
        EnergiserLane disconnected = null;
        AtomicBoolean completed = new AtomicBoolean();
        try {
            ItemStack[] modules = craftTwoModules(helper, level, origin.offset(0, 0, 3));
            powered = buildLane(level, origin, 4);
            disconnected = buildLane(level, origin, 10);

            AtomicBoolean sourceConnected = new AtomicBoolean();
            AtomicBoolean itemsSubmitted = new AtomicBoolean();
            AtomicInteger poweredProcessingTicks = new AtomicInteger();
            AtomicBoolean sawPoweredTransition = new AtomicBoolean();
            AtomicInteger poweredEnergyDrawTicks = new AtomicInteger();
            AtomicLong unpoweredProcessingStartedAt = new AtomicLong(-1L);
            EnergiserLane poweredLane = powered;
            EnergiserLane disconnectedLane = disconnected;

            helper.onEachTick(() -> {
                if (completed.get())
                    return;
                try {
                    if (!sourceConnected.get()
                            && source.sourceConnector().getNetwork() != null
                            && poweredLane.connector.getNetwork() != null) {
                        source.sourceConnector().connect(poweredLane.connector, WireType.COPPER);
                        helper.assertTrue(source.sourceConnector().getConnectedConnectors()
                                        .get(poweredLane.connector) == WireType.COPPER
                                        && poweredLane.connector.getConnectedConnectors()
                                        .get(source.sourceConnector()) == WireType.COPPER,
                                "The powered basic energiser must join the live NewAge copper network");
                        sourceConnected.set(true);
                    }

                    if (sourceConnected.get() && !itemsSubmitted.get()
                            && poweredLane.machine.getSpeed() != 0
                            && disconnectedLane.machine.getSpeed() != 0
                            && poweredLane.belt.getSpeed() != 0
                            && disconnectedLane.belt.getSpeed() != 0) {
                        submitItem(poweredLane, modules[0]);
                        submitItem(disconnectedLane, modules[1]);
                        itemsSubmitted.set(true);
                    }

                    if (!itemsSubmitted.get())
                        return;

                    if (containsItem(poweredLane.belt, MaglevContent.LEVITATION_MODULE))
                        sawPoweredTransition.set(true);

                    EnergiserBehaviour poweredBehaviour = behaviour(level, poweredLane.energiserPos);
                    EnergiserBehaviour disconnectedBehaviour = behaviour(level, disconnectedLane.energiserPos);
                    if (poweredBehaviour != null
                            && poweredBehaviour.currentRecipe != null
                            && poweredBehaviour.needed > 0)
                        poweredProcessingTicks.incrementAndGet();
                    if (poweredLane.machine.lastCharged > 0)
                        poweredEnergyDrawTicks.incrementAndGet();

                    if (disconnectedBehaviour != null
                            && disconnectedBehaviour.currentRecipe != null
                            && disconnectedBehaviour.needed > 0
                            && disconnectedBehaviour.charged == 0
                            && containsItem(disconnectedLane.belt, MaglevContent.UNENERGIZED_LEVITATION_MODULE)) {
                        unpoweredProcessingStartedAt.compareAndSet(-1L, level.getGameTime());
                    }

                    long unpoweredStart = unpoweredProcessingStartedAt.get();
                    if (sawPoweredTransition.get()
                            && poweredProcessingTicks.get() >= 2
                            && poweredEnergyDrawTicks.get() >= 2
                            && unpoweredStart >= 0
                            && level.getGameTime() - unpoweredStart >= REQUIRED_UNPOWERED_TICKS) {
                        helper.assertTrue(source.hasLiveCopperWire(),
                                "The native NewAge generator must retain its real copper connection to the substation");
                        helper.assertTrue(disconnectedLane.connector.getConnectedConnectors().isEmpty(),
                                "The negative-control energiser connector must remain disconnected from any energy source");
                        helper.assertTrue(containsItem(disconnectedLane.belt,
                                        MaglevContent.UNENERGIZED_LEVITATION_MODULE)
                                        && !containsItem(disconnectedLane.belt, MaglevContent.LEVITATION_MODULE),
                                "The disconnected NewAge energiser must retain its unenergized belt input");
                        helper.assertTrue(level.getGameTime() - unpoweredStart >= REQUIRED_UNPOWERED_TICKS,
                                "The disconnected control must remain unpowered for the full observation interval");
                        finish(helper, completed, source, poweredLane, disconnectedLane);
                    }
                } catch (Throwable error) {
                    fail(helper, completed, source, poweredLane, disconnectedLane, error);
                }
            });

            helper.runAtTickTime(350, () -> {
                if (!completed.get())
                    fail(helper, completed, source, poweredLane, disconnectedLane,
                            new IllegalStateException("Native energising did not complete. Powered: " + describe(poweredLane)
                                    + "; disconnected: " + describe(disconnectedLane)));
            });
        } catch (Throwable error) {
            fail(helper, completed, source, powered, disconnected, error);
        }
    }


    private static ItemStack[] craftTwoModules(GameTestHelper helper, ServerLevel level, BlockPos tablePos) {
        level.setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "MaglevCrafting"));
        level.getServer().getPlayerList().placeNewPlayer(new Connection(PacketFlow.SERVERBOUND), player);
        player.setGameMode(GameType.SURVIVAL);
        try {
            player.moveTo(tablePos.getX() + 0.5, tablePos.getY() + 1, tablePos.getZ() + 0.5);
            CraftingMenu menu = new CraftingMenu(1, player.getInventory(), ContainerLevelAccess.create(level, tablePos));
            Container grid = menu.getSlot(1).container;
            grid.setItem(1, new ItemStack(CNAItems.OVERCHARGED_IRON_SHEET.get(), 2));
            grid.setItem(3, new ItemStack(MaglevContent.LAMINATED_CORE, 2));
            grid.setItem(4, new ItemStack(CNABlocks.LAYERED_MAGNET.get().asItem(), 2));
            grid.setItem(5, new ItemStack(MaglevContent.WOUND_COIL, 2));
            grid.setItem(7, new ItemStack(CNAItems.OVERCHARGED_IRON_SHEET.get(), 2));
            for (int craft = 1; craft <= 2; craft++) {
                menu.slotsChanged(grid);
                helper.assertTrue(menu.getSlot(0).getItem().is(MaglevContent.UNENERGIZED_LEVITATION_MODULE),
                        "The native crafting table must produce an unenergized levitation module");
                menu.clicked(0, 0, ClickType.PICKUP, player);
                helper.assertTrue(menu.getCarried().is(MaglevContent.UNENERGIZED_LEVITATION_MODULE)
                                && menu.getCarried().getCount() == craft,
                        "Taking each crafting result must consume the recipe's materials");
            }
            for (int slot = 0; slot < 9; slot++)
                helper.assertTrue(grid.getItem(slot).isEmpty(), "Both native crafts must consume all required materials");
            ItemStack first = menu.getCarried().split(1);
            ItemStack second = menu.getCarried().split(1);
            menu.setCarried(ItemStack.EMPTY);
            return new ItemStack[] { first, second };
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
    }

    private static EnergiserLane buildLane(ServerLevel level, BlockPos origin, int laneZ) {
        BlockPos start = origin.offset(BELT_START_X, BELT_Y_OFFSET, laneZ);
        BlockPos end = origin.offset(BELT_END_X, BELT_Y_OFFSET, laneZ);

        for (int x = BELT_START_X; x <= BELT_END_X; x++)
            level.setBlock(origin.offset(x, BELT_Y_OFFSET - 1, laneZ), Blocks.STONE.defaultBlockState(), 3);

        BlockPos beltMotorPos = origin.offset(BELT_START_X, BELT_Y_OFFSET, laneZ - 1);
        level.setBlock(beltMotorPos.relative(Direction.NORTH), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(beltMotorPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(beltMotorPos, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(CreativeMotorBlock.FACING, Direction.SOUTH), 3);
        level.setBlock(start, AllBlocks.SHAFT.getDefaultState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z), 3);
        level.setBlock(end, AllBlocks.SHAFT.getDefaultState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z), 3);
        BeltConnectorItem.createBelts(level, start, end);
        BeltBlock.initBelt(level, start);

        BlockPos energiserPos = origin.offset(ENERGISER_X, ENERGISER_Y_OFFSET, laneZ);
        BlockPos machineMotorPos = energiserPos.west();
        level.setBlock(machineMotorPos.west(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(machineMotorPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(machineMotorPos, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(CreativeMotorBlock.FACING, Direction.EAST), 3);
        level.setBlock(energiserPos, CNABlocks.BASIC_ENERGISER.get().defaultBlockState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST), 3);
        BlockPos connectorPos = energiserPos.east();
        level.setBlock(connectorPos, CNABlocks.ELECTRICAL_CONNECTOR.get().defaultBlockState()
                .setValue(DirectionalBlock.FACING, Direction.EAST)
                .setValue(ElectricalConnectorBlock.MODE, ElectricalConnectorMode.INERT), 3);

        BlockEntity beltEntity = level.getBlockEntity(start);
        BlockEntity beltMotorEntity = level.getBlockEntity(beltMotorPos);
        BlockEntity machineMotorEntity = level.getBlockEntity(machineMotorPos);
        BlockEntity energiserEntity = level.getBlockEntity(energiserPos);
        BlockEntity connectorEntity = level.getBlockEntity(connectorPos);
        if (!(beltEntity instanceof BeltBlockEntity belt)
                || !(beltMotorEntity instanceof CreativeMotorBlockEntity beltMotor)
                || !(machineMotorEntity instanceof CreativeMotorBlockEntity machineMotor)
                || !(energiserEntity instanceof EnergiserBlockEntity energiser)
                || !(connectorEntity instanceof ElectricalConnectorBlockEntity connector))
            throw new IllegalStateException("Native belt, kinetic motors, basic energiser, and electrical connector must create their block entities");
        if (!connector.getFacing().equals(Direction.EAST)
                || !connector.getSupportingBlockPos().equals(energiserPos))
            throw new IllegalStateException("NewAge connector must face east and be backed by its basic energiser");

        beltMotor.generatedSpeed.value = 128;
        beltMotor.updateGeneratedRotation();
        machineMotor.generatedSpeed.value = 128;
        machineMotor.updateGeneratedRotation();
        if (belt.getControllerBE() == null || belt.getControllerBE() != belt)
            throw new IllegalStateException("Create must initialize the native belt controller");
        return new EnergiserLane(energiserPos, belt, beltMotor, machineMotor, energiser, connector);
    }

    private static void submitItem(EnergiserLane lane, ItemStack input) {
        int upstream = lane.belt.getDirectionAwareBeltMovementSpeed() > 0 ? 0 : lane.belt.beltLength - 1;
        BlockPos entry = BeltHelper.getPositionForOffset(lane.belt, upstream);
        var insertion = BlockEntityBehaviour.get(lane.belt.getLevel(), entry, DirectBeltInputBehaviour.TYPE);
        if (insertion == null || !insertion.handleInsertion(input, Direction.UP, false).isEmpty()) {
            throw new IllegalStateException("The moving native belt rejected its upstream input");
        }
    }

    private static String describe(EnergiserLane lane) {
        var behaviour = behaviour((ServerLevel) lane.machine.getLevel(), lane.energiserPos);
        StringBuilder state = new StringBuilder("rpm=").append(lane.machine.getSpeed())
                .append(", energy=").append(lane.machine.getEnergyStorage().getStoredEnergy())
                .append(", beltSpeed=").append(lane.belt.getDirectionAwareBeltMovementSpeed());
        if (behaviour != null) state.append(", progress=").append(behaviour.charged).append('/').append(behaviour.needed);
        for (var item : lane.belt.getInventory().getTransportedItems()) {
            state.append(", item=").append(item.stack).append('@').append(item.beltPosition);
        }
        return state.toString();
    }

    private static EnergiserBehaviour behaviour(ServerLevel level, BlockPos pos) {
        var behaviour = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
                level, pos, EnergiserBehaviour.TYPE);
        return behaviour instanceof EnergiserBehaviour energiser ? energiser : null;
    }

    private static boolean containsItem(BeltBlockEntity belt, Item item) {
        BeltInventory inventory = belt.getInventory();
        for (TransportedItemStack transported : inventory.getTransportedItems())
            if (transported.stack.is(item))
                return true;
        return false;
    }

    private static BlockPos isolatedOrigin(GameTestHelper helper, int region) {
        return helper.absolutePos(new BlockPos(512 + region * REGION_SPACING, 64, 512));
    }

    private static void finish(GameTestHelper helper, AtomicBoolean completed, NativeNewAgePowerFixture source,
                               EnergiserLane powered, EnergiserLane disconnected) {
        if (!completed.compareAndSet(false, true))
            return;
        cleanup(source, powered, disconnected);
        helper.succeed();
    }

    private static void fail(GameTestHelper helper, AtomicBoolean completed, NativeNewAgePowerFixture source,
                             EnergiserLane powered, EnergiserLane disconnected, Throwable error) {
        if (!completed.compareAndSet(false, true))
            return;
        cleanup(source, powered, disconnected);
        helper.fail(error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static void cleanup(NativeNewAgePowerFixture source, EnergiserLane powered,
                                EnergiserLane disconnected) {
        stop(powered);
        stop(disconnected);
        source.stopGeneration();
        source.removeOwnedTickets();
    }

    private static void stop(EnergiserLane lane) {
        if (lane == null)
            return;
        lane.beltMotor.generatedSpeed.value = 0;
        lane.beltMotor.updateGeneratedRotation();
        lane.machineMotor.generatedSpeed.value = 0;
        lane.machineMotor.updateGeneratedRotation();
    }

    private record EnergiserLane(BlockPos energiserPos, BeltBlockEntity belt,
                                 CreativeMotorBlockEntity beltMotor, CreativeMotorBlockEntity machineMotor,
                                 EnergiserBlockEntity machine, ElectricalConnectorBlockEntity connector) {}
}
