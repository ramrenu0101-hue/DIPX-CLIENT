package com.dipx.client.module;

import java.util.Locale;

/** Pure formatting and input-sanitizing helpers for manual spear telemetry. */
public final class CombatHudTelemetry {
    private CombatHudTelemetry() {}

    /**
     * Formats the active spear-use timer and horizontal movement speed.
     * Velocity is shown in blocks per tick, matching Minecraft's world units.
     */
    public static String spearChargeLine(boolean charging, int useTicks, double horizontalSpeed) {
        int safeTicks = Math.max(0, useTicks);
        double safeSpeed = Double.isFinite(horizontalSpeed)
                ? Math.max(0.0, horizontalSpeed) : 0.0;
        String charge = charging ? safeTicks + "t" : "IDLE";
        return "Spear charge: " + charge + " | Horizontal speed: "
                + String.format(Locale.ROOT, "%.2f", safeSpeed) + " b/t";
    }

    /**
     * Shows the effective vanilla attack range supplied by the held spear's
     * minecraft:attack_range component and the current vanilla crosshair result.
     * It reports information only; it never selects a target or sends an attack.
     */
    public static String spearRangeLine(boolean spearHeld, double minReach, double maxReach,
                                        boolean entityUnderCrosshair, double targetDistance,
                                        boolean blockUnderCrosshair) {
        if (!spearHeld) return "Spear reach: NOT HELD";
        if (!Double.isFinite(minReach) || !Double.isFinite(maxReach)
                || minReach < 0.0 || maxReach < minReach || maxReach > 64.0) {
            return "Spear reach: UNAVAILABLE";
        }

        String range = String.format(Locale.ROOT, "%.1f-%.1fm", minReach, maxReach);
        String result;
        if (entityUnderCrosshair) {
            result = Double.isFinite(targetDistance) && targetDistance >= 0.0
                    ? "ENTITY " + String.format(Locale.ROOT, "%.1f", targetDistance) + "m"
                    : "ENTITY UNDER CROSSHAIR";
        } else if (blockUnderCrosshair) {
            result = "BLOCK HIT";
        } else {
            result = "NO TARGET";
        }
        return "Spear reach: " + range + " | " + result;
    }
}
