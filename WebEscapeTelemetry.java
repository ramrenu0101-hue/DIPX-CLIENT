package com.dipx.client.module;

import java.util.Locale;

/**
 * Read-only cobweb contact guidance. The game-side caller supplies the number
 * of cobweb blocks intersecting the player's bounding box and local speed.
 * No movement, input, inventory, or packet behavior is performed here.
 */
public final class WebEscapeTelemetry {
    private WebEscapeTelemetry() {}

    public static String statusLine(int overlappingCobwebBlocks, double horizontalSpeed) {
        if (overlappingCobwebBlocks < 0) return "Web Escape: UNAVAILABLE";
        if (overlappingCobwebBlocks == 0) return "Web Escape: CLEAR";

        double speed = Double.isFinite(horizontalSpeed)
                ? Math.max(0.0, horizontalSpeed) : 0.0;
        return String.format(Locale.ROOT,
                "Web Escape: COBWEB CONTACT | blocks: %d | move: %.2f b/t | MANUAL",
                overlappingCobwebBlocks, speed);
    }
}
