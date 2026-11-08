package dev.createmaglev.config;

import dev.createmaglev.CreateMaglev;
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Server balance values, stored in config/create_maglev-server.toml (Forge Config API Port's global server-config location) and synced to clients.
 * Accessors return Create's internal units: blocks per tick, blocks per tick squared, and energy per tick.
 */
public final class MaglevConfig {
    private static final double TICKS_PER_SECOND = 20.0;

    public static final ForgeConfigSpec SPEC;
    public static final Tier ELECTRIC;
    public static final Tier BULLET;
    public static final Tier MAGLEV;
    public static final ForgeConfigSpec.DoubleValue BULLET_SPEED_ON_ORDINARY_TRACK;
    public static final ForgeConfigSpec.DoubleValue CURVE_LATERAL_ACCELERATION;
    public static final ForgeConfigSpec.DoubleValue MINIMUM_CURVE_SPEED;
    public static final ForgeConfigSpec.DoubleValue EMERGENCY_BRAKING;
    public static final ForgeConfigSpec.IntValue SUBSTATION_CAPACITY;
    public static final ForgeConfigSpec.IntValue SUBSTATION_OUTPUT;
    public static final ForgeConfigSpec.IntValue SUPPLY_RADIUS;

    private static final double DEFAULT_BULLET_SPEED_ON_ORDINARY_TRACK = 40;
    private static final double DEFAULT_CURVE_LATERAL_ACCELERATION = 25;
    private static final double DEFAULT_MINIMUM_CURVE_SPEED = 20;
    private static final double DEFAULT_EMERGENCY_BRAKING = 16;
    private static final int DEFAULT_SUBSTATION_CAPACITY = 65536;
    private static final int DEFAULT_SUBSTATION_OUTPUT = 512;
    private static final int DEFAULT_SUPPLY_RADIUS = 64;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Rated performance of powered bogeys. Speeds are blocks per second (1 block = 1 metre),",
                "accelerations blocks per second squared, energy in New Age/ESL units per powered bogey per tick.").push("tiers");
        ELECTRIC = new Tier(builder, "electric", 40, 5, 8, 4);
        BULLET = new Tier(builder, "bullet", 60, 6, 16, 6);
        MAGLEV = new Tier(builder, "maglev", 80, 8, 32, 8);
        BULLET_SPEED_ON_ORDINARY_TRACK = builder
                .comment("Bullet-tier speed cap on track that is not Reinforced Standard Track, in blocks per second.")
                .defineInRange("bulletSpeedOnOrdinaryTrack", DEFAULT_BULLET_SPEED_ON_ORDINARY_TRACK, 1.0, 400.0);
        builder.pop();

        builder.comment("Curves and braking for addon-powered trains.").push("track");
        CURVE_LATERAL_ACCELERATION = builder
                .comment("Sideways acceleration allowed on curves, in blocks per second squared.",
                        "A curve of radius r permits sqrt(curveLateralAcceleration * r) blocks per second.")
                .defineInRange("curveLateralAcceleration", DEFAULT_CURVE_LATERAL_ACCELERATION, 0.1, 10000.0);
        MINIMUM_CURVE_SPEED = builder
                .comment("Speed always permitted on a curve however tight, in blocks per second.")
                .defineInRange("minimumCurveSpeed", DEFAULT_MINIMUM_CURVE_SPEED, 1.0, 400.0);
        EMERGENCY_BRAKING = builder
                .comment("Deceleration when traction power is lost, or ahead of unpowered track, curves and track ends,",
                        "in blocks per second squared.")
                .defineInRange("emergencyBraking", DEFAULT_EMERGENCY_BRAKING, 1.0, 400.0);
        builder.pop();

        builder.comment("Traction substations.").push("substations");
        SUBSTATION_CAPACITY = builder.comment("Energy each substation can store.")
                .defineInRange("capacity", DEFAULT_SUBSTATION_CAPACITY, 1, Integer.MAX_VALUE);
        SUBSTATION_OUTPUT = builder.comment("Most energy one substation can deliver to trains per tick, shared by every train it powers.")
                .defineInRange("maxOutputPerTick", DEFAULT_SUBSTATION_OUTPUT, 1, Integer.MAX_VALUE);
        SUPPLY_RADIUS = builder.comment("Spherical radius, in blocks, within which a substation powers bogey pickups.")
                .defineInRange("supplyRadius", DEFAULT_SUPPLY_RADIUS, 1, 512);
        builder.pop();
        SPEC = builder.build();
    }

    private MaglevConfig() {}

    public static void register() {
        ForgeConfigRegistry.INSTANCE.register(CreateMaglev.ID, ModConfig.Type.SERVER, SPEC);
    }

    public static double bulletSpeedOnOrdinaryTrack() {
        return value(BULLET_SPEED_ON_ORDINARY_TRACK, DEFAULT_BULLET_SPEED_ON_ORDINARY_TRACK) / TICKS_PER_SECOND;
    }

    public static double curveLateralAcceleration() {
        return value(CURVE_LATERAL_ACCELERATION, DEFAULT_CURVE_LATERAL_ACCELERATION) / (TICKS_PER_SECOND * TICKS_PER_SECOND);
    }

    public static double minimumCurveSpeed() {
        return value(MINIMUM_CURVE_SPEED, DEFAULT_MINIMUM_CURVE_SPEED) / TICKS_PER_SECOND;
    }

    public static double emergencyBraking() {
        return value(EMERGENCY_BRAKING, DEFAULT_EMERGENCY_BRAKING) / (TICKS_PER_SECOND * TICKS_PER_SECOND);
    }

    public static long substationCapacity() {
        return value(SUBSTATION_CAPACITY, DEFAULT_SUBSTATION_CAPACITY);
    }

    public static long substationOutput() {
        return value(SUBSTATION_OUTPUT, DEFAULT_SUBSTATION_OUTPUT);
    }

    public static int supplyRadius() {
        return value(SUPPLY_RADIUS, DEFAULT_SUPPLY_RADIUS);
    }

    /** Server configs load with the world; before that (menus, early client code) the defaults apply. */
    private static <T> T value(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    public static final class Tier {
        public final ForgeConfigSpec.DoubleValue topSpeed;
        public final ForgeConfigSpec.DoubleValue acceleration;
        public final ForgeConfigSpec.IntValue idleEnergy;
        public final ForgeConfigSpec.DoubleValue speedEnergyCoefficient;
        private final double defaultTopSpeed;
        private final double defaultAcceleration;
        private final int defaultIdleEnergy;
        private final double defaultSpeedEnergyCoefficient;

        private Tier(ForgeConfigSpec.Builder builder, String name, double topSpeed, double acceleration, int idleEnergy,
                     double speedEnergyCoefficient) {
            defaultTopSpeed = topSpeed;
            defaultAcceleration = acceleration;
            defaultIdleEnergy = idleEnergy;
            defaultSpeedEnergyCoefficient = speedEnergyCoefficient;
            builder.push(name);
            this.topSpeed = builder.comment("Rated top speed in blocks per second.")
                    .defineInRange("topSpeed", topSpeed, 1.0, 400.0);
            this.acceleration = builder.comment("Acceleration in blocks per second squared.")
                    .defineInRange("acceleration", acceleration, 0.1, 400.0);
            this.idleEnergy = builder.comment("Energy each powered bogey draws per tick, even at rest.")
                    .defineInRange("idleEnergyPerBogey", idleEnergy, 0, 1_000_000);
            this.speedEnergyCoefficient = builder
                    .comment("Extra energy per powered bogey per tick: ceil(speed^2 * coefficient), speed in blocks per tick.")
                    .defineInRange("speedEnergyCoefficient", speedEnergyCoefficient, 0.0, 1_000_000.0);
            builder.pop();
        }

        /** Blocks per tick. */
        public double topSpeed() {
            return value(topSpeed, defaultTopSpeed) / TICKS_PER_SECOND;
        }

        /** Blocks per tick squared. */
        public double acceleration() {
            return value(acceleration, defaultAcceleration) / (TICKS_PER_SECOND * TICKS_PER_SECOND);
        }

        public long idleEnergy() {
            return value(idleEnergy, defaultIdleEnergy);
        }

        public double speedEnergyCoefficient() {
            return value(speedEnergyCoefficient, defaultSpeedEnergyCoefficient);
        }
    }
}
