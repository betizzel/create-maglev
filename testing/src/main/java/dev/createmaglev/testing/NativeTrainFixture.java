package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPackets;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.createmod.catnip.data.Couple;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TrainPacket;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.createmaglev.power.TractionPower;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Isolated, world-backed railway fixtures using Create's actual track graph and assembly path. */
public final class NativeTrainFixture {
    public static final int ROUTE_LENGTH = 112;
    public static final int DESTINATION_OFFSET = 96;
    private static final int BOGEY_SPACING = 12;
    public static final BlockPos CONTROL_OFFSET = new BlockPos(0, 1, BOGEY_SPACING / 2);
    private static final int ROUTE_MARGIN = 24;
    private static final int SUBSTATION_SPACING = 88;

    private record RouteLocation(Vec3 position, Vec3 tangent) {}

    private record ForcedChunk(int x, int z) {}

    public static final BlockPos RIDER_SEAT_OFFSET = new BlockPos(0, 2, 5);

    private NativeTrainFixture() {}

    public static final class BuiltTrain {
        public final ServerLevel level;
        public final BlockPos origin;
        public final TrackBlock trackBlock;
        public final TrackMaterial material;
        public final BlockPos startStationPos;
        public BlockPos destinationStationPos;
        public BlockPos destinationRailPos;
        public final String destinationName;
        public final BlockPos leadingBogeyPos;
        public final BlockPos trailingBogeyPos;
        public final BlockPos controlsLocalPos;
        public StationBlockEntity startStation;
        public StationBlockEntity destinationStation;
        public Train train;
        public BezierConnection turn;
        public final List<BlockPos> substationPositions = new ArrayList<>();
        private final List<ForcedChunk> forcedChunks = new ArrayList<>();


        private BuiltTrain(ServerLevel level, BlockPos origin, TrackBlock trackBlock, TrackMaterial material,
                           String stationPrefix, BlockPos destinationStationPos, BlockPos destinationRailPos) {
            this.level = level;
            this.origin = origin;
            this.trackBlock = trackBlock;
            this.material = material;
            this.startStationPos = origin.offset(3, 0, 0);
            this.destinationStationPos = destinationStationPos;
            this.destinationRailPos = destinationRailPos;
            this.destinationName = stationPrefix + "-destination";
            this.leadingBogeyPos = origin.above().relative(Direction.SOUTH, 1);
            this.trailingBogeyPos = leadingBogeyPos.relative(Direction.SOUTH, BOGEY_SPACING);
            this.controlsLocalPos = CONTROL_OFFSET;
        }

        /** Must run after Create has ticked the native rails and station track-target behaviors. */
        public Train assemble() {
            if (startStation == null || destinationStation == null) {
                startStation = station(startStationPos, origin, destinationName + "-start");
                destinationStation = station(destinationStationPos, destinationRailPos, destinationName);
            }
            if (startStation.edgePoint.createEdgePoint() == null || destinationStation.edgePoint.createEdgePoint() == null) {
                throw new IllegalStateException("Create did not create native station edge points on the loaded route");
            }
            startStation.updateName(destinationName + "-start");
            destinationStation.updateName(destinationName);
            if (!startStation.enterAssemblyMode(null)) {
                throw new IllegalStateException("Native Create station refused to enter assembly mode");
            }
            startStation.assemble(null);
            if (startStation.getStation() == null || startStation.getStation().getPresentTrain() == null) {
                throw new IllegalStateException("Native Create station assembly did not produce a live train");
            }
            train = startStation.getStation().getPresentTrain();
            // Match the successful StationEditPacket path before native navigation starts.
            startStation.exitAssemblyMode();
            if (train.carriages.isEmpty()) {
                throw new IllegalStateException("Native assembled train has no carriage data");
            }
            return train;
        }

        public void scheduleToDestination() {
            if (train == null || destinationStation == null || destinationStation.getStation() == null) {
                throw new IllegalStateException("Both native stations and the assembled train must exist before scheduling");
            }
            Schedule schedule = new Schedule();
            ScheduleEntry entry = new ScheduleEntry();
            DestinationInstruction destination = new DestinationInstruction();
            CompoundTag data = destination.getData().copy();
            data.putString("Text", destinationName);
            destination.setData(data);
            entry.instruction = destination;
            schedule.entries.add(entry);
            train.runtime.setSchedule(schedule, true);
        }

        /** Explicit native server action used only for the recorded manual-control scene. */
        public CarriageContraptionEntity activateNativeControls(ServerPlayer player) {
            if (train == null || train.carriages.isEmpty()) throw new IllegalStateException("No live train to control");
            Carriage carriage = train.carriages.get(0);
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) throw new IllegalStateException("The live carriage entity disappeared before control activation");
            player.teleportTo(level, origin.getX() + 0.5, origin.getY() + 3,
                    leadingBogeyPos.getZ() + BOGEY_SPACING / 2 + 0.5, 0, 20);
            boolean handled = entity.handlePlayerInteraction(player, controlsLocalPos, Direction.UP, InteractionHand.MAIN_HAND);
            if (!handled || entity.getControllingPlayer().filter(player.getUUID()::equals).isEmpty()) {
                throw new IllegalStateException("Create's native controls interaction did not authorize this player");
            }
            return entity;
        }

        public CarriageContraptionEntity liveCarriage() {
            if (train == null || train.carriages.isEmpty()) return null;
            return train.carriages.get(0).anyAvailableEntity();
        }

        /** Removes only this recorded fixture's native train and all its live carriage entities. */
        public void discardRecordingTrain() {
            if (train == null) return;
            train.navigation.cancelNavigation();
            train.leaveStation();
            for (Carriage carriage : train.carriages) {
                carriage.forEachPresentEntity(CarriageContraptionEntity::discard);
            }
            Create.RAILWAYS.removeTrain(train.id);
            AllPackets.getChannel().sendToClientsInServer(new TrainPacket(train, false), level.getServer());
            Create.RAILWAYS.markTracksDirty();
            train = null;
        }

        /** Releases exactly the straight-route chunks owned by a recorded scene. */
        public void releaseRecordingTickets() {
            for (int x = (origin.getX() - 24) >> 4; x <= (origin.getX() + 24) >> 4; x++) {
                for (int z = (origin.getZ() - 24) >> 4; z <= (origin.getZ() + ROUTE_LENGTH + 24) >> 4; z++) {
                    level.setChunkForced(x, z, false);
                }
            }
        }
        /** Releases exactly the chunks forced for a route fixture. */
        public void releaseForcedChunks() {
            for (ForcedChunk chunk : forcedChunks) {
                level.setChunkForced(chunk.x(), chunk.z(), false);
            }
        }


        public StationBlockEntity station(BlockPos stationPos, BlockPos railPos, String name) {
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof StationBlockEntity station)) {
                throw new IllegalStateException("Expected a native Create StationBlockEntity at " + stationPos);
            }
            CompoundTag target = new CompoundTag();
            target.put("TargetTrack", NbtUtils.writeBlockPos(railPos.subtract(stationPos)));
            target.putBoolean("TargetDirection", stationPos.equals(startStationPos));
            station.edgePoint.read(target, false);
            station.edgePoint.createEdgePoint();
            station.updateName(name);
            return station;
        }
    }

    public static void addPassengerSeat(BuiltTrain fixture) {
        BlockPos seat = fixture.leadingBogeyPos.offset(RIDER_SEAT_OFFSET);
        fixture.level.setBlock(seat, AllBlocks.SEATS.get(DyeColor.BLUE).getDefaultState(), 3);
        fixture.level.addFreshEntity(new SuperGlueEntity(fixture.level, SuperGlueEntity.span(seat.below(), seat)));
    }


    public static BuiltTrain build(ServerLevel level, BlockPos origin, TrackBlock trackBlock, TrackMaterial material,
                                    String stationPrefix, Block bogeyBlock, boolean controls) {
        forceOwnedRegion(level, origin, DESTINATION_OFFSET + 16);
        BuiltTrain fixture = new BuiltTrain(level, origin, trackBlock, material, stationPrefix,
                origin.offset(3, 0, DESTINATION_OFFSET), origin.relative(Direction.SOUTH, DESTINATION_OFFSET));
        BlockState rail = trackBlock.defaultBlockState().setValue(TrackBlock.SHAPE, TrackShape.ZO)
                .setValue(TrackBlock.HAS_BE, true);
        for (int offset = 0; offset <= ROUTE_LENGTH; offset++) {
            BlockPos pos = origin.relative(Direction.SOUTH, offset);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, rail, 3);
        }
        BlockState stationState = AllBlocks.TRACK_STATION.get().defaultBlockState();
        level.setBlock(fixture.startStationPos, stationState, 3);
        level.setBlock(fixture.destinationStationPos, stationState, 3);
        fixture.startStation = fixture.station(fixture.startStationPos, origin, stationPrefix + "-start");
        fixture.destinationStation = fixture.station(fixture.destinationStationPos,
                fixture.destinationRailPos, fixture.destinationName);

        placeTrainBody(level, fixture, bogeyBlock, controls);
        return fixture;
    }

    /** Creates a native Bezier turn between two straight live Create track sections. */
    public static BuiltTrain buildCurved(ServerLevel level, BlockPos origin, TrackBlock trackBlock,
                                          TrackMaterial material, String stationPrefix, Block bogeyBlock) {
        int endX = 104;
        int endZ = 40;
        BlockPos destinationRail = origin.offset(endX, 0, endZ);
        BlockPos destinationStation = destinationRail.relative(Direction.SOUTH);
        forceOwnedRegion(level, origin, 128);
        for (int chunkX = (origin.getX() - 24) >> 4; chunkX <= (origin.getX() + endX + 24) >> 4; chunkX++) {
            int minChunkZ = (origin.getZ() - 24) >> 4;
            int maxChunkZ = (origin.getZ() + endZ + 24) >> 4;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                level.getChunk(chunkX, chunkZ);
            }
        }
        BuiltTrain fixture = new BuiltTrain(level, origin, trackBlock, material, stationPrefix,
                destinationStation, destinationRail);
        BlockState base = trackBlock.defaultBlockState().setValue(TrackBlock.HAS_BE, true);
        BlockState northTrack = base.setValue(TrackBlock.SHAPE, TrackShape.ZO);
        for (int offset = 0; offset <= 30; offset++) {
            BlockPos pos = origin.relative(Direction.SOUTH, offset);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, northTrack, 3);
        }
        BlockState eastTrack = base.setValue(TrackBlock.SHAPE, TrackShape.XO);
        for (int offset = 10; offset <= endX; offset++) {
            BlockPos pos = origin.offset(offset, 0, endZ);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, eastTrack, 3);
        }
        BlockPos curveStartPos = origin.relative(Direction.SOUTH, 30);
        BlockPos curveEndPos = origin.offset(10, 0, endZ);
        Vec3 curveStart = trackBlock.getCurveStart(level, curveStartPos, northTrack, new Vec3(0, 0, 1));
        Vec3 curveEnd = trackBlock.getCurveStart(level, curveEndPos, eastTrack, new Vec3(-1, 0, 0));
        BezierConnection curve = new BezierConnection(
                Couple.create(curveStartPos, curveEndPos),
                Couple.create(curveStart, curveEnd),
                Couple.create(new Vec3(0, 0, 1), new Vec3(-1, 0, 0)),
                Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)),
                true, false, material);
        if (!(level.getBlockEntity(curveStartPos) instanceof TrackBlockEntity startCurve)
                || !(level.getBlockEntity(curveEndPos) instanceof TrackBlockEntity endCurve)) {
            throw new IllegalStateException("Native Create curve endpoints have no track block entities");
        }
        startCurve.addConnection(curve);
        endCurve.addConnection(curve.secondary());

        BlockState stationState = AllBlocks.TRACK_STATION.get().defaultBlockState();
        level.setBlock(fixture.startStationPos, stationState, 3);
        level.setBlock(fixture.destinationStationPos, stationState, 3);
        fixture.startStation = fixture.station(fixture.startStationPos, origin, stationPrefix + "-start");
        fixture.destinationStation = fixture.station(fixture.destinationStationPos, destinationRail, fixture.destinationName);
        placeTrainBody(level, fixture, bogeyBlock, false);
        return fixture;
    }

    /**
     * Builds a long native route: southbound approach, an optional south-to-east Bezier turn,
     * and an eastbound exit. Route chunks are forced only within the configured margin around
     * the path; callers release them with {@link BuiltTrain#releaseForcedChunks()}.
     */
    public static BuiltTrain buildRoute(ServerLevel level, BlockPos origin, TrackBlock trackBlock,
                                        TrackMaterial material, String stationPrefix, Block bogeyBlock,
                                        int approachLength, int curveRadius, int exitLength) {
        if (approachLength < 0 || curveRadius < 0 || exitLength < 0) {
            throw new IllegalArgumentException("Route segment lengths and radius must be non-negative");
        }

        boolean curved = curveRadius > 0;
        int straightLength = curved ? approachLength : approachLength + exitLength;
        int endX = curved ? curveRadius + exitLength : 0;
        int endZ = curved ? approachLength + curveRadius : straightLength;
        BlockPos destinationRail = origin.offset(endX, 0, endZ);
        BlockPos destinationStation = curved
                ? destinationRail.relative(Direction.SOUTH)
                : destinationRail.offset(3, 0, 0);
        BuiltTrain fixture = new BuiltTrain(level, origin, trackBlock, material, stationPrefix,
                destinationStation, destinationRail);
        forceRouteBounds(fixture, origin.getX() - ROUTE_MARGIN - 8, origin.getZ() - ROUTE_MARGIN - 8,
                destinationRail.getX() + ROUTE_MARGIN + 8, destinationRail.getZ() + ROUTE_MARGIN + 8);

        List<Vec3> routePoints = new ArrayList<>();
        BlockState base = trackBlock.defaultBlockState().setValue(TrackBlock.HAS_BE, true);
        BlockState southTrack = base.setValue(TrackBlock.SHAPE, TrackShape.ZO);
        for (int offset = 0; offset <= approachLength; offset++) {
            BlockPos pos = origin.relative(Direction.SOUTH, offset);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, southTrack, 3);
            routePoints.add(blockCenter(pos));
        }

        if (curved) {
            BlockState eastTrack = base.setValue(TrackBlock.SHAPE, TrackShape.XO);
            BlockPos curveStartPos = origin.relative(Direction.SOUTH, approachLength);
            BlockPos curveEndPos = origin.offset(curveRadius, 0, approachLength + curveRadius);
            for (int offset = 1; offset <= exitLength; offset++) {
                BlockPos pos = curveEndPos.relative(Direction.EAST, offset);
                level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pos, eastTrack, 3);
            }
            level.setBlock(curveEndPos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(curveEndPos, eastTrack, 3);

            Vec3 curveStart = trackBlock.getCurveStart(level, curveStartPos, southTrack, new Vec3(0, 0, 1));
            Vec3 curveEnd = trackBlock.getCurveStart(level, curveEndPos, eastTrack, new Vec3(-1, 0, 0));
            BezierConnection curve = new BezierConnection(
                    Couple.create(curveStartPos, curveEndPos),
                    Couple.create(curveStart, curveEnd),
                    Couple.create(new Vec3(0, 0, 1), new Vec3(-1, 0, 0)),
                    Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)),
                    true, false, material);
            if (!(level.getBlockEntity(curveStartPos) instanceof TrackBlockEntity startCurve)
                    || !(level.getBlockEntity(curveEndPos) instanceof TrackBlockEntity endCurve)) {
                throw new IllegalStateException("Native Create route curve endpoints have no track block entities");
            }
            startCurve.addConnection(curve);
            endCurve.addConnection(curve.secondary());
            fixture.turn = curve;
            int curveSamples = Math.max(curve.getSegmentCount(), 1);
            for (int sample = 1; sample <= curveSamples; sample++) {
                routePoints.add(curve.getPosition((double) sample / curveSamples));
            }
            for (int offset = 1; offset <= exitLength; offset++) {
                routePoints.add(blockCenter(curveEndPos.relative(Direction.EAST, offset)));
            }
        } else {
            for (int offset = approachLength + 1; offset <= straightLength; offset++) {
                BlockPos pos = origin.relative(Direction.SOUTH, offset);
                level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pos, southTrack, 3);
                routePoints.add(blockCenter(pos));
            }
        }

        BlockState stationState = AllBlocks.TRACK_STATION.get().defaultBlockState();
        level.setBlock(fixture.startStationPos, stationState, 3);
        level.setBlock(fixture.destinationStationPos, stationState, 3);
        fixture.startStation = fixture.station(fixture.startStationPos, origin, stationPrefix + "-start");
        fixture.destinationStation = fixture.station(fixture.destinationStationPos,
                destinationRail, fixture.destinationName);
        placeRouteSubstations(level, fixture, routePoints);
        retainRouteChunks(fixture, routePoints);
        placeTrainBody(level, fixture, bogeyBlock, false);
        return fixture;
    }

    private static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static void forceRouteBounds(BuiltTrain fixture, int minX, int minZ, int maxX, int maxZ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ForcedChunk chunk = new ForcedChunk(chunkX, chunkZ);
                fixture.forcedChunks.add(chunk);
                fixture.level.setChunkForced(chunkX, chunkZ, true);
                fixture.level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void retainRouteChunks(BuiltTrain fixture, List<Vec3> routePoints) {
        Set<ForcedChunk> needed = new HashSet<>();
        for (Vec3 point : routePoints) {
            int x = (int) Math.floor(point.x);
            int z = (int) Math.floor(point.z);
            for (int chunkX = (x - ROUTE_MARGIN) >> 4; chunkX <= (x + ROUTE_MARGIN) >> 4; chunkX++) {
                for (int chunkZ = (z - ROUTE_MARGIN) >> 4; chunkZ <= (z + ROUTE_MARGIN) >> 4; chunkZ++) {
                    needed.add(new ForcedChunk(chunkX, chunkZ));
                }
            }
        }
        for (ForcedChunk chunk : fixture.forcedChunks) {
            if (!needed.contains(chunk)) fixture.level.setChunkForced(chunk.x(), chunk.z(), false);
        }
        fixture.forcedChunks.clear();
        fixture.forcedChunks.addAll(needed);
    }

    private static void placeRouteSubstations(ServerLevel level, BuiltTrain fixture, List<Vec3> routePoints) {
        double[] cumulative = new double[routePoints.size()];
        for (int index = 1; index < routePoints.size(); index++) {
            cumulative[index] = cumulative[index - 1] + routePoints.get(index).distanceTo(routePoints.get(index - 1));
        }
        double totalLength = cumulative[cumulative.length - 1];
        for (double distance = 0; distance < totalLength; distance += SUBSTATION_SPACING) {
            placeRouteSubstation(level, fixture, routeLocationAt(routePoints, cumulative, distance));
        }
        placeRouteSubstation(level, fixture, routeLocationAt(routePoints, cumulative, totalLength));
    }

    private static RouteLocation routeLocationAt(List<Vec3> points, double[] cumulative, double distance) {
        int end = 1;
        while (end < cumulative.length - 1 && cumulative[end] < distance) end++;
        while (end < cumulative.length - 1 && cumulative[end] - cumulative[end - 1] < 1.0e-6) end++;
        Vec3 start = points.get(end - 1);
        Vec3 segment = points.get(end).subtract(start);
        double length = segment.length();
        double fraction = length < 1.0e-6 ? 0 : (distance - cumulative[end - 1]) / length;
        fraction = Math.max(0, Math.min(1, fraction));
        return new RouteLocation(start.add(segment.scale(fraction)), segment.normalize());
    }

    private static void placeRouteSubstation(ServerLevel level, BuiltTrain fixture, RouteLocation location) {
        Vec3 tangent = location.tangent();
        int x = (int) Math.floor(location.position().x + tangent.z * 4);
        int z = (int) Math.floor(location.position().z - tangent.x * 4);
        BlockPos pos = new BlockPos(x, fixture.origin.getY(), z);
        if (fixture.substationPositions.contains(pos)) return;
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, TractionPower.SUBSTATION.defaultBlockState(), 3);
        fixture.substationPositions.add(pos);
    }

    private static void placeTrainBody(ServerLevel level, BuiltTrain fixture, Block bogeyBlock, boolean controls) {
        BlockState casing = AllBlocks.RAILWAY_CASING.get().defaultBlockState();
        for (int offset = 0; offset <= BOGEY_SPACING; offset++) {
            level.setBlock(fixture.leadingBogeyPos.above().relative(Direction.SOUTH, offset), casing, 3);
        }
        BlockPos manualControl = fixture.leadingBogeyPos.above().relative(Direction.SOUTH, BOGEY_SPACING / 2);
        BlockPos forwardControl = fixture.leadingBogeyPos.above().relative(Direction.SOUTH, 2);
        // This southbound route uses negative native speed: the center panel faces north.
        level.setBlock(manualControl, AllBlocks.TRAIN_CONTROLS.get().defaultBlockState()
                .setValue(ControlsBlock.FACING, Direction.NORTH), 3);
        level.setBlock(forwardControl, AllBlocks.TRAIN_CONTROLS.get().defaultBlockState()
                .setValue(ControlsBlock.FACING, Direction.SOUTH), 3);
        if (!controls) {
            BlockState conductor = AllBlocks.BLAZE_BURNER.get().defaultBlockState()
                    .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING);
            level.setBlock(manualControl.north(), conductor, 3);
            level.setBlock(forwardControl.south(), conductor, 3);
        }
        if (!(bogeyBlock instanceof AbstractBogeyBlock<?>)) {
            throw new IllegalArgumentException("Native bogey block required");
        }
        BlockState bogeyState = bogeyBlock.defaultBlockState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z);
        level.setBlock(fixture.leadingBogeyPos, bogeyState, 3);
        level.setBlock(fixture.trailingBogeyPos, bogeyState, 3);
        level.addFreshEntity(new SuperGlueEntity(level,
                SuperGlueEntity.span(fixture.leadingBogeyPos, fixture.trailingBogeyPos.above())));
    }

    private static void forceOwnedRegion(ServerLevel level, BlockPos origin, int forwardLength) {
        int minChunkX = (origin.getX() - 24) >> 4;
        int maxChunkX = (origin.getX() + 24) >> 4;
        int minChunkZ = (origin.getZ() - 24) >> 4;
        int maxChunkZ = (origin.getZ() + forwardLength + 24) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                level.getChunk(chunkX, chunkZ);
            }
        }
    }
}
