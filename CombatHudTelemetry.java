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
}
