package com.dipx.client.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure, bounded projectile-path telemetry. Minecraft collision checks are
 * supplied by the caller; this class owns deterministic drag/gravity stepping
 * and the data/labels consumed by the mobile HUD.
 */
public final class PearlTrajectoryTelemetry {
    public enum StopReason { BLOCK, ENTITY, RANGE_LIMIT, UNAVAILABLE }

    public record Point(double x, double y, double z, int tick) {
        public boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }

    public record DragSample(double factor, boolean fluid) {}

    public record Collision(StopReason reason, Point point) {}

    @FunctionalInterface
    public interface CollisionProbe {
        Collision firstHit(Point from, Point to, int tick);
    }

    @FunctionalInterface
    public interface DragProvider {
        DragSample at(Point position);
    }

    public record Preview(List<Point> points, StopReason stopReason, int ticks,
                          double horizontalRange, double heightDelta, boolean touchedFluid) {
        public Preview {
            points = List.copyOf(points);
        }
    }

    private PearlTrajectoryTelemetry() {}

    public static Preview unavailable() {
        return new Preview(List.of(), StopReason.UNAVAILABLE, 0, 0.0, 0.0, false);
    }

    /**
     * Steps one projectile tick at a time: move, apply medium drag, then apply
     * gravity. Collision checks happen on each swept segment before advancing.
     */
    public static Preview trace(Point origin, double velocityX, double velocityY, double velocityZ,
                                double gravity, int maxTicks,
                                CollisionProbe collisionProbe, DragProvider dragProvider) {
        if (origin == null || !origin.isFinite()
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY)
                || !Double.isFinite(velocityZ) || !Double.isFinite(gravity)
                || gravity < 0.0 || maxTicks < 1 || maxTicks > 120) {
            return unavailable();
        }

        List<Point> points = new ArrayList<>(maxTicks + 1);
        points.add(origin);
        Point position = origin;
        boolean touchedFluid = false;

        for (int tick = 1; tick <= maxTicks; tick++) {
            Point next = new Point(position.x() + velocityX,
                    position.y() + velocityY, position.z() + velocityZ, tick);
            if (!next.isFinite()) return unavailable();

            Collision collision = collisionProbe == null
                    ? null : collisionProbe.firstHit(position, next, tick);
            if (collision != null
                    && (collision.reason() == StopReason.BLOCK || collision.reason() == StopReason.ENTITY)) {
                Point hit = collision.point() != null && collision.point().isFinite()
                        ? new Point(collision.point().x(), collision.point().y(),
                                collision.point().z(), tick)
                        : next;
                points.add(hit);
                return result(points, collision.reason(), tick, origin, touchedFluid);
            }

            points.add(next);
            DragSample drag = dragProvider == null
                    ? new DragSample(0.99, false) : dragProvider.at(next);
            if (drag == null || !Double.isFinite(drag.factor())
                    || drag.factor() < 0.0 || drag.factor() > 1.0) {
                return unavailable();
            }
            touchedFluid |= drag.fluid();
            velocityX *= drag.factor();
            velocityY = velocityY * drag.factor() - gravity;
            velocityZ *= drag.factor();
            position = next;
        }

        return result(points, StopReason.RANGE_LIMIT, maxTicks, origin, touchedFluid);
    }

    private static Preview result(List<Point> points, StopReason reason, int ticks,
                                  Point origin, boolean touchedFluid) {
        Point end = points.get(points.size() - 1);
        double dx = end.x() - origin.x();
        double dz = end.z() - origin.z();
        return new Preview(points, reason, ticks, Math.hypot(dx, dz),
                end.y() - origin.y(), touchedFluid);
    }

    public static String summary(boolean pearlAvailable, Preview preview) {
        if (!pearlAvailable) return "Pearl path: NO PEARL IN INVENTORY";
        if (preview == null || preview.stopReason() == StopReason.UNAVAILABLE) {
            return "Pearl path: UNAVAILABLE";
        }
        String status = switch (preview.stopReason()) {
            case BLOCK -> "BLOCK HIT";
            case ENTITY -> "ENTITY HIT";
            case RANGE_LIMIT -> "NO HIT";
            case UNAVAILABLE -> "UNAVAILABLE";
        };
        String fluid = preview.touchedFluid() ? " | FLUID APPROX" : "";
        return String.format(Locale.ROOT, "Pearl path: %s @ %dt | %.1fm | ΔY %+.1f%s",
                status, preview.ticks(), preview.horizontalRange(), preview.heightDelta(), fluid);
    }

    public static String graphFooter(Preview preview) {
        if (preview == null) return "UNAVAILABLE";
        String status = switch (preview.stopReason()) {
            case BLOCK -> "BLOCK";
            case ENTITY -> "ENTITY";
            case RANGE_LIMIT -> "NO HIT";
            case UNAVAILABLE -> "UNAVAILABLE";
        };
        return String.format(Locale.ROOT, "%s %dt  ·  %.1fm",
                status, preview.ticks(), preview.horizontalRange());
    }
}
