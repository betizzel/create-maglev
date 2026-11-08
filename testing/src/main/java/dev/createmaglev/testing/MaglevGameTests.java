package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.createmaglev.config.MaglevConfig;
import dev.createmaglev.content.MaglevContent;
import dev.createmaglev.content.PropulsionBogeyBlock;
import dev.createmaglev.power.TractionSubstationBlockEntity;
import dev.createmaglev.train.LevitationData;
import dev.createmaglev.train.TractionState;
import dev.createmaglev.train.TrainTier;
import dev.createmaglev.train.TrainTraction;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.antarcticgardens.cna.content.electricity.generation.brushes.CarbonBrushesBlockEntity;
import org.antarcticgardens.cna.content.electricity.generation.coil.GeneratorCoilBlockEntity;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.createmaglev.power.TractionPower;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import org.antarcticgardens.esl.energy.EnergyStorage;
import org.antarcticgardens.esl.transaction.Transaction;
import org.antarcticgardens.esl.transaction.TransactionStack;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;

/** Consumer-facing native train, track, NewAge energy, and persistence behavior. */
public final class MaglevGameTests implements FabricGameTest {
    private static final int REGION_SPACING = 512;
    private static final int LIVE_CARRIAGE_GRACE_TICKS = 8;
    /** Charge delivered through the public ESL API; fits within the original 1.0.x buffer too. */
    private static final long TEST_CHARGE = 8192;
    private static final String WEAK_SUPPLY_BATCH = "create_maglev_weak_supply";
    /** Just above two electric bogeys' 16-unit idle draw, so only a little is left for speed. */
    private static final int WEAK_SUPPLY_OUTPUT = 20;
    /** New train-running regressions stay out of Create's seeded default batch. */
    private static final String SUPPLY_BATCH = "create_maglev_supply";

    @GameTest(template = "create_maglev_tests:arena", batch = "create_maglev_survival_mining", timeoutTicks = 40, attempts = 1)
    public void survivalPickaxeMiningReturnsEveryAddonBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.setGameMode(GameType.SURVIVAL);
            List<Block> blocks = List.of(TractionPower.SUBSTATION, MaglevContent.ELECTRIC_BOGEY, MaglevContent.BULLET_BOGEY,
                    MaglevContent.MAGLEV_BOGEY, MaglevContent.REINFORCED_TRACK, MaglevContent.MAGLEV_TRACK);
            int x = 1;
            for (Block block : blocks) {
                boolean bogey = block instanceof PropulsionBogeyBlock;
                BlockPos base = helper.absolutePos(new BlockPos(x, 1, 2));
                BlockPos pos = bogey ? base.above() : base;
                AABB area = new AABB(pos).inflate(2);
                level.getEntitiesOfClass(ItemEntity.class, area).forEach(Entity::discard);
                level.setBlock(base.below(), Blocks.STONE.defaultBlockState(), 3);
                if (bogey) {
                    level.setBlock(base, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO), 3);
                }
                level.setBlock(pos, block.defaultBlockState(), 3);
                BlockState placed = level.getBlockState(pos);
                String name = BuiltInRegistries.BLOCK.getKey(block).toString();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                helper.assertTrue(placed.is(block), "Expected " + name + " to be placed for the mining check");
                helper.assertTrue(player.hasCorrectToolForDrops(placed), "A pickaxe must be the correct harvesting tool for " + name);
                helper.assertTrue(player.getMainHandItem().getDestroySpeed(placed) > 1.0F,
                        "A pickaxe must mine " + name + " faster than a bare hand");
                helper.assertTrue(player.gameMode.destroyBlock(pos), "A survival player must be able to break " + name);
                int drops = 0;
                for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) {
                    if (drop.getItem().is(block.asItem())) drops += drop.getItem().getCount();
                    drop.discard();
                }
                helper.assertTrue(drops == 1, "Survival pickaxe mining must return exactly one " + name + ", got " + drops);
                x += 3;
            }
            helper.succeed();
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 240, attempts = 1)
    public void nativeNewAgeBrushesAndCopperWireChargePersistentSubstation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 0);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin);
        AtomicBoolean completed = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (completed.get()) return;
            guarded(helper, completed, () -> {
                if (source.substation().energyStored() == 0) return;
                completed.set(true);
                helper.assertTrue(source.hasLiveCopperWire(), "NewAge source and sink must remain linked by a live native copper wire");
                helper.assertTrue(level.getBlockEntity(source.coilPos()) == source.coil()
                                && source.coil() instanceof GeneratorCoilBlockEntity
                                && level.getBlockEntity(source.brushPos()) == source.brushes()
                                && source.brushes() instanceof CarbonBrushesBlockEntity,
                        "The generated charge must come from the real NewAge coil and carbon brushes");
                long energy = source.substation().energyStored();
                CompoundTag persisted = source.substation().saveWithoutMetadata();
                helper.assertTrue(persisted.getLong("Energy") == energy, "Substation NBT must persist actual stored energy");
                source.substation().load(persisted.copy());
                helper.assertTrue(source.substation().energyStored() == energy, "Reloading native substation NBT must preserve energy");
                source.stopGeneration();
                source.removeOwnedTickets();
                helper.succeed();
            });
        });
        helper.runAtTickTime(180, () -> {
            if (!completed.get()) helper.fail("NewAge generator, brushes, and native copper wire did not charge the substation");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 720, attempts = 1)
    public void assembledElectricTrainConsumesIsolatedRealChargeAndMovesUnderNativeSchedule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 1);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-electric", MaglevContent.ELECTRIC_BOGEY, false);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin.offset(37, 0, 48));
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<UUID> carriageId = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();
        AtomicLong startingCharge = new AtomicLong();
        AtomicReference<Vec3> initialPosition = new AtomicReference<>();
        AtomicReference<Double> furthest = new AtomicReference<>(0d);
        AtomicReference<Double> maxSpeed = new AtomicReference<>(0d);
        AtomicBoolean observedPower = new AtomicBoolean();

        helper.onEachTick(() -> {
            if (completed.get()) return;
            guarded(helper, completed, () -> {
                if (!started.get()) {
                    if (source.substation().energyStored() != MaglevConfig.substationCapacity()) return;
                    helper.assertTrue(source.hasLiveCopperWire(), "The native generator must fill the connected substation before isolation");
                    source.stopGeneration();
                    source.disconnect();
                    helper.assertTrue(!source.hasLiveCopperWire(), "The real generator wire must be detached before debit is measured");
                    helper.assertTrue(source.substation().energyStored() == MaglevConfig.substationCapacity(),
                            "The isolated substation must begin at its observed full capacity");
                    startingCharge.set(source.substation().energyStored());
                    Train assembled = built.assemble();
                    requireRegisteredTrain(assembled.id, assembled);
                    trainId.set(assembled.id);
                    built.scheduleToDestination();
                    started.set(true);
                    return;
                }

                Train train = requireRegisteredTrain(trainId.get(), null);
                UUID liveCarriageId = carriageId.get();
                if (liveCarriageId == null) {
                    if (carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                        throw new AssertionError("Native train did not register a live carriage within the assembly grace period");
                    }
                    liveCarriageId = captureRegisteredCarriageId(level, train);
                    if (liveCarriageId == null) return;
                    carriageId.set(liveCarriageId);
                }
                CarriageContraptionEntity carriage = requireLiveCarriage(level, train, liveCarriageId);
                TrainTraction traction = ((TractionState) train).maglev$traction();
                if (initialPosition.get() == null) initialPosition.set(carriage.position());
                furthest.set(Math.max(furthest.get(), carriage.position().distanceTo(initialPosition.get())));
                maxSpeed.set(Math.max(maxSpeed.get(), Math.abs(train.speed)));
                observedPower.set(observedPower.get() || traction.powered());

                if (furthest.get() > 3 && source.substation().energyStored() < startingCharge.get()
                        && train.getCurrentStation() == built.destinationStation.getStation()
                        && train.navigation.destination == null && Math.abs(train.speed) < 0.001) {
                    completed.set(true);
                    helper.assertTrue(carriage.isAlive() && level.getEntity(liveCarriageId) == carriage,
                            "The moving carriage must remain the same registered live entity by UUID");
                    helper.assertTrue(observedPower.get(), "Native traction must report powered while isolated real charge remains available");
                    helper.assertTrue(maxSpeed.get() > 0.001, "The scheduled native train must actually move under real traction");
                    helper.assertTrue(maxSpeed.get() <= TrainTier.ELECTRIC.topSpeed() + 0.001,
                            "Electric train speed must remain within the native electric tier ceiling");
                    helper.assertTrue(source.substation().energyStored() < startingCharge.get(),
                            "Native train traction must debit the full observed buffer after generation stops");
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(600, () -> {
            if (!completed.get()) helper.fail("Native electric train did not consume generated charge and complete its station-to-station schedule");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 360, attempts = 1)
    public void maglevRejectsOrdinaryTrackAndCannotMoveWithoutPower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 2);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-maglev-rejection", MaglevContent.MAGLEV_BOGEY, false);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<UUID> carriageId = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();

        helper.onEachTick(() -> {
            if (!started.get() || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                TrainTraction traction = ((TractionState) train).maglev$traction();
                UUID liveCarriageId = carriageId.get();
                if (liveCarriageId == null) {
                    if (carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                        throw new AssertionError("Unpowered native maglev did not register a live carriage after assembly");
                    }
                    liveCarriageId = captureRegisteredCarriageId(level, train);
                    if (liveCarriageId == null || !traction.authoritative()) return;
                    carriageId.set(liveCarriageId);
                }
                CarriageContraptionEntity carriage = requireLiveCarriage(level, train, liveCarriageId);
                if (!traction.authoritative()) return;
                completed.set(true);
                helper.assertTrue(carriage.isAlive(), "The native maglev carriage must remain live by its registered UUID");
                helper.assertTrue(!traction.powered(), "A maglev consist on ordinary track must not receive traction");
                helper.assertTrue(traction.speedLimit() == 0, "Maglev bogeys must reject non-maglev track material");
                helper.assertTrue(Math.abs(train.speed) < 0.001, "A maglev consist must remain stopped without a compatible powered route");
                helper.succeed();
            });
        });
        helper.runAtTickTime(20, () -> guarded(helper, completed, () -> {
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
            started.set(true);
        }));
        helper.runAtTickTime(300, () -> {
            if (!completed.get()) helper.fail("Native maglev compatibility assertion was not reached");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 500, attempts = 1)
    public void reinforcedBulletTrainCapsMaximumSpeedOnLiveNativeCurve(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 3);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.buildCurved(level, origin,
                MaglevContent.REINFORCED_TRACK, MaglevContent.REINFORCED_MATERIAL,
                "gametest-bullet-curve", MaglevContent.BULLET_BOGEY);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin.offset(38, 0, 22));
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean sawCurve = new AtomicBoolean();
        AtomicBoolean sawPowered = new AtomicBoolean();
        AtomicBoolean sawMotionOnCurve = new AtomicBoolean();
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<UUID> carriageId = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();
        AtomicInteger ticksAfterCurve = new AtomicInteger();
        AtomicReference<Double> curveSpeedExcess = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        AtomicReference<Double> curveEnvelopeExcess = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        AtomicReference<Double> curveLimit = new AtomicReference<>(Double.POSITIVE_INFINITY);

        helper.onEachTick(() -> {
            if (!started.get() || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                TrainTraction traction = ((TractionState) train).maglev$traction();
                sawPowered.set(sawPowered.get() || traction.powered());
                UUID liveCarriageId = carriageId.get();
                if (liveCarriageId == null) {
                    if (carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                        throw new AssertionError("Curved native train did not register its live carriage after assembly");
                    }
                    liveCarriageId = captureRegisteredCarriageId(level, train);
                    if (liveCarriageId == null) return;
                    carriageId.set(liveCarriageId);
                }
                CarriageContraptionEntity entity = requireLiveCarriage(level, train, liveCarriageId);
                Carriage liveCarriage = entity.getCarriage();
                boolean onCurve = isTurn(liveCarriage.getLeadingPoint()) || isTurn(liveCarriage.getTrailingPoint());
                if (onCurve) {
                    sawCurve.set(true);
                    ticksAfterCurve.set(0);
                    double speed = Math.abs(train.speed);
                    // The radius-based limit of the tightest curve under any carriage point this tick.
                    double limit = Math.min(curveLimitAt(liveCarriage.getLeadingPoint()), curveLimitAt(liveCarriage.getTrailingPoint()));
                    curveLimit.set(Math.min(curveLimit.get(), limit));
                    curveSpeedExcess.set(Math.max(curveSpeedExcess.get(), speed - limit));
                    curveEnvelopeExcess.set(Math.max(curveEnvelopeExcess.get(), traction.speedLimit() - limit));
                    sawMotionOnCurve.set(sawMotionOnCurve.get() || speed > 0.001);
                } else if (sawCurve.get()) {
                    ticksAfterCurve.incrementAndGet();
                }

                if (sawCurve.get() && ticksAfterCurve.get() >= 3) {
                    completed.set(true);
                    helper.assertTrue(entity.isAlive() && level.getEntity(liveCarriageId) == entity,
                            "The curve observation must come from the same registered live carriage");
                    helper.assertTrue(sawPowered.get(), "Native NewAge supply must power the reinforced bullet train");
                    helper.assertTrue(sawMotionOnCurve.get(), "The live carriage must move while its travelling point is on a Bezier turn");
                    helper.assertTrue(curveSpeedExcess.get() <= 0.001,
                            "Bullet speed on a live curve must obey that curve's radius-based limit " + curveLimit.get()
                                    + "; worst excess " + curveSpeedExcess.get());
                    helper.assertTrue(curveEnvelopeExcess.get() <= 0.001,
                            "Native traction's speed envelope must not exceed the curve's radius-based limit while on it");
                    source.stopGeneration();
                    source.removeOwnedTickets();
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(45, () -> guarded(helper, completed, () -> {
            helper.assertTrue(source.hasLiveCopperWire(), "Native NewAge copper wire must be live before train assembly");
            helper.assertTrue(source.substation().energyStored() > 0, "NewAge must charge the reinforced bullet train substation");
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
            started.set(true);
        }));
        helper.runAtTickTime(460, () -> {
            if (!completed.get()) helper.fail("Reinforced bullet train did not traverse a live native curve within its maximum speed limit");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 300, attempts = 1)
    public void maglevPersistsTierLiftStyleAndMaterialOnItsDedicatedTrack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 4);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin,
                MaglevContent.MAGLEV_TRACK, MaglevContent.MAGLEV_MATERIAL,
                "gametest-maglev", MaglevContent.MAGLEV_BOGEY, false);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin.offset(37, 0, 48));
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<UUID> carriageId = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();

        helper.onEachTick(() -> {
            if (!started.get() || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                TrainTraction traction = ((TractionState) train).maglev$traction();
                UUID liveCarriageId = carriageId.get();
                if (liveCarriageId == null) {
                    if (carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                        throw new AssertionError("Native maglev did not register a live carriage after assembly");
                    }
                    liveCarriageId = captureRegisteredCarriageId(level, train);
                    if (liveCarriageId == null) return;
                    carriageId.set(liveCarriageId);
                }
                CarriageContraptionEntity entity = requireLiveCarriage(level, train, liveCarriageId);
                if (!traction.authoritative() || !traction.powered() || traction.lift() < 0.25f) return;

                completed.set(true);
                helper.assertTrue(entity instanceof LevitationData
                                && ((LevitationData) entity).maglev$lift() == traction.lift(),
                        "Native carriage body alignment must use the authoritative lift offset");
                var anchors = entity.getCarriage().getDimensionalIfPresent(level.dimension());
                helper.assertTrue(anchors != null && anchors.positionAnchor != null, "Native carriage must retain its track position anchor");
                var point = entity.getCarriage().getLeadingPoint();
                var normal = point.edge.getNormal(train.graph, point.position / point.edge.getLength());
                var displacement = entity.position().subtract(anchors.positionAnchor);
                helper.assertTrue(displacement.dot(normal) > 0.1
                                && displacement.distanceToSqr(normal.scale(traction.lift())) < 0.000001,
                        "Live carriage position must physically rise above its track anchor by the commanded normal offset: " + displacement);
                helper.assertTrue(traction.tier() == TrainTier.MAGLEV,
                        "The registered native train must classify as the maglev tier");
                helper.assertTrue(traction.speedLimit() <= TrainTier.MAGLEV.topSpeed(),
                        "Maglev train must obey its actual tier ceiling");
                helper.assertTrue(Create.RAILWAYS.trackNetworks.get(train.graph.id) == train.graph,
                        "Native train graph must remain registered for persistence lookup");

                Carriage originalCarriage = train.carriages.get(0);
                ResourceLocation originalStyle = originalCarriage.leadingBogey().getStyle().id;
                var originalEdge = originalCarriage.leadingBogey().leading().edge;
                helper.assertTrue(originalEdge != null && originalEdge.getTrackMaterial().id.equals(MaglevContent.MAGLEV_MATERIAL.id),
                        "The live maglev bogey must occupy its dedicated native track material");

                DimensionPalette dimensions = new DimensionPalette();
                CompoundTag saved = train.write(dimensions);
                dimensions.write(saved);
                helper.assertTrue(saved.contains("MaglevLift") && saved.getFloat("MaglevLift") == traction.lift(),
                        "Native Train save data must preserve the current maglev lift");
                Train restored = Train.read(saved, Create.RAILWAYS.trackNetworks, DimensionPalette.read(saved));
                TrainTraction restoredTraction = ((TractionState) restored).maglev$traction();
                helper.assertTrue(restored.id.equals(train.id), "Native Train read must preserve the registered train identity");
                helper.assertTrue(restoredTraction.tier() == TrainTier.MAGLEV,
                        "Native Train read must recover the maglev tier from its bogey style");
                helper.assertTrue(restoredTraction.lift() == traction.lift(), "Native Train read must preserve levitation lift");
                helper.assertTrue(!restoredTraction.powered() && !restoredTraction.authoritative(),
                        "Loaded traction must be unpowered until native ticks recompute real supply");
                CarriageBogey restoredBogey = restored.carriages.get(0).leadingBogey();
                helper.assertTrue(restoredBogey.getStyle().id.equals(originalStyle),
                        "Native Train read must preserve the bogey style");
                var restoredEdge = restoredBogey.leading().edge;
                helper.assertTrue(restoredEdge != null
                                && restoredEdge.getTrackMaterial().id.equals(MaglevContent.MAGLEV_MATERIAL.id),
                        "Native Train read with the real graph map and dimension palette must preserve track material");
                source.stopGeneration();
                source.removeOwnedTickets();
                helper.succeed();
            });
        });
        helper.runAtTickTime(45, () -> guarded(helper, completed, () -> {
            helper.assertTrue(source.hasLiveCopperWire(), "Native NewAge network must be live before maglev assembly");
            helper.assertTrue(source.substation().energyStored() > 0, "Native NewAge network must charge maglev before assembly");
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
            started.set(true);
        }));
        helper.runAtTickTime(270, () -> {
            if (!completed.get()) helper.fail("Native powered maglev did not establish lift and round-trip its native train state");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", timeoutTicks = 900, attempts = 1)
    public void oneRealSubstationPowersStationAssembledElectricAndMaglevUntilExactExhaustion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 5);
        NativeTrainFixture.BuiltTrain electricBuilt = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-shared-electric", MaglevContent.ELECTRIC_BOGEY, false);
        NativeTrainFixture.BuiltTrain maglevBuilt = NativeTrainFixture.build(level, origin.offset(16, 0, 0),
                MaglevContent.MAGLEV_TRACK, MaglevContent.MAGLEV_MATERIAL,
                "gametest-shared-maglev", MaglevContent.MAGLEV_BOGEY, false);
        NativeNewAgePowerFixture source = NativeNewAgePowerFixture.build(level, origin.offset(37, 0, 48));
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean observedBothPowered = new AtomicBoolean();
        AtomicBoolean observedInsufficientBuffer = new AtomicBoolean();
        AtomicReference<UUID> electricTrainId = new AtomicReference<>();
        AtomicReference<UUID> maglevTrainId = new AtomicReference<>();
        AtomicReference<UUID> electricCarriageId = new AtomicReference<>();
        AtomicReference<UUID> maglevCarriageId = new AtomicReference<>();
        AtomicInteger carriageWaitTicks = new AtomicInteger();
        AtomicLong electricDemand = new AtomicLong();
        AtomicLong maglevDemand = new AtomicLong();
        AtomicLong previousCharge = new AtomicLong();
        AtomicLong previousServerTick = new AtomicLong();
        AtomicLong totalExpectedDebit = new AtomicLong();
        AtomicLong totalObservedDebit = new AtomicLong();

        helper.onEachTick(() -> {
            if (completed.get()) return;
            guarded(helper, completed, () -> {
                if (!started.get()) {
                    if (source.substation().energyStored() != MaglevConfig.substationCapacity()) return;
                    helper.assertTrue(source.hasLiveCopperWire(), "The one native generator must fill the shared substation before isolation");
                    source.stopGeneration();
                    source.disconnect();
                    helper.assertTrue(!source.hasLiveCopperWire(), "The real NewAge wire must be detached before shared-train debit is measured");
                    helper.assertTrue(source.substation().energyStored() == MaglevConfig.substationCapacity(),
                            "The shared substation must begin with its naturally observed full buffer");

                    Train electricTrain = electricBuilt.assemble();
                    Train maglevTrain = maglevBuilt.assemble();
                    requireRegisteredTrain(electricTrain.id, electricTrain);
                    requireRegisteredTrain(maglevTrain.id, maglevTrain);
                    electricTrainId.set(electricTrain.id);
                    maglevTrainId.set(maglevTrain.id);
                    helper.assertTrue(electricTrain.carriages.size() == 1 && maglevTrain.carriages.size() == 1,
                            "Both separately assembled station consists must contain one native carriage");
                    electricDemand.set(stationaryPoweredBogeyDemand(electricTrain, TrainTier.ELECTRIC));
                    maglevDemand.set(stationaryPoweredBogeyDemand(maglevTrain, TrainTier.MAGLEV));
                    helper.assertTrue(electricDemand.get() == 2 * TrainTier.ELECTRIC.energyCost(0),
                            "The electric fixture must expose its two actual powered bogey demands");
                    helper.assertTrue(maglevDemand.get() == 2 * TrainTier.MAGLEV.energyCost(0),
                            "The maglev fixture must expose its two actual powered bogey demands");
                    helper.assertTrue(Math.abs(electricTrain.speed) < 0.001 && Math.abs(maglevTrain.speed) < 0.001,
                            "Both station-assembled consists must begin stationary");
                    previousCharge.set(source.substation().energyStored());
                    previousServerTick.set(level.getServer().getTickCount());
                    started.set(true);
                    return;
                }

                Train electricTrain = requireRegisteredTrain(electricTrainId.get(), null);
                Train maglevTrain = requireRegisteredTrain(maglevTrainId.get(), null);
                TrainTraction electricTraction = ((TractionState) electricTrain).maglev$traction();
                TrainTraction maglevTraction = ((TractionState) maglevTrain).maglev$traction();
                if (electricCarriageId.get() == null) {
                    UUID found = captureRegisteredCarriageId(level, electricTrain);
                    if (found != null) electricCarriageId.set(found);
                }
                if (maglevCarriageId.get() == null) {
                    UUID found = captureRegisteredCarriageId(level, maglevTrain);
                    if (found != null) maglevCarriageId.set(found);
                }
                if ((electricCarriageId.get() == null || maglevCarriageId.get() == null)
                        && carriageWaitTicks.incrementAndGet() > LIVE_CARRIAGE_GRACE_TICKS) {
                    throw new AssertionError("Both station-assembled trains must register live UUID-addressable carriages");
                }
                if (electricCarriageId.get() != null) {
                    requireLiveCarriage(level, electricTrain, electricCarriageId.get());
                }
                if (maglevCarriageId.get() != null) {
                    requireLiveCarriage(level, maglevTrain, maglevCarriageId.get());
                }
                helper.assertTrue(Math.abs(electricTrain.speed) < 0.001 && Math.abs(maglevTrain.speed) < 0.001,
                        "Shared-substation energy demand must be measured at stable zero speed");

                long currentTick = level.getServer().getTickCount();
                if (currentTick <= previousServerTick.get()) return;
                if (currentTick != previousServerTick.get() + 1) {
                    throw new AssertionError("The shared-substation GameTest must observe every native server tick");
                }
                long before = previousCharge.get();
                long charge = source.substation().energyStored();
                helper.assertTrue(charge >= 0, "Substation charge must never become negative");
                helper.assertTrue(charge <= before, "Stopped and disconnected generation must never refill the measured buffer");
                long actualDebit = before - charge;
                boolean electricPowered = electricTraction.powered();
                boolean maglevPowered = maglevTraction.powered();
                long expectedDebit = (electricPowered ? electricDemand.get() : 0)
                        + (maglevPowered ? maglevDemand.get() : 0);
                helper.assertTrue(actualDebit == expectedDebit,
                        "Each stable zero-speed debit must equal the sum of actual powered bogey demands");
                if (electricPowered && maglevPowered) {
                    observedBothPowered.set(true);
                    helper.assertTrue(before >= electricDemand.get() + maglevDemand.get(),
                            "Both native consists must not be powered when the available pre-tick buffer cannot cover their combined demand");
                }
                if (before < electricDemand.get() + maglevDemand.get() && !(electricPowered && maglevPowered)) {
                    observedInsufficientBuffer.set(true);
                }
                totalExpectedDebit.addAndGet(expectedDebit);
                totalObservedDebit.addAndGet(actualDebit);
                previousCharge.set(charge);
                previousServerTick.set(currentTick);

                if (charge == 0 && !electricPowered && !maglevPowered) {
                    completed.set(true);
                    helper.assertTrue(observedBothPowered.get(), "The charged shared substation must initially power both native trains");
                    helper.assertTrue(observedInsufficientBuffer.get(), "Natural depletion must reach a buffer too small to power both consists");
                    helper.assertTrue(totalExpectedDebit.get() == MaglevConfig.substationCapacity(),
                            "Total powered bogey demand through natural exhaustion must exactly equal the initial capacity");
                    helper.assertTrue(totalObservedDebit.get() == MaglevConfig.substationCapacity(),
                            "Measured native traction debit through natural exhaustion must exactly equal the initial capacity");
                    helper.assertTrue(source.substation().energyStored() == 0,
                            "Both stationary trains must naturally exhaust, not setter-drain, the shared charge");
                    source.removeOwnedTickets();
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(800, () -> {
            if (!completed.get()) helper.fail("Two native station-assembled consists did not consume the isolated shared buffer to exact exhaustion");
        });
    }

    @GameTest(template = "create_maglev_tests:arena", batch = SUPPLY_BATCH, timeoutTicks = 1000, attempts = 1)
    public void scheduledTrainKeepsTractionAfterItsChunksUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 6);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-unloaded", MaglevContent.ELECTRIC_BOGEY, false);
        // The start supply covers only the station end; the remote supply alone covers the middle and destination.
        // Both lie beyond every query the parked train makes (pickups plus its short standstill look-ahead), so
        // their chunks genuinely unload; the coverage spheres still overlap around z+24..z+34.
        BlockPos startSupply = origin.offset(4, 0, -30);
        BlockPos remoteSupply = origin.offset(4, 0, 88);
        List<BlockPos> substations = List.of(startSupply, remoteSupply);
        long[] charged = new long[substations.size()];
        for (int i = 0; i < substations.size(); i++) {
            BlockPos pos = substations.get(i);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, TractionPower.SUBSTATION.defaultBlockState(), 3);
            charged[i] = chargeThroughEnergyApi(level, pos, TEST_CHARGE);
        }
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicReference<GlobalStation> destination = new AtomicReference<>();
        AtomicBoolean scheduled = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean poweredWhileUnloaded = new AtomicBoolean();
        AtomicInteger conductorWaitTicks = new AtomicInteger();

        helper.runAtTickTime(20, () -> guarded(helper, completed, () -> {
            for (long stored : charged) {
                helper.assertTrue(stored == TEST_CHARGE, "Each substation must accept charge through the public energy API");
            }
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            destination.set(built.destinationStation.getStation());
            helper.assertTrue(destination.get() != null, "The destination must be a native graph station before unloading");
        }));
        helper.onEachTick(() -> {
            if (trainId.get() == null || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                boolean remoteUnloaded = !chunkLoaded(level, built.leadingBogeyPos) && !chunkLoaded(level, built.destinationRailPos)
                        && !chunkLoaded(level, remoteSupply);
                if (!scheduled.get()) {
                    // Create learns a carriage's conductors only from its loaded entity; navigation needs them.
                    if (!train.hasForwardConductor() && !train.hasBackwardConductor()) {
                        if (conductorWaitTicks.incrementAndGet() > 60) {
                            throw new AssertionError("Create never registered the assembled train's conductors");
                        }
                        return;
                    }
                    // Depart as an unattended train: nothing keeps the route or substation chunks loaded from now on.
                    scheduleByName(train, built.destinationName);
                    built.releaseRecordingTickets();
                    scheduled.set(true);
                    return;
                }
                TrainTraction traction = ((TractionState) train).maglev$traction();
                double frontZ = Math.max(train.carriages.get(0).getLeadingPoint().getPosition(train.graph).z,
                        train.carriages.get(0).getTrailingPoint().getPosition(train.graph).z);
                // Beyond the start supply's 64-block sphere only the unloaded remote substation covers the route.
                if (remoteUnloaded && traction.powered() && frontZ > startSupply.getZ() + 70) poweredWhileUnloaded.set(true);
                if (train.getCurrentStation() == destination.get() && train.navigation.destination == null
                        && Math.abs(train.speed) < 0.001) {
                    completed.set(true);
                    helper.assertTrue(poweredWhileUnloaded.get(),
                            "A substation in an unloaded chunk must keep supplying a train running through unloaded chunks");
                    helper.assertTrue(!chunkLoaded(level, remoteSupply),
                            "The remote supplying substation must still be unloaded when the train arrives");
                    helper.assertTrue(TractionPower.storedEnergy(level, remoteSupply) < TEST_CHARGE,
                            "The unloaded remote substation's persistent charge must pay for the trip it powered");
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(950, () -> {
            if (completed.get()) return;
            Train train = trainId.get() == null ? null : Create.RAILWAYS.trains.get(trainId.get());
            String state = "no train";
            if (train != null) {
                TrainTraction traction = ((TractionState) train).maglev$traction();
                state = "scheduled=" + scheduled.get() + " speed=" + train.speed + " target=" + train.targetSpeed
                        + " frontZ-origin=" + (train.carriages.get(0).getTrailingPoint().getPosition(train.graph).z - origin.getZ())
                        + " navigating=" + (train.navigation.destination != null) + " station=" + train.getCurrentStation()
                        + " powered=" + traction.powered() + " status=" + traction.status()
                        + " speedLimit=" + traction.speedLimit() + " powerLimit=" + traction.powerLimit()
                        + " startEnergy=" + TractionPower.storedEnergy(level, startSupply)
                        + " remoteEnergy=" + TractionPower.storedEnergy(level, remoteSupply)
                        + " startLoaded=" + chunkLoaded(level, startSupply) + " remoteLoaded=" + chunkLoaded(level, remoteSupply);
            }
            helper.fail("A scheduled electric train stopped where its only supply was a substation in an unloaded chunk: " + state);
        });
    }

    @GameTest(template = "create_maglev_tests:arena", batch = SUPPLY_BATCH, timeoutTicks = 700, attempts = 1)
    public void chargedSubstationPowersTrainWhenNearestSubstationIsEmpty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 7);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-shadowed", MaglevContent.ELECTRIC_BOGEY, false);
        BlockPos empty = origin.offset(-3, 0, 7);
        BlockPos charged = origin.offset(-3, 0, 40);
        for (BlockPos pos : List.of(empty, charged)) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, TractionPower.SUBSTATION.defaultBlockState(), 3);
        }
        long stored = chargeThroughEnergyApi(level, charged, TEST_CHARGE);
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean sawPower = new AtomicBoolean();

        helper.runAtTickTime(20, () -> guarded(helper, completed, () -> {
            helper.assertTrue(stored == TEST_CHARGE, "The farther substation must accept charge through the public energy API");
            helper.assertTrue(storedEnergy(level, empty) == 0, "The nearest substation must start empty");
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
        }));
        helper.onEachTick(() -> {
            if (trainId.get() == null || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                if (((TractionState) train).maglev$traction().powered()) sawPower.set(true);
                if (train.getCurrentStation() == built.destinationStation.getStation() && train.navigation.destination == null
                        && Math.abs(train.speed) < 0.001) {
                    completed.set(true);
                    helper.assertTrue(sawPower.get(), "A charged substation in range must power the train");
                    helper.assertTrue(storedEnergy(level, empty) == 0, "The empty substation cannot have supplied energy");
                    helper.assertTrue(storedEnergy(level, charged) < TEST_CHARGE, "The charged substation must supply the trip");
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(650, () -> {
            if (!completed.get()) helper.fail("An empty nearest substation blocked a charged substation that also covers the train");
        });
    }

    @BeforeBatch(batch = WEAK_SUPPLY_BATCH)
    public static void rateSubstationsWeakly(ServerLevel level) {
        MaglevConfig.SUBSTATION_OUTPUT.set(WEAK_SUPPLY_OUTPUT);
    }

    @AfterBatch(batch = WEAK_SUPPLY_BATCH)
    public static void restoreSubstationRating(ServerLevel level) {
        MaglevConfig.SUBSTATION_OUTPUT.set(MaglevConfig.SUBSTATION_OUTPUT.getDefault());
    }

    @GameTest(template = "create_maglev_tests:arena", batch = WEAK_SUPPLY_BATCH, timeoutTicks = 1200, attempts = 1)
    public void weakSubstationOutputCapsSpeedInsteadOfStoppingTheTrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = isolatedOrigin(helper, 11);
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin, AllBlocks.TRACK.get(),
                TrackMaterial.ANDESITE, "gametest-weak-supply", MaglevContent.ELECTRIC_BOGEY, false);
        BlockPos substation = origin.offset(4, 0, 48);
        level.setBlock(substation.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(substation, TractionPower.SUBSTATION.defaultBlockState(), 3);
        long stored = chargeThroughEnergyApi(level, substation, TEST_CHARGE);
        // Two electric bogeys idle at 2 * idleEnergy; whatever the rating leaves over pays the speed-squared term.
        double sustainable = Math.sqrt((WEAK_SUPPLY_OUTPUT - 2.0 * TrainTier.ELECTRIC.idleEnergy())
                / (2.0 * TrainTier.ELECTRIC.speedEnergyCoefficient()));
        AtomicReference<UUID> trainId = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean sawWeakSupply = new AtomicBoolean();
        AtomicLong previousCharge = new AtomicLong(-1);
        AtomicLong largestDebit = new AtomicLong();
        AtomicReference<Double> maxSpeed = new AtomicReference<>(0d);

        helper.runAtTickTime(20, () -> guarded(helper, completed, () -> {
            helper.assertTrue(stored == TEST_CHARGE, "The weak substation must accept charge through the public energy API");
            helper.assertTrue(MaglevConfig.substationOutput() == WEAK_SUPPLY_OUTPUT, "The batch must rate substations weakly");
            Train assembled = built.assemble();
            requireRegisteredTrain(assembled.id, assembled);
            trainId.set(assembled.id);
            built.scheduleToDestination();
        }));
        helper.onEachTick(() -> {
            if (trainId.get() == null || completed.get()) return;
            guarded(helper, completed, () -> {
                Train train = requireRegisteredTrain(trainId.get(), null);
                TrainTraction traction = ((TractionState) train).maglev$traction();
                long charge = TractionPower.storedEnergy(level, substation);
                if (previousCharge.get() >= 0) largestDebit.set(Math.max(largestDebit.get(), previousCharge.get() - charge));
                previousCharge.set(charge);
                maxSpeed.set(Math.max(maxSpeed.get(), Math.abs(train.speed)));
                if (traction.powered() && traction.powerFraction() < 1) sawWeakSupply.set(true);
                if (train.getCurrentStation() == built.destinationStation.getStation() && train.navigation.destination == null
                        && Math.abs(train.speed) < 0.001) {
                    completed.set(true);
                    helper.assertTrue(largestDebit.get() <= WEAK_SUPPLY_OUTPUT,
                            "A substation delivered " + largestDebit.get() + " in one tick, above its rated " + WEAK_SUPPLY_OUTPUT);
                    helper.assertTrue(sawWeakSupply.get(), "Traction must report a partial supply under the weak rating");
                    helper.assertTrue(maxSpeed.get() <= sustainable + 0.02,
                            "Weak supply must cap speed at the sustainable " + sustainable + ", saw " + maxSpeed.get());
                    helper.assertTrue(maxSpeed.get() >= 0.8 * sustainable,
                            "Weak supply must still run the train near " + sustainable + ", saw " + maxSpeed.get());
                    helper.succeed();
                }
            });
        });
        helper.runAtTickTime(1150, () -> {
            if (!completed.get()) helper.fail("A train on a weakly rated substation stopped instead of running at reduced speed");
        });
    }

    private static BlockPos isolatedOrigin(GameTestHelper helper, int region) {
        return helper.absolutePos(new BlockPos(512 + region * REGION_SPACING, 64, 512));
    }

    private static Train requireRegisteredTrain(UUID id, Train expected) {
        if (id == null) throw new AssertionError("Native train UUID is not available");
        Train train = Create.RAILWAYS.trains.get(id);
        if (train == null || !id.equals(train.id) || expected != null && train != expected) {
            throw new AssertionError("Native train UUID is absent from Create's live railway registry");
        }
        return train;
    }

    private static UUID captureRegisteredCarriageId(ServerLevel level, Train train) {
        if (train.carriages.isEmpty()) throw new AssertionError("Registered native train has no carriage");
        CarriageContraptionEntity entity = train.carriages.get(0).anyAvailableEntity();
        if (entity == null) return null;
        UUID id = entity.getUUID();
        if (level.getEntity(id) != entity) {
            throw new AssertionError("Create carriage reference is not present in the server UUID registry");
        }
        requireLiveCarriage(level, train, id);
        return id;
    }

    private static CarriageContraptionEntity requireLiveCarriage(ServerLevel level, Train train, UUID id) {
        Entity registered = id == null ? null : level.getEntity(id);
        if (!(registered instanceof CarriageContraptionEntity carriage)
                || !carriage.isAlive()
                || !id.equals(carriage.getUUID())
                || !train.id.equals(carriage.trainId)
                || carriage.getCarriage() == null
                || carriage.getCarriage().train != train) {
            throw new AssertionError("Native carriage is not alive and linked to its registered train by UUID");
        }
        return carriage;
    }

    private static boolean isTurn(TravellingPoint point) {
        return point.edge != null && point.edge.isTurn();
    }

    private static double curveLimitAt(TravellingPoint point) {
        return isTurn(point) ? TrainTraction.curveSpeedLimit(point.edge.getTurn()) : Double.POSITIVE_INFINITY;
    }

    private static long stationaryPoweredBogeyDemand(Train train, TrainTier expectedTier) {
        long demand = 0;
        for (Carriage carriage : train.carriages) {
            CarriageBogey leading = carriage.leadingBogey();
            if (bogeyTier(leading) == expectedTier) demand += expectedTier.energyCost(0);
            if (carriage.isOnTwoBogeys() && bogeyTier(carriage.trailingBogey()) == expectedTier) {
                demand += expectedTier.energyCost(0);
            }
        }
        return demand;
    }

    private static TrainTier bogeyTier(CarriageBogey bogey) {
        Block block = bogey.getStyle().getBlockForSize(bogey.getSize());
        return block instanceof PropulsionBogeyBlock propulsion ? propulsion.tier() : TrainTier.WORKSHOP;
    }

    private static void guarded(GameTestHelper helper, AtomicBoolean completed, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (completed != null) completed.set(true);
            helper.fail(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static long chargeThroughEnergyApi(ServerLevel level, BlockPos pos, long amount) {
        EnergyStorage storage = EnergyStorage.findForBlock(level, pos, Direction.UP);
        if (storage == null) throw new AssertionError("No public energy storage at substation " + pos);
        try (Transaction transaction = TransactionStack.get().openOuter()) {
            long inserted = storage.insert(amount, transaction);
            transaction.commit();
            return inserted;
        }
    }

    private static long storedEnergy(ServerLevel level, BlockPos pos) {
        EnergyStorage storage = EnergyStorage.findForBlock(level, pos, Direction.UP);
        if (storage == null) throw new AssertionError("No public energy storage at substation " + pos);
        return storage.getStoredEnergy();
    }

    private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** Name-based native schedule; the destination station block entity may be unloaded. */
    private static void scheduleByName(Train train, String stationName) {
        Schedule schedule = new Schedule();
        ScheduleEntry entry = new ScheduleEntry();
        DestinationInstruction instruction = new DestinationInstruction();
        CompoundTag data = instruction.getData().copy();
        data.putString("Text", stationName);
        instruction.setData(data);
        entry.instruction = instruction;
        schedule.entries.add(entry);
        train.runtime.setSchedule(schedule, true);
    }
}
