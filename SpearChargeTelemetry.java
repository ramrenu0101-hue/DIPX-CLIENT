package com.dipx.client.module;

import java.util.Locale;

/**
 * Read-only Spear charge-stage telemetry derived from the held item's actual
 * minecraft:kinetic_weapon component. Stage windows are supplied by the item;
 * no material-specific timing constants or attack automation are used.
 */
public final class SpearChargeTelemetry {
    private SpearChargeTelemetry() {}

    /**
     * Formats the current kinetic-weapon window. The three duration values are
     * cumulative max-duration ticks measured after delayTicks, matching the
     * vanilla KineticWeaponComponent.Condition contract.
     *
     * A missing, unordered, or malformed component is reported as UNKNOWN
     * rather than guessing stage timings for a material or server-modified item.
     */
    public static String chargeLine(boolean spearHeld, boolean charging, int useTicks,
                                    int delayTicks, int engagedTicks, int tiredTicks,
                                    int damageTicks, double horizontalSpeed) {
        if (!spearHeld) return "Spear: NOT HELD";

        int elapsedUseTicks = Math.max(0, useTicks);
        double speed = Double.isFinite(horizontalSpeed)
                ? Math.max(0.0, horizontalSpeed) : 0.0;

        if (!charging) {
            return String.format(Locale.ROOT, "Spear: IDLE | speed: %.2f b/t", speed);
        }

        if (delayTicks < 0 || engagedTicks < 0
                || tiredTicks < engagedTicks || damageTicks < tiredTicks) {
            return line("WINDOW UNKNOWN", -1, elapsedUseTicks, speed);
        }

        if (elapsedUseTicks < delayTicks) {
            return line("WARMUP", delayTicks - elapsedUseTicks,
                    elapsedUseTicks, speed);
        }

        int activeTicks = elapsedUseTicks - delayTicks;
        if (activeTicks < engagedTicks) {
            return line("ENGAGED", engagedTicks - activeTicks,
                    elapsedUseTicks, speed);
        }
        if (activeTicks < tiredTicks) {
            return line("TIRED", tiredTicks - activeTicks,
                    elapsedUseTicks, speed);
        }
        if (activeTicks < damageTicks) {
            return line("DISENGAGED", damageTicks - activeTicks,
                    elapsedUseTicks, speed);
        }
        return line("WINDOW ENDED", -1, elapsedUseTicks, speed);
    }

    private static String line(String stage, int remainingTicks, int useTicks, double speed) {
        if (remainingTicks >= 0) {
            return String.format(Locale.ROOT,
                    "Spear: %s %dt left | use: %dt | speed: %.2f b/t",
                    stage, remainingTicks, useTicks, speed);
        }
        return String.format(Locale.ROOT,
                "Spear: %s | use: %dt | speed: %.2f b/t",
                stage, useTicks, speed);
    }
}
