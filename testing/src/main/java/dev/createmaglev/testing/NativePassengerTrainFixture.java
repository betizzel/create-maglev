package dev.createmaglev.testing;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.createmaglev.content.MaglevContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A full, world-backed passenger carriage and west-side boarding platform for the walkthrough. */
public final class NativePassengerTrainFixture {
    public static final int WIDTH = 5;
    public static final int LENGTH = 19;
    public static final int HEIGHT = 6;
    public static final int FLOOR_Y = 1;
    public static final int ROUTE_LENGTH = 256;
    public static final int DESTINATION_OFFSET = 240;
    public static final BlockPos DOOR_LOWER_POS = new BlockPos(-2, 2, 3);
    public static final Vec3 BOARDING_FEET = new Vec3(-4.0, 2, 3.5);
    public static final Vec3 EXTERIOR_FEET = new Vec3(-11.5, 2, -5.5);
    public static final Vec3 EXTERIOR_TARGET = new Vec3(0.5, 3, 6);

    private static final int MIN_X = -2;
    private static final int MAX_X = 2;
    private static final int MIN_Z = -3;
    private static final int MAX_Z = 15;
    private static final int ROOF_Y = 5;

    private NativePassengerTrainFixture() {}

    /** Builds track and stations through the native fixture, but leaves carriage assembly to the scenario. */
    public static NativeTrainFixture.BuiltTrain build(ServerLevel level, BlockPos origin) {
        NativeTrainFixture.BuiltTrain built = NativeTrainFixture.build(level, origin,
                MaglevContent.MAGLEV_TRACK, MaglevContent.MAGLEV_MATERIAL, "passenger-demo",
                MaglevContent.MAGLEV_BOGEY, true);
        extendPassengerRoute(level, built);
        BlockPos leading = built.leadingBogeyPos;

        buildFloorAndRoof(level, leading);
        buildSideWalls(level, leading);
        buildEndWalls(level, leading);
        buildDoor(level, leading);
        buildCab(level, leading);
        buildPassengerInterior(level, leading);
        buildPlatform(level, leading);

        // Include every shell block and both bogeys; the platform at x<=-3 stays outside.
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(
                leading.offset(MIN_X, 0, MIN_Z), leading.offset(MAX_X, ROOF_Y, MAX_Z))));
        return built;
    }

    private static void buildFloorAndRoof(ServerLevel level, BlockPos leading) {
        BlockState floor = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                place(level, leading, x, FLOOR_Y, z, floor);

                BlockState roof = x == MIN_X || x == MAX_X
                        ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()
                        : x == 0 ? Blocks.CYAN_CONCRETE.defaultBlockState()
                        : Blocks.WHITE_CONCRETE.defaultBlockState();
                if (z == MIN_Z && (x == MIN_X || x == MAX_X)) {
                    roof = Blocks.QUARTZ_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING, Direction.NORTH)
                            .setValue(StairBlock.HALF, Half.BOTTOM);
                }
                place(level, leading, x, ROOF_Y, z, roof);
            }
        }
    }

    private static void buildSideWalls(ServerLevel level, BlockPos leading) {
        for (int z = MIN_Z + 1; z <= MAX_Z; z++) {
            for (int y = 2; y <= 4; y++) {
                BlockState wall = y == 2 ? Blocks.CYAN_CONCRETE.defaultBlockState()
                        : Blocks.WHITE_CONCRETE.defaultBlockState();
                place(level, leading, MIN_X, y, z, wall);
                place(level, leading, MAX_X, y, z, wall);
            }
        }

        BlockState darkWindow = Blocks.BLACK_STAINED_GLASS.defaultBlockState();
        for (int z = MIN_Z + 2; z <= MAX_Z - 1; z++) {
            if (!isWindowColumn(z)) continue;
            for (int y = 3; y <= 4; y++) {
                place(level, leading, MIN_X, y, z, darkWindow);
                place(level, leading, MAX_X, y, z, darkWindow);
            }
        }
    }

    private static void buildEndWalls(ServerLevel level, BlockPos leading) {
        BlockState lowerFront = Blocks.CYAN_CONCRETE.defaultBlockState();
        BlockState glass = Blocks.BLACK_STAINED_GLASS.defaultBlockState();
        for (int x = MIN_X; x <= MAX_X; x++) {
            if (x == MIN_X || x == MAX_X) {
                place(level, leading, x, 2, MIN_Z, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
                place(level, leading, x, 3, MIN_Z, Blocks.WHITE_CONCRETE.defaultBlockState());
                place(level, leading, x, 4, MIN_Z, Blocks.WHITE_CONCRETE.defaultBlockState());
            } else {
                place(level, leading, x, 2, MIN_Z, lowerFront);
                place(level, leading, x, 3, MIN_Z, glass);
                place(level, leading, x, 4, MIN_Z, glass);
            }
        }
        place(level, leading, -1, 2, MIN_Z, Blocks.SEA_LANTERN.defaultBlockState());
        place(level, leading, 1, 2, MIN_Z, Blocks.SEA_LANTERN.defaultBlockState());
        place(level, leading, 0, 2, MIN_Z, Blocks.CYAN_CONCRETE.defaultBlockState());

        for (int x = MIN_X; x <= MAX_X; x++) {
            place(level, leading, x, 2, MAX_Z, Blocks.CYAN_CONCRETE.defaultBlockState());
            place(level, leading, x, 3, MAX_Z, Blocks.WHITE_CONCRETE.defaultBlockState());
            place(level, leading, x, 4, MAX_Z, Blocks.WHITE_CONCRETE.defaultBlockState());
        }
        for (int x = -1; x <= 1; x += 2)
            place(level, leading, x, 2, MAX_Z, Blocks.RED_STAINED_GLASS.defaultBlockState());
        for (int x = -1; x <= 1; x++)
            place(level, leading, x, 3, MAX_Z, Blocks.BLACK_STAINED_GLASS.defaultBlockState());
    }

    private static void buildDoor(ServerLevel level, BlockPos leading) {
        BlockPos lowerPos = leading.offset(DOOR_LOWER_POS);
        BlockState lower = Blocks.BIRCH_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(lowerPos, lower, 2);
        level.setBlock(lowerPos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    private static void buildCab(ServerLevel level, BlockPos leading) {
        BlockState forwardControls = AllBlocks.TRAIN_CONTROLS.get().defaultBlockState()
                .setValue(ControlsBlock.FACING, Direction.SOUTH);
        BlockState reverseControls = forwardControls.setValue(ControlsBlock.FACING, Direction.NORTH);
        // The paired native control panels sit on the continuous floor at the enclosed front cab,
        // well ahead of the passenger aisle beginning at z=3.
        place(level, leading, 0, 2, -2, forwardControls);
        place(level, leading, 0, 2, 0, reverseControls);
        place(level, leading, 0, 2, -1, AllBlocks.BLAZE_BURNER.get().defaultBlockState()
                .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
    }

    private static void buildPassengerInterior(ServerLevel level, BlockPos leading) {
        // A visible vestibule bulkhead opposite the door guides boarding into the clear center aisle.
        for (int y = 2; y <= 4; y++)
            place(level, leading, 1, y, 3, Blocks.WHITE_CONCRETE.defaultBlockState());

        for (int z = 5; z <= 13; z += 2) {
            place(level, leading, -1, 2, z, AllBlocks.SEATS.get(DyeColor.BLUE).getDefaultState());
            place(level, leading, 1, 2, z, AllBlocks.SEATS.get(DyeColor.BLUE).getDefaultState());
        }

        // Set flush into the ceiling above the seat rows; the x=0 aisle remains clear at head height.
        for (int z = 4; z <= 12; z += 4) {
            place(level, leading, -1, 4, z, Blocks.SEA_LANTERN.defaultBlockState());
            place(level, leading, 1, 4, z, Blocks.SEA_LANTERN.defaultBlockState());
        }
    }

    private static void buildPlatform(ServerLevel level, BlockPos leading) {
        for (int x = -13; x <= -3; x++) {
            for (int z = -8; z <= 18; z++) {
                for (int y = -1; y < FLOOR_Y; y++)
                    place(level, leading, x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
                BlockState surface = Blocks.POLISHED_ANDESITE.defaultBlockState();
                if (x == -3) surface = Blocks.CYAN_CONCRETE.defaultBlockState();
                else if (x == -4) surface = Blocks.YELLOW_CONCRETE.defaultBlockState();
                else if (x == -13 || z == -8 || z == 18)
                    surface = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                place(level, leading, x, FLOOR_Y, z, surface);
            }
        }
        BlockState stair = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST);
        for (int step = 0; step < 3; step++) {
            for (int z = 2; z <= 4; z++)
                place(level, leading, -16 + step, -1 + step, z, stair);
        }
    }

    private static void extendPassengerRoute(ServerLevel level, NativeTrainFixture.BuiltTrain built) {
        BlockPos origin = built.origin;
        for (int x = (origin.getX() - 24) >> 4; x <= (origin.getX() + 24) >> 4; x++) {
            for (int z = (origin.getZ() - 24) >> 4; z <= (origin.getZ() + ROUTE_LENGTH + 24) >> 4; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
        BlockState rail = built.trackBlock.defaultBlockState().setValue(TrackBlock.SHAPE, TrackShape.ZO)
                .setValue(TrackBlock.HAS_BE, true);
        for (int offset = NativeTrainFixture.ROUTE_LENGTH + 1; offset <= ROUTE_LENGTH; offset++) {
            BlockPos pos = origin.relative(Direction.SOUTH, offset);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, rail, 3);
        }
        level.removeBlock(built.destinationStationPos, false);
        built.destinationStationPos = origin.offset(3, 0, DESTINATION_OFFSET);
        built.destinationRailPos = origin.relative(Direction.SOUTH, DESTINATION_OFFSET);
        level.setBlock(built.destinationStationPos, AllBlocks.TRACK_STATION.get().defaultBlockState(), 3);
        built.destinationStation = built.station(built.destinationStationPos,
                built.destinationRailPos, built.destinationName);

        // Fixed, alternating trackside posts make real world travel visible through both windows.
        for (int offset = 0; offset <= ROUTE_LENGTH; offset += 8) {
            BlockState stripe = (offset / 8) % 2 == 0
                    ? Blocks.ORANGE_CONCRETE.defaultBlockState() : Blocks.BLUE_CONCRETE.defaultBlockState();
            for (int side = -7; side <= 7; side += 14) {
                for (int y = -1; y <= 6; y++) {
                    BlockState state = y == 3 || y == 4 ? stripe : Blocks.QUARTZ_BLOCK.defaultBlockState();
                    level.setBlock(origin.offset(side, y, offset), state, 3);
                }
                level.setBlock(origin.offset(side, 7, offset), Blocks.SEA_LANTERN.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isWindowColumn(int z) {
        return (z >= -1 && z <= 1)
                || (z >= 4 && z <= 6)
                || (z >= 8 && z <= 10)
                || (z >= 12 && z <= 14);
    }

    private static void place(ServerLevel level, BlockPos leading, int x, int y, int z, BlockState state) {
        level.setBlock(leading.offset(x, y, z), state, 3);
    }
}
