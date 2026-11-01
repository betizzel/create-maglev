package dev.createmaglev.train;

/** Speeds are blocks/tick, accelerations blocks/tick squared, and energy is ESL units/tick. */
public enum TrainTier {
    WORKSHOP(1.4, 0.0075f, 0, 0),
    ELECTRIC(2.0, 0.0125f, 8, 4),
    BULLET(3.0, 0.015f, 16, 6),
    MAGLEV(4.0, 0.02f, 32, 8);

    private final double topSpeed;
    private final float acceleration;
    private final int idleEnergy;
    private final int dragEnergy;

    TrainTier(double topSpeed, float acceleration, int idleEnergy, int dragEnergy) {
        this.topSpeed = topSpeed;
        this.acceleration = acceleration;
        this.idleEnergy = idleEnergy;
        this.dragEnergy = dragEnergy;
    }

    public double topSpeed() { return topSpeed; }
    public float acceleration() { return acceleration; }
    public long energyCost(double speed) { return idleEnergy + (long) Math.ceil(speed * speed * dragEnergy); }
}
