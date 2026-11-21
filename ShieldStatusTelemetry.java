package com.dipx.client.module;

/** Read-only status for the player currently under the crosshair. */
public final class ShieldStatusTelemetry {
    private ShieldStatusTelemetry() {}

    /**
     * Reports whether a player target carries or is actively blocking with a
     * shield, plus its remaining durability. This never attempts to break it.
     */
    public static String shieldLine(boolean playerTarget, boolean shieldPresent,
                                    boolean blocking, int remainingDurability,
                                    int maximumDurability) {
        if (!playerTarget) return "Shield: NO PLAYER TARGET";
        if (!shieldPresent) return "Shield: NONE";

        String state = blocking ? "BLOCKING" : "CARRIED";
        int maximum = Math.max(0, maximumDurability);
        if (maximum == 0) {
            return "Shield: " + state + " | Durability: unknown";
        }

        int remaining = Math.max(0, Math.min(maximum, remainingDurability));
        int percent = Math.round(remaining * 100.0f / maximum);
        String warning = percent <= 20 ? " | LOW" : "";
        return "Shield: " + state + " | Durability: " + remaining + "/"
                + maximum + " (" + percent + "%)" + warning;
    }
}
