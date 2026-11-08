package dev.createmaglev.train;

import dev.createmaglev.config.MaglevConfig;

/** Speeds are blocks/tick, accelerations blocks/tick squared, and energy is ESL units/tick. */
public enum TrainTier {
    WORKSHOP,
    ELECTRIC,
    BULLET,
    MAGLEV;

    /** Create's default unpowered train top speed and acceleration; used when stock bogeys limit a consist. */
    private static final double WORKSHOP_TOP_SPEED = 1.4;
    private static final float WORKSHOP_ACCELERATION = 0.0075f;

    private MaglevConfig.Tier config() {
        return switch (this) {
            case WORKSHOP -> null;
            case ELECTRIC -> MaglevConfig.ELECTRIC;
            case BULLET -> MaglevConfig.BULLET;
            case MAGLEV -> MaglevConfig.MAGLEV;
        };
    }

    public double topSpeed() {
        MaglevConfig.Tier config = config();
        return config == null ? WORKSHOP_TOP_SPEED : config.topSpeed();
    }

    public float acceleration() {
        MaglevConfig.Tier config = config();
        return config == null ? WORKSHOP_ACCELERATION : (float) config.acceleration();
    }

    public long idleEnergy() {
        MaglevConfig.Tier config = config();
        return config == null ? 0 : config.idleEnergy();
    }

    public double speedEnergyCoefficient() {
        MaglevConfig.Tier config = config();
        return config == null ? 0 : config.speedEnergyCoefficient();
    }

    /** Energy one powered bogey of this tier draws per tick at the given speed in blocks/tick. */
    public long energyCost(double speed) {
        return idleEnergy() + (long) Math.ceil(speed * speed * speedEnergyCoefficient());
    }
}
