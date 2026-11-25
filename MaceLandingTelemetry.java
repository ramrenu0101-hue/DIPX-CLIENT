package com.dipx.client.module;

import java.util.Locale;

/**
 * Read-only estimate of the local player's time to a collidable floor while
 * holding a Mace. This is a simple air-physics estimate, not a damage or hit
 * guarantee; fluids, effects, elytra, server movement and latency can differ.
 */
public final class MaceLandingTelemetry {
    public static final double DEFAULT_GRAVITY = 0.08;
    public static final double DEFAULT_AIR_DRAG = 0.98;
    private static final double GROUND_EPSILON = 0.08;
    private static final double MIN_RISING_SPEED = 0.015;

    private MaceLandingTelemetry() {}

    /**
     * Integrates a bounded, discrete vertical estimate. Returns zero when
     * already at the floor, a positive tick count on predicted contact, or -1
     * when inputs are invalid / contact is outside the prediction horizon.
     */
    public static int estimateTicksToGround(double gap, double verticalVelocity,
                                             double gravity, double drag, int maxTicks) {
        if (!Double.isFinite(gap) || !Double.isFinite(verticalVelocity)
                || !Double.isFinite(gravity) || !Double.isFinite(drag)
                || gap < 0.0 || gravity <= 0.0 || drag <= 0.0 || drag > 1.0
                || maxTicks < 1) {
            return -1;
        }
        if (gap <= GROUND_EPSILON) return 0;

        double travelled = 0.0;
        double velocity = verticalVelocity;
        for (int tick = 1; tick <= maxTicks; tick++) {
            velocity = (velocity - gravity) * drag;
            travelled += velocity;
            if (travelled <= -gap) return tick;
        }
        return -1;
    }

    public static String landingLine(boolean maceHeld, double gap,
                                     double verticalVelocity, int maxTicks) {
        if (!maceHeld) return "Mace landing: NOT HELD";
        if (!Double.isFinite(gap) || gap < 0.0 || !Double.isFinite(verticalVelocity)
                || maxTicks < 1) {
            return "Mace landing: UNAVAILABLE";
        }

        double safeGap = Math.max(0.0, gap);
        String gapText = String.format(Locale.ROOT, "%.1f", safeGap);
        if (safeGap <= GROUND_EPSILON) {
            return "Mace landing: GROUND | gap: " + gapText + "m";
        }
        if (verticalVelocity > MIN_RISING_SPEED) {
            return "Mace landing: RISING | gap: " + gapText + "m | up: "
                    + String.format(Locale.ROOT, "%.2f", verticalVelocity) + " b/t";
        }

        int ticks = estimateTicksToGround(safeGap, verticalVelocity,
                DEFAULT_GRAVITY, DEFAULT_AIR_DRAG, maxTicks);
        String speed = String.format(Locale.ROOT, "%.2f", Math.max(0.0, -verticalVelocity));
        if (ticks < 0) {
            return "Mace landing: >" + maxTicks + "t | gap: " + gapText
                    + "m | down: " + speed + " b/t";
        }
        if (ticks == 0) return "Mace landing: GROUND | gap: " + gapText + "m";
        return "Mace landing: ~" + ticks + "t | gap: " + gapText
                + "m | down: " + speed + " b/t";
    }

    public static String noFloorLine(boolean maceHeld) {
        return maceHeld ? "Mace landing: NO FLOOR WITHIN 64m" : "Mace landing: NOT HELD";
    }
}
