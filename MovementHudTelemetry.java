package com.dipx.client.module;

import java.util.Locale;

/** Pure formatting and input-sanitizing helpers for the read-only movement HUD. */
public final class MovementHudTelemetry {
    private MovementHudTelemetry() {}

    /**
     * Formats horizontal/vertical player velocity and current movement state.
     * Minecraft velocity is measured in blocks per tick.
     */
    public static String movementLine(double horizontalSpeed, double verticalVelocity,
                                      boolean onGround, boolean sprinting, boolean sneaking) {
        double safeHorizontal = Double.isFinite(horizontalSpeed)
                ? Math.max(0.0, horizontalSpeed) : 0.0;
        double safeVertical = Double.isFinite(verticalVelocity) && verticalVelocity != 0.0
                ? verticalVelocity : 0.0;
        String surface = onGround ? "GROUND" : "AIR";
        String pace = sprinting ? "SPRINT" : sneaking ? "SNEAK" : "NORMAL";
        return String.format(Locale.ROOT, "Move: %.2f b/t | Y: %+.2f | %s | %s",
                safeHorizontal, safeVertical, surface, pace);
    }
}
