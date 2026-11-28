package com.dipx.client.module;

import java.util.Locale;

/**
 * Read-only Mace impact guidance based on the local player's live fall state.
 * The vanilla MaceItem predicate remains authoritative for SMASH READY; the
 * 1.5-block marker is only a fall-distance reference, not a damage estimate.
 */
public final class MaceImpactTelemetry {
    private static final double SMASH_FALL_REFERENCE = 1.5;
    private static final double MIN_DESCENT_SPEED = 0.01;

    private MaceImpactTelemetry() {}

    public static String impactLine(boolean maceHeld, boolean vanillaSmashReady,
                                     double fallDistance, double verticalVelocity) {
        double fall = Double.isFinite(fallDistance) ? Math.max(0.0, fallDistance) : 0.0;
        double downwardSpeed = Double.isFinite(verticalVelocity)
                ? Math.max(0.0, -verticalVelocity) : 0.0;
        String fallText = String.format(Locale.ROOT, "%.1f", fall);
        String speedText = String.format(Locale.ROOT, "%.2f", downwardSpeed);

        if (!maceHeld) {
            return "Mace: NOT HELD | Fall: " + fallText + " | Down: " + speedText + " b/t";
        }
        if (vanillaSmashReady) {
            return "Mace: SMASH READY | Fall: " + fallText + " | Down: " + speedText + " b/t";
        }
        if (downwardSpeed > MIN_DESCENT_SPEED) {
            double remaining = Math.max(0.0, SMASH_FALL_REFERENCE - fall);
            if (remaining > 0.05) {
                String gap = String.format(Locale.ROOT, "%.1f", remaining);
                return "Mace: BUILDING | " + gap + " to 1.5 fall | Down: " + speedText + " b/t";
            }
            return "Mace: FALLING | Vanilla check pending | Fall: "
                    + fallText + " | Down: " + speedText + " b/t";
        }
        return "Mace: WAIT | Fall: " + fallText + " | Down: " + speedText + " b/t";
    }
}
