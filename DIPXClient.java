package com.dipx.client;

import com.dipx.client.gui.DIPXScreen;
import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleRegistry;
import com.dipx.client.module.ModuleConfigStore;
import com.dipx.client.module.PearlTrajectoryTelemetry;
import com.dipx.client.module.MaceLandingTelemetry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.item.Items;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.MaceItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DIPX client bootstrap and lightweight, client-side PvP information HUDs. */
public final class DIPXClient implements ClientModInitializer {
    private static KeyBinding openGui;
    private static Double originalGamma;
    private static Screen previousScreen;
    private static int smokeOpenTicks;
    private static boolean smokeModeLogged;
    private static boolean smokeOnboardingDismissed;
    private static boolean smokeOpenInjected;
    private static boolean smokeCloseInjected;
    private static boolean smokeWorldScreenOpened;
    private static boolean smokeWorldCreationStarted;
    private static boolean smokePearlWorldTestsComplete;
    private static boolean smokePearlWorldTestsSucceeded;
    private static boolean smokeMaceLandingTestsComplete;
    private static final List<BlockPos> smokePearlTestBlocks = new ArrayList<>();
    private static PearlTrajectoryTelemetry.Preview pearlPreview = PearlTrajectoryTelemetry.unavailable();
    private static boolean pearlAvailable;
    private static int lastPearlPreviewTick = Integer.MIN_VALUE;
    private static boolean pearlPreviewFailureLogged;
    private static final Logger LOGGER = LoggerFactory.getLogger("DIPX/Client");
    private static final double PEARL_AIR_DRAG = 0.99;
    private static final double PEARL_WATER_DRAG = 0.8;

    /** Exposes vanilla's protected water-state refresh without spawning the probe. */
    private static final class PearlPreviewProbe extends EnderPearlEntity {
        private PearlPreviewProbe(net.minecraft.world.World world, PlayerEntity owner) {
            super(world, owner, Items.ENDER_PEARL.getDefaultStack());
        }

        private void refreshFluidState() {
            updateWaterState();
        }
    }

    private static boolean hasEnderPearl(PlayerEntity player) {
        if (player.getOffHandStack().isOf(Items.ENDER_PEARL)) return true;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.ENDER_PEARL)) return true;
        }
        return false;
    }

    /**
     * Samples vanilla's refreshed fluid height over the projectile bounding box.
     * EnderPearlEntity uses submerged drag only after fluid height crosses its
     * swim-height threshold; partial contact retains the normal projectile drag.
     */
    private static PearlTrajectoryTelemetry.DragSample pearlDragAt(
            PearlPreviewProbe probe, PearlTrajectoryTelemetry.Point point) {
        probe.setPosition(point.x(), point.y(), point.z());
        probe.refreshFluidState();
        boolean water = probe.isTouchingWater();
        return new PearlTrajectoryTelemetry.DragSample(
                water ? PEARL_WATER_DRAG : PEARL_AIR_DRAG, water);
    }

    /**
     * Rebuilds a centreline preview at most every five ticks. The temporary
     * projectile is never added to the world; it only supplies vanilla launch
     * direction/owner velocity and ProjectileUtil collision queries.
     */
    private static void updatePearlPreview(MinecraftClient client) {
        if (!enabled("Pearl Trajectory HUD") || client.player == null || client.world == null) {
            pearlAvailable = false;
            pearlPreview = PearlTrajectoryTelemetry.unavailable();
            lastPearlPreviewTick = Integer.MIN_VALUE;
            return;
        }
        if (lastPearlPreviewTick != Integer.MIN_VALUE
                && client.player.age >= lastPearlPreviewTick
                && client.player.age - lastPearlPreviewTick < 5) {
            return;
        }
        lastPearlPreviewTick = client.player.age;
        // CI-only: the loaded-world harness has no inventory dependency. Normal
        // gameplay still requires an Ender Pearl in the player's inventory.
        pearlAvailable = hasEnderPearl(client.player) || Boolean.getBoolean("dipx.smoke.gui");
        if (!pearlAvailable) {
            pearlPreview = PearlTrajectoryTelemetry.unavailable();
            return;
        }

        try {
            PlayerEntity player = client.player;
            PearlPreviewProbe probe = new PearlPreviewProbe(client.world, player);
            // Zero divergence draws the vanilla aim centreline. Real throws
            // include a tiny random spread, so the displayed path is an estimate.
            probe.setVelocity(player, player.getPitch(), player.getYaw(),
                    0.0F, EnderPearlItem.POWER, 0.0F);
            Vec3d origin = new Vec3d(probe.getX(), probe.getY(), probe.getZ());
            Vec3d velocity = probe.getVelocity();

            pearlPreview = PearlTrajectoryTelemetry.trace(
                    new PearlTrajectoryTelemetry.Point(origin.x, origin.y, origin.z, 0),
                    velocity.x, velocity.y, velocity.z, 0.03, 48,
                    (from, to, tick) -> {
                        probe.setPosition(from.x(), from.y(), from.z());
                        probe.setVelocityClient(new Vec3d(
                                to.x() - from.x(), to.y() - from.y(), to.z() - from.z()));
                        HitResult hit = ProjectileUtil.getCollision(
                                probe, candidate -> candidate != player && candidate.canHit());
                        if (hit.getType() == HitResult.Type.MISS) return null;

                        PearlTrajectoryTelemetry.StopReason reason;
                        if (hit instanceof BlockHitResult) {
                            reason = PearlTrajectoryTelemetry.StopReason.BLOCK;
                        } else if (hit instanceof EntityHitResult) {
                            reason = PearlTrajectoryTelemetry.StopReason.ENTITY;
                        } else {
                            return null;
                        }
                        Vec3d hitPos = hit.getPos();
                        return new PearlTrajectoryTelemetry.Collision(reason,
                                new PearlTrajectoryTelemetry.Point(
                                        hitPos.x, hitPos.y, hitPos.z, tick));
                    },
                    point -> point.tick() == 0
                            ? new PearlTrajectoryTelemetry.DragSample(PEARL_AIR_DRAG, false)
                            : pearlDragAt(probe, point));
        } catch (RuntimeException previewError) {
            pearlPreview = PearlTrajectoryTelemetry.unavailable();
            if (!pearlPreviewFailureLogged) {
                pearlPreviewFailureLogged = true;
                LOGGER.warn("Pearl trajectory preview unavailable; client remains active", previewError);
            }
        }
    }


    /** Opens an isolated creative world only in the CI integration harness. */
    private static void openSmokeWorldCreator(MinecraftClient client) {
        if (!Boolean.getBoolean("dipx.smoke.gui") || !smokeCloseInjected
                || smokeWorldScreenOpened || client.player != null
                || client.currentScreen == null
                || !client.currentScreen.getClass().getSimpleName().equals("TitleScreen")) {
            return;
        }
        try {
            for (DIPXModule module : ModuleRegistry.all()) {
                if (module.name().equals("Pearl Trajectory HUD")
                        || module.name().equals("Mace Landing HUD")) module.setEnabled(true);
            }
            CreateWorldScreen.show(client, () -> {});
            smokeWorldScreenOpened = true;
            LOGGER.info("DIPX smoke opened isolated test world creator");
        } catch (RuntimeException error) {
            smokePearlWorldTestsComplete = true;
            LOGGER.error("DIPX smoke could not open test world creator", error);
        }
    }

    /** Starts the vanilla Create World flow without depending on translated UI coordinates. */
    private static void createSmokeWorld(MinecraftClient client) {
        if (!Boolean.getBoolean("dipx.smoke.gui") || !smokeWorldScreenOpened
                || smokeWorldCreationStarted || !(client.currentScreen instanceof CreateWorldScreen screen)) {
            return;
        }
        try {
            WorldCreator creator = screen.getWorldCreator();
            creator.setWorldName("DIPX Pearl Smoke");
            creator.setGameMode(WorldCreator.Mode.CREATIVE);
            creator.setCheatsEnabled(true);
            creator.setGenerateStructures(false);
            Method createLevel = CreateWorldScreen.class.getDeclaredMethod("createLevel");
            createLevel.setAccessible(true);
            createLevel.invoke(screen);
            smokeWorldCreationStarted = true;
            LOGGER.info("DIPX smoke started isolated creative test world");
        } catch (ReflectiveOperationException | RuntimeException error) {
            smokePearlWorldTestsComplete = true;
            LOGGER.error("DIPX smoke could not create test world", error);
        }
    }

    private static void clearPearlSmokeBlocks(MinecraftClient client) {
        if (client.world != null) {
            for (BlockPos pos : smokePearlTestBlocks) {
                client.world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
        smokePearlTestBlocks.clear();
    }

    private static void placePearlSmokeBlock(MinecraftClient client, BlockPos pos, boolean water) {
        BlockPos immutable = pos.toImmutable();
        var fixtureState = water ? Blocks.WATER.getDefaultState() : Blocks.STONE.getDefaultState();
        // A client world silently rejects writes to unloaded chunks. Treat that as
        // a fixture error instead of letting a missing wall look like a raycast bug.
        if (!client.world.getBlockState(immutable).equals(fixtureState)
                && !client.world.setBlockState(immutable, fixtureState)) {
            throw new IllegalStateException("Pearl smoke fixture block was not applied at " + immutable
                    + " (chunk may not be loaded)");
        }
        smokePearlTestBlocks.add(immutable);
    }

    /**
     * Clears a three-block corridor around a simulated path. Dry scenarios clear
     * water as well as terrain so a random lake in the generated spawn chunks
     * cannot silently change the vanilla projectile's drag. Water scenarios first
     * clear this corridor, then lay their own controlled fluid fixture.
     */
    private static void clearPearlSmokePath(MinecraftClient client,
                                             PearlTrajectoryTelemetry.Preview preview,
                                             boolean preserveWater) {
        for (PearlTrajectoryTelemetry.Point point : preview.points()) {
            BlockPos center = BlockPos.ofFloored(point.x(), point.y(), point.z());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = center.add(dx, dy, dz).toImmutable();
                        var state = client.world.getBlockState(pos);
                        if (!state.isAir() && !(preserveWater && state.isOf(Blocks.WATER))) {
                            if (!client.world.setBlockState(pos, Blocks.AIR.getDefaultState())) {
                                throw new IllegalStateException("Pearl smoke path could not clear " + pos
                                        + " (chunk may not be loaded)");
                            }
                            smokePearlTestBlocks.add(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the vanilla centreline for the loaded-world smoke harness. The
     * dry-corridor pass deliberately forces air drag; the actual preview pass
     * samples vanilla's refreshed water state from the controlled fixture.
     */
    private static PearlTrajectoryTelemetry.Preview tracePearlCenterline(
            MinecraftClient client, PlayerEntity player, int maxTicks, boolean sampleWater) {
        PearlPreviewProbe probe = new PearlPreviewProbe(client.world, player);
        probe.setVelocity(player, player.getPitch(), player.getYaw(),
                0.0F, EnderPearlItem.POWER, 0.0F);
        Vec3d origin = new Vec3d(probe.getX(), probe.getY(), probe.getZ());
        Vec3d velocity = probe.getVelocity();
        return PearlTrajectoryTelemetry.trace(
                new PearlTrajectoryTelemetry.Point(origin.x, origin.y, origin.z, 0),
                velocity.x, velocity.y, velocity.z, 0.03, maxTicks, null,
                point -> !sampleWater || point.tick() == 0
                        ? new PearlTrajectoryTelemetry.DragSample(PEARL_AIR_DRAG, false)
                        : pearlDragAt(probe, point));
    }

    /** Runs the actual vanilla EnderPearlEntity tick beside the HUD's predicted points. */
    private static double comparePearlToVanillaTick(
            MinecraftClient client, PlayerEntity player, PearlTrajectoryTelemetry.Preview predicted,
            int ticks, boolean expectWater) {
        EnderPearlEntity vanilla = new EnderPearlEntity(
                client.world, player, Items.ENDER_PEARL.getDefaultStack());
        vanilla.setVelocity(player, player.getPitch(), player.getYaw(),
                0.0F, EnderPearlItem.POWER, 0.0F);
        double maximumError = 0.0;
        for (int tick = 1; tick <= ticks; tick++) {
            vanilla.tick();
            if (vanilla.isRemoved() || (expectWater && !vanilla.isTouchingWater())) {
                return Double.POSITIVE_INFINITY;
            }
            PearlTrajectoryTelemetry.Point expected = predicted.points().get(tick);
            double error = new Vec3d(vanilla.getX(), vanilla.getY(), vanilla.getZ()).distanceTo(
                    new Vec3d(expected.x(), expected.y(), expected.z()));
            maximumError = Math.max(maximumError, error);
        }
        return maximumError;
    }

    private static void placePearlWaterCube(MinecraftClient client, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    placePearlSmokeBlock(client, center.add(dx, dy, dz), true);
                }
            }
        }
    }

    private static void placePearlWaterPath(MinecraftClient client, PlayerEntity player, int ticks) {
        PearlPreviewProbe probe = new PearlPreviewProbe(client.world, player);
        probe.setVelocity(player, player.getPitch(), player.getYaw(),
                0.0F, EnderPearlItem.POWER, 0.0F);
        Vec3d position = new Vec3d(probe.getX(), probe.getY(), probe.getZ());
        Vec3d velocity = probe.getVelocity();
        placePearlWaterCube(client, BlockPos.ofFloored(position));
        for (int tick = 1; tick <= ticks; tick++) {
            PearlTrajectoryTelemetry.DragSample drag = tick == 1
                    ? new PearlTrajectoryTelemetry.DragSample(PEARL_AIR_DRAG, false)
                    : pearlDragAt(probe,
                            new PearlTrajectoryTelemetry.Point(position.x, position.y, position.z, tick - 1));
            velocity = velocity.add(0.0, -0.03, 0.0).multiply(drag.factor());
            position = position.add(velocity);
            placePearlWaterCube(client, BlockPos.ofFloored(position));
        }
    }

    /**
     * Places water beside, not under, the pearl centreline. The projectile's
     * bounding box overlaps the water while its centre block remains dry.
     */
    private static void placePearlWaterEdgePath(MinecraftClient client, BlockPos anchor) {
        // Keep the test water inside the already-loaded spawn chunks. The pearl
        // centre stays in anchor.x while its 0.25-wide box overlaps anchor.x + 1.
        int waterX = anchor.getX() + 1;
        for (int y = anchor.getY() - 1; y <= anchor.getY() + 4; y++) {
            for (int z = anchor.getZ() - 1; z <= anchor.getZ() + 24; z++) {
                placePearlSmokeBlock(client, new BlockPos(waterX, y, z), true);
            }
        }
    }

    /**
     * Exercises the real ClientWorld collision query and compares the preview
     * against EnderPearlEntity.tick() for level, diagonal, upward, downward,
     * and water paths. This is smoke-only and never runs in a normal session.
     */
    private static void runPearlWorldSmokeTests(MinecraftClient client) {
        if (!Boolean.getBoolean("dipx.smoke.gui") || smokePearlWorldTestsComplete
                || !smokeWorldCreationStarted || client.player == null || client.world == null) {
            return;
        }
        PlayerEntity player = client.player;
        Vec3d savedPosition = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d savedVelocity = player.getVelocity();
        float savedYaw = player.getYaw();
        float savedPitch = player.getPitch();
        String[] names = {"level", "diagonal", "upward", "downward", "water-submerged", "water-edge"};
        float[] yaws = {0.0F, 45.0F, 0.0F, 0.0F, 30.0F, 0.0F};
        float[] pitches = {0.0F, 0.0F, -25.0F, 25.0F, -8.0F, 0.0F};
        // Keep the water fixture, prediction and vanilla comparison on the same horizon.
        final int smokeTicks = 12;
        // The integrated world spawn chunks are loaded around the real player.
        // Use that location as the fixture origin instead of teleporting to
        // (0, 128, 0), which may be several unloaded chunks away.
        // Stay in the vertical chunk section already streamed around the player.
        // The old fixed y=128 could be outside the client-loaded section on some seeds.
        int testY = MathHelper.floor(savedPosition.y) + 8;
        BlockPos anchor = BlockPos.ofFloored(savedPosition.x, testY, savedPosition.z);

        try {
            for (int scenario = 0; scenario < names.length; scenario++) {
                clearPearlSmokeBlocks(client);
                player.setPosition(scenario == 5 ? anchor.getX() + 0.9 : anchor.getX() + 0.5,
                        scenario == 5 ? anchor.getY() + 0.98 : anchor.getY(), anchor.getZ() + 0.5);
                player.setVelocity(Vec3d.ZERO);
                player.setYaw(yaws[scenario]);
                player.setPitch(pitches[scenario]);

                // First clear the entire dry centreline, including any naturally
                // generated water. This keeps downward tests independent of the
                // random lake/river layout around the integrated world's spawn.
                PearlTrajectoryTelemetry.Preview dryCorridor =
                        tracePearlCenterline(client, player, smokeTicks, false);
                clearPearlSmokePath(client, dryCorridor, false);

                if (scenario == 4) {
                    placePearlWaterPath(client, player, smokeTicks);
                } else if (scenario == 5) {
                    placePearlWaterEdgePath(client, anchor);
                }
                PearlTrajectoryTelemetry.Preview predicted =
                        tracePearlCenterline(client, player, smokeTicks, true);
                if (predicted.stopReason() != PearlTrajectoryTelemetry.StopReason.RANGE_LIMIT
                        || predicted.points().size() != smokeTicks + 1) {
                    throw new IllegalStateException(names[scenario] + " preview did not produce " + smokeTicks + " clear ticks: "
                            + predicted.stopReason() + " / " + predicted.points().size());
                }

                if (scenario == 5) {
                    for (PearlTrajectoryTelemetry.Point point : predicted.points()) {
                        BlockPos centerBlock = BlockPos.ofFloored(point.x(), point.y(), point.z());
                        if (client.world.getBlockState(centerBlock).isOf(Blocks.WATER)) {
                            throw new IllegalStateException("water-edge fixture flooded the pearl centre block");
                        }
                    }
                }

                clearPearlSmokePath(client, predicted, scenario >= 4);
                double maximumError = comparePearlToVanillaTick(
                        client, player, predicted, smokeTicks, scenario >= 4);
                if (!Double.isFinite(maximumError) || maximumError > 0.08) {
                    throw new IllegalStateException(names[scenario]
                            + " centreline diverged from vanilla tick by " + maximumError + " blocks");
                }
                if (scenario >= 4 && !predicted.touchedFluid()) {
                    throw new IllegalStateException(names[scenario] + " scenario did not detect vanilla water drag");
                }

                PearlTrajectoryTelemetry.Point target = predicted.points().get(10);
                BlockPos targetBlock = BlockPos.ofFloored(target.x(), target.y(), target.z());
                int wallStart = smokePearlTestBlocks.size();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            placePearlSmokeBlock(client, targetBlock.add(dx, dy, dz), false);
                        }
                    }
                }
                int wallBlocks = smokePearlTestBlocks.size() - wallStart;
                if (wallBlocks < 27) {
                    throw new IllegalStateException(names[scenario] + " collision wall fixture was incomplete: "
                            + wallBlocks + "/27 blocks applied");
                }
                LOGGER.info("DIPX pearl smoke collision wall placed scenario={} blocks={} target={}",
                        names[scenario], wallBlocks, targetBlock);
                lastPearlPreviewTick = Integer.MIN_VALUE;
                updatePearlPreview(client);
                if (pearlPreview.stopReason() != PearlTrajectoryTelemetry.StopReason.BLOCK
                        || pearlPreview.ticks() < 1 || pearlPreview.ticks() > 12) {
                    throw new IllegalStateException(names[scenario]
                            + " live ClientWorld collision query did not stop at the test wall: "
                            + pearlPreview.stopReason() + " @ " + pearlPreview.ticks() + "t");
                }
                if (scenario >= 4 && !pearlPreview.touchedFluid()) {
                    throw new IllegalStateException(names[scenario] + " collision preview did not retain its fluid marker");
                }
                LOGGER.info("DIPX pearl world smoke PASS scenario={} vanillaMaxError={} collision={} ticks={} fluid={}",
                        names[scenario], String.format(java.util.Locale.ROOT, "%.5f", maximumError),
                        pearlPreview.stopReason(), pearlPreview.ticks(), pearlPreview.touchedFluid());
            }
            smokePearlWorldTestsSucceeded = true;
            LOGGER.info("DIPX pearl world smoke complete scenarios=6");
        } catch (Throwable error) {
            LOGGER.error("DIPX pearl world smoke FAILED", error);
        } finally {
            clearPearlSmokeBlocks(client);
            player.setPosition(savedPosition.x, savedPosition.y, savedPosition.z);
            player.setVelocity(savedVelocity);
            player.setYaw(savedYaw);
            player.setPitch(savedPitch);
            smokePearlWorldTestsComplete = true;
        }
    }


    /**
     * Uses the player's feet (not the offset ray origin) for the displayed gap.
     * The +0.05 ray offset avoids starting inside the player's own collision box.
     */
    private static String maceLandingLine(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return "Mace landing: UNAVAILABLE";
        }
        boolean maceHeld = client.player.getMainHandStack().isOf(Items.MACE);
        if (!maceHeld) {
            return MaceLandingTelemetry.landingLine(false, 0.0, 0.0, 80);
        }

        double feetY = client.player.getBoundingBox().minY;
        Vec3d rayStart = new Vec3d(client.player.getX(), feetY + 0.05, client.player.getZ());
        Vec3d rayEnd = rayStart.add(0.0, -64.0, 0.0);
        BlockHitResult floorHit = client.world.raycast(new RaycastContext(
                rayStart, rayEnd, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, client.player));
        if (floorHit.getType() == HitResult.Type.MISS) {
            return MaceLandingTelemetry.noFloorLine(true);
        }

        // Measure from the actual feet. Measuring from rayStart adds the
        // collision-avoidance offset and visibly overstates the gap by 0.05m.
        double floorGap = Math.max(0.0, feetY - floorHit.getPos().y);
        return MaceLandingTelemetry.landingLine(
                true, floorGap, client.player.getVelocity().y, 80);
    }

    /** Reads the held spear's effective vanilla range and current crosshair result. */
    private static String currentSpearRangeLine(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return "Spear reach: UNAVAILABLE";
        }
        boolean spearHeld = client.player.getMainHandStack().isIn(ItemTags.SPEARS);
        if (!spearHeld) {
            return com.dipx.client.module.CombatHudTelemetry.spearRangeLine(
                    false, 0.0, 0.0, false, Double.NaN, false);
        }

        var range = client.player.getAttackRange();
        boolean entityUnderCrosshair = client.crosshairTarget instanceof EntityHitResult;
        double targetDistance = entityUnderCrosshair
                ? client.player.distanceTo(((EntityHitResult) client.crosshairTarget).getEntity())
                : Double.NaN;
        boolean blockUnderCrosshair = client.crosshairTarget instanceof BlockHitResult;
        return com.dipx.client.module.CombatHudTelemetry.spearRangeLine(
                true, range.getEffectiveMinRange(client.player),
                range.getEffectiveMaxRange(client.player),
                entityUnderCrosshair, targetDistance, blockUnderCrosshair);
    }

    /** Exercises the real loaded-world floor raycast and displayed landing estimate. */
    private static void runMaceLandingWorldSmokeTests(MinecraftClient client) {
        if (!Boolean.getBoolean("dipx.smoke.gui") || !smokePearlWorldTestsSucceeded
                || smokeMaceLandingTestsComplete || !smokeWorldCreationStarted
                || client.player == null || client.world == null) {
            return;
        }

        PlayerEntity player = client.player;
        Vec3d savedPosition = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d savedVelocity = player.getVelocity();
        float savedYaw = player.getYaw();
        float savedPitch = player.getPitch();
        int selectedSlot = player.getInventory().getSelectedSlot();
        var savedStack = player.getInventory().getStack(selectedSlot).copy();
        String[] names = {"one-block", "four-block", "twelve-block", "rising"};
        double[] gaps = {1.0, 4.0, 12.0, 4.0};
        double[] verticalSpeeds = {-0.20, -0.20, -0.20, 0.25};
        int floorY = MathHelper.floor(savedPosition.y) + 8;
        BlockPos anchor = BlockPos.ofFloored(savedPosition.x, floorY, savedPosition.z);

        try {
            player.getInventory().setStack(selectedSlot, Items.MACE.getDefaultStack());
            for (int scenario = 0; scenario < names.length; scenario++) {
                clearPearlSmokeBlocks(client);

                // A five-by-five platform guarantees a collidable floor beneath
                // the player's centre. Clear the vertical ray column so trees or
                // naturally generated blocks cannot shadow the test platform.
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        placePearlSmokeBlock(client, anchor.add(dx, 0, dz), false);
                    }
                }
                int clearTop = floorY + 1 + (int) Math.ceil(gaps[scenario]) + 2;
                for (int y = floorY + 1; y <= clearTop; y++) {
                    BlockPos column = new BlockPos(anchor.getX(), y, anchor.getZ());
                    if (!client.world.getBlockState(column).isAir()) {
                        if (!client.world.setBlockState(column, Blocks.AIR.getDefaultState())) {
                            throw new IllegalStateException("Mace landing ray column could not be cleared at " + column);
                        }
                        smokePearlTestBlocks.add(column.toImmutable());
                    }
                }

                player.setPosition(anchor.getX() + 0.5,
                        floorY + 1.0 + gaps[scenario], anchor.getZ() + 0.5);
                player.setVelocity(new Vec3d(0.0, verticalSpeeds[scenario], 0.0));
                String line = maceLandingLine(client);
                String expectedGap = String.format(java.util.Locale.ROOT, "%.1f", gaps[scenario]);
                if (!line.contains("gap: " + expectedGap + "m")) {
                    throw new IllegalStateException(names[scenario]
                            + " raycast gap mismatch: expected " + expectedGap + "m, got " + line);
                }

                if (scenario == 3) {
                    if (!line.contains("RISING") || !line.contains("up: 0.25 b/t")) {
                        throw new IllegalStateException("Rising Mace state was not reported: " + line);
                    }
                } else {
                    int expectedTicks = MaceLandingTelemetry.estimateTicksToGround(
                            gaps[scenario], verticalSpeeds[scenario],
                            MaceLandingTelemetry.DEFAULT_GRAVITY,
                            MaceLandingTelemetry.DEFAULT_AIR_DRAG, 80);
                    if (expectedTicks < 1 || !line.contains("~" + expectedTicks + "t")
                            || !line.contains("down: 0.20 b/t")) {
                        throw new IllegalStateException(names[scenario]
                                + " landing ETA mismatch: expected ~" + expectedTicks + "t, got " + line);
                    }
                }
                LOGGER.info("DIPX mace landing world smoke PASS scenario={} result={}",
                        names[scenario], line);
            }

            // Validate the actual 1.21.11 spear attack_range component in a
            // survival-like state. The HUD reads this same effective vanilla
            // component; it does not infer a hard-coded reach from item names.
            boolean originalCreativeMode = player.getAbilities().creativeMode;
            var maceStack = player.getInventory().getStack(selectedSlot).copy();
            try {
                player.getAbilities().creativeMode = false;
                player.getInventory().setStack(selectedSlot, Items.IRON_SPEAR.getDefaultStack());
                var spearAttackRange = player.getAttackRange();
                if (Math.abs(spearAttackRange.minRange() - 2.0f) > 0.001f
                        || Math.abs(spearAttackRange.maxRange() - 4.5f) > 0.001f) {
                    throw new IllegalStateException("Iron spear vanilla attack_range mismatch: min="
                            + spearAttackRange.minRange() + " max=" + spearAttackRange.maxRange());
                }
                String spearLine = currentSpearRangeLine(client);
                if (!spearLine.contains("2.0-4.5m")) {
                    throw new IllegalStateException("Spear reach HUD did not use effective survival range: "
                            + spearLine);
                }
                LOGGER.info("DIPX spear range world smoke PASS result={}", spearLine);
            } finally {
                player.getInventory().setStack(selectedSlot, maceStack);
                player.getAbilities().creativeMode = originalCreativeMode;
            }
            LOGGER.info("DIPX mace landing world smoke complete scenarios=4");
        } catch (Throwable error) {
            LOGGER.error("DIPX mace landing world smoke FAILED", error);
        } finally {
            clearPearlSmokeBlocks(client);
            player.setPosition(savedPosition.x, savedPosition.y, savedPosition.z);
            player.setVelocity(savedVelocity);
            player.setYaw(savedYaw);
            player.setPitch(savedPitch);
            player.getInventory().setStack(selectedSlot, savedStack);
            smokeMaceLandingTestsComplete = true;
        }
    }

    /** Draws a compact side-view arc so it remains readable on touch screens. */
    private static void drawPearlGraph(DrawContext context, MinecraftClient client,
                                       PearlTrajectoryTelemetry.Preview preview) {
        if (preview == null || preview.points().size() < 2) return;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        if (screenWidth < 84 || screenHeight < 66) return;

        int panelWidth = Math.min(146, screenWidth - 12);
        int panelHeight = Math.min(78, screenHeight - 12);
        int panelX = screenWidth - panelWidth - 6;
        int panelY = screenHeight - panelHeight - 6;
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC70E0B15);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFF9B6BD0);
        context.drawText(client.textRenderer, "PEARL PATH · CENTERLINE",
                panelX + 5, panelY + 5, 0xFFE9D8FF, false);

        int plotX = panelX + 5;
        int plotY = panelY + 18;
        int plotWidth = panelWidth - 10;
        int plotHeight = panelHeight - 30;
        if (plotWidth < 4 || plotHeight < 4) return;

        var points = preview.points();
        var origin = points.get(0);
        double minHeight = Double.POSITIVE_INFINITY;
        double maxHeight = Double.NEGATIVE_INFINITY;
        double maxDistance = 0.0;
        for (var point : points) {
            double heightDelta = point.y() - origin.y();
            double distance = Math.hypot(point.x() - origin.x(), point.z() - origin.z());
            minHeight = Math.min(minHeight, heightDelta);
            maxHeight = Math.max(maxHeight, heightDelta);
            maxDistance = Math.max(maxDistance, distance);
        }
        if (maxDistance < 0.01) maxDistance = 1.0;
        if (maxHeight - minHeight < 0.01) {
            minHeight -= 0.5;
            maxHeight += 0.5;
        }
        if (minHeight <= 0.0 && maxHeight >= 0.0) {
            int groundY = plotY + (int) Math.round(
                    (maxHeight / (maxHeight - minHeight)) * (plotHeight - 1));
            context.fill(plotX, groundY, plotX + plotWidth, groundY + 1, 0x553F3850);
        }

        int previousX = -1;
        int previousY = -1;
        int endpointColor = switch (preview.stopReason()) {
            case BLOCK -> 0xFFFFA16E;
            case ENTITY -> 0xFF8DE6FF;
            case RANGE_LIMIT -> 0xFFBBA6D8;
            case UNAVAILABLE -> 0xFF77717F;
        };
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            double distance = Math.hypot(point.x() - origin.x(), point.z() - origin.z());
            double heightDelta = point.y() - origin.y();
            int px = plotX + (int) Math.round(distance / maxDistance * (plotWidth - 1));
            int py = plotY + (int) Math.round(
                    (maxHeight - heightDelta) / (maxHeight - minHeight) * (plotHeight - 1));
            int color = i == points.size() - 1 ? endpointColor : 0xFFD9B7FF;
            if (previousX >= 0) {
                drawPearlGraphLine(context, previousX, previousY, px, py,
                        plotX, plotY, plotX + plotWidth, plotY + plotHeight, 0xFFD9B7FF);
            }
            context.fill(px - 1, py - 1, px + 2, py + 2, color);
            previousX = px;
            previousY = py;
        }

        String footer = PearlTrajectoryTelemetry.graphFooter(preview);
        context.drawText(client.textRenderer,
                client.textRenderer.trimToWidth(footer, panelWidth - 10),
                panelX + 5, panelY + panelHeight - 10, endpointColor, false);
    }

    private static void drawPearlGraphLine(DrawContext context, int x0, int y0, int x1, int y1,
                                           int left, int top, int right, int bottom, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            if (x0 >= left && x0 < right && y0 >= top && y0 < bottom) {
                context.fill(x0, y0, x0 + 1, y0 + 1, color);
            }
            if (x0 == x1 && y0 == y1) break;
            int twiceError = 2 * error;
            if (twiceError >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twiceError <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    /** Uses an isolated directory only in the CI interaction test. */
    private static Path configDirectory() {
        if (Boolean.getBoolean("dipx.smoke.gui")) {
            String override = System.getProperty("dipx.smoke.configDir");
            if (override != null && !override.isBlank()) return Path.of(override);
        }
        return FabricLoader.getInstance().getConfigDir();
    }

    private static boolean enabled(String name) {
        for (DIPXModule module : ModuleRegistry.all()) {
            if (module.name().equals(name)) return module.enabled();
        }
        return false;
    }

    /** Saves module states immediately after a user changes a GUI toggle. */
    public static void saveModuleConfig() {
        try {
            Path directory = configDirectory();
            ModuleConfigStore.save(directory, ModuleRegistry.all());
            if (Boolean.getBoolean("dipx.smoke.gui")) {
                LOGGER.info("DIPX smoke config saved: {}",
                        directory.resolve(ModuleConfigStore.FILE_NAME).toAbsolutePath());
            }
        } catch (IOException | SecurityException configError) {
            LOGGER.warn("Could not save DIPX module settings", configError);
        }
    }

    /**
     * In CI only, prove the just-saved state can be loaded from disk again.
     * The temporary in-memory flip makes this a real save/load round-trip,
     * not merely a check of the current module object.
     */
    public static void verifySmokeConfigReload(DIPXModule module) {
        if (!Boolean.getBoolean("dipx.smoke.gui")) return;
        boolean expected = module.enabled();
        module.setEnabled(!expected);
        try {
            ModuleConfigStore.load(configDirectory(), ModuleRegistry.all());
        } catch (IOException | SecurityException configError) {
            LOGGER.error("DIPX smoke config reload failed", configError);
            return;
        }
        if (module.enabled() != expected) {
            LOGGER.error("DIPX smoke config reload mismatch: expected {} but found {}",
                    expected, module.enabled());
            return;
        }
        LOGGER.info("DIPX smoke config reloaded: {}={} enabledCount={}",
                module.name(), module.enabled(), ModuleRegistry.enabledCount());
    }

    /** Restores the screen that was visible before DIPX was opened. */
    public static void closeGui() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen instanceof DIPXScreen) {
            Screen restore = previousScreen;
            previousScreen = null;
            client.setScreen(restore);
            LOGGER.info("DIPX GUI closed");
        }
    }

    @Override
    public void onInitializeClient() {
        ModuleRegistry.registerDefaults();
        try {
            ModuleConfigStore.load(configDirectory(), ModuleRegistry.all());
        } catch (IOException | SecurityException configError) {
            LOGGER.warn("Could not load DIPX module settings; using defaults", configError);
        }
        LOGGER.info("DIPX initialized: {} modules registered ({} enabled)",
                ModuleRegistry.all().size(), ModuleRegistry.enabledCount());
        if (Boolean.getBoolean("dipx.smoke.gui")) {
            LOGGER.info("DIPX smoke property present at client initialization");
        }

        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.dipx.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (Boolean.getBoolean("dipx.smoke.gui")) {
                // The first-run onboarding screen steals game focus. Dismiss it
                // only in CI smoke mode, then exercise the registered keybinding
                // through Minecraft's own KeyBinding event queue.
                if (!smokeOnboardingDismissed && client.currentScreen != null
                        && client.currentScreen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                    client.setScreen(null);
                    smokeOnboardingDismissed = true;
                    LOGGER.info("DIPX smoke dismissed first-run accessibility onboarding");
                }
                if (!smokeModeLogged) {
                    String screenName = client.currentScreen == null
                            ? "none" : client.currentScreen.getClass().getSimpleName();
                    LOGGER.info("DIPX smoke mode active; initial screen={}", screenName);
                    smokeModeLogged = true;
                }
                if (!smokeOpenInjected && client.currentScreen != null
                        && client.currentScreen.getClass().getSimpleName().equals("TitleScreen")) {
                    KeyBinding.onKeyPressed(openGui.getDefaultKey());
                    smokeOpenInjected = true;
                    LOGGER.info("DIPX smoke injected registered binding event: {}",
                            openGui.getBoundKeyTranslationKey());
                }
                if (smokeOpenInjected && client.currentScreen instanceof DIPXScreen) {
                    smokeOpenTicks++;
                    if (smokeOpenTicks >= 300 && !smokeCloseInjected) {
                        KeyBinding.onKeyPressed(openGui.getDefaultKey());
                        smokeCloseInjected = true;
                        LOGGER.info("DIPX smoke injected close binding event");
                    }
                }
            }
            if (client.options != null) {
                if (originalGamma == null) originalGamma = client.options.getGamma().getValue();
                client.options.getGamma().setValue(enabled("FullBright") ? 16.0 : originalGamma);
            }
            // Mobile launchers/keyboards can enqueue more than one press for a
            // single tap. Drain the queue, then toggle at most once per tick.
            boolean guiPressed = false;
            while (openGui.wasPressed()) guiPressed = true;
            if (guiPressed) {
                String screenName = client.currentScreen == null
                        ? "none" : client.currentScreen.getClass().getSimpleName();
                LOGGER.info("DIPX Right Shift received while screen={}", screenName);
            }
            if (guiPressed && client.currentScreen instanceof DIPXScreen) {
                try {
                    closeGui();
                } catch (RuntimeException screenError) {
                    LOGGER.error("Failed to close DIPX GUI", screenError);
                }
            } else if (guiPressed) {
                try {
                    previousScreen = client.currentScreen;
                    client.setScreen(new DIPXScreen());
                    LOGGER.info("DIPX GUI opened; {} modules available", ModuleRegistry.all().size());
                } catch (RuntimeException screenError) {
                    previousScreen = null;
                    LOGGER.error("Failed to open DIPX GUI", screenError);
                }
            }

            // Toggle Sprint: client-side convenience; only engages while moving forward.
            if (client.player != null && enabled("Toggle Sprint")) {
                boolean shouldSprint = client.player.forwardSpeed > 0.0F
                        && !client.player.isSneaking()
                        && client.player.getHungerManager().getFoodLevel() > 6;
                client.player.setSprinting(shouldSprint);
            }
            updatePearlPreview(client);
            if (Boolean.getBoolean("dipx.smoke.gui")) {
                openSmokeWorldCreator(client);
                createSmokeWorld(client);
                runPearlWorldSmokeTests(client);
                runMaceLandingWorldSmokeTests(client);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || client.player == null || client.options == null) return;

            int total = ModuleRegistry.all().size();
            long active = ModuleRegistry.enabledCount();
            int x = 8;
            int y = 8;
            drawContext.drawText(client.textRenderer,
                    Text.literal("DIPX  |  " + total + " modules  |  " + active + " ON"),
                    x, y, 0xFFB58CFF, true);
            y += 12;

            if (enabled("Coordinates HUD")) {
                var p = client.player;
                String coords = String.format(java.util.Locale.ROOT, "XYZ: %.1f / %.1f / %.1f",
                        p.getX(), p.getY(), p.getZ());
                drawContext.drawText(client.textRenderer, Text.literal(coords),
                        x, y, 0xFFE6D9FF, true);
                y += 12;
            }

            if (enabled("Totem Counter")) {
                int totems = client.player.getInventory().count(Items.TOTEM_OF_UNDYING);
                drawContext.drawText(client.textRenderer, Text.literal("Totems: " + totems),
                        x, y, 0xFFFFD66B, true);
                y += 12;
            }

            if (enabled("Wind Charge Counter")) {
                int charges = client.player.getInventory().count(Items.WIND_CHARGE);
                drawContext.drawText(client.textRenderer, Text.literal("Wind Charges: " + charges),
                        x, y, 0xFF8DEBFF, true);
                y += 12;
            }


            if (enabled("Armor HUD")) {
                StringBuilder armor = new StringBuilder("Armor: ");
                boolean hasArmor = false;
                for (EquipmentSlot slot : new EquipmentSlot[] {
                        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
                }) {
                    var stack = client.player.getEquippedStack(slot);
                    if (!stack.isEmpty()) {
                        if (hasArmor) armor.append(" | ");
                        int remaining = stack.getMaxDamage() - stack.getDamage();
                        armor.append(stack.getName().getString()).append(" ")
                                .append(remaining).append("/").append(stack.getMaxDamage());
                        hasArmor = true;
                    }
                }
                if (!hasArmor) armor.append("none");
                drawContext.drawText(client.textRenderer, Text.literal(armor.toString()),
                        x, y, 0xFFC7F7D4, true);
                y += 12;
            }

            if (enabled("Target Info HUD") && client.crosshairTarget instanceof EntityHitResult hit) {
                Entity target = hit.getEntity();
                String targetLine = target.getName().getString()
                        + "  " + String.format(java.util.Locale.ROOT, "%.1fm", client.player.distanceTo(target));
                drawContext.drawText(client.textRenderer, Text.literal(targetLine),
                        x, y, 0xFFFFC0E8, true);
            }

            // Manual timing only: shows the vanilla attack cooldown without
            // sending clicks, changing targets, or automating attacks.
            if (enabled("Attack Cooldown HUD")) {
                float cooldown = client.player.getAttackCooldownProgress(0.0F);
                int percent = Math.round(cooldown * 100.0F);
                String timing = "Attack timing: " + percent + "%  "
                        + (percent >= 100 ? "READY" : "RECHARGING");
                drawContext.drawText(client.textRenderer, Text.literal(timing),
                        x, y, percent >= 100 ? 0xFF9AF5BD : 0xFFFFD18A, true);
                y += 12;
            }

            if (enabled("Mace Inventory HUD")) {
                int maces = client.player.getInventory().count(Items.MACE);
                drawContext.drawText(client.textRenderer, Text.literal("Maces: " + maces),
                        x, y, 0xFFE7B6FF, true);
                y += 12;
            }

            if (enabled("Spear Inventory HUD")) {
                int spears = 0;
                for (int slotIndex = 0; slotIndex < client.player.getInventory().size(); slotIndex++) {
                    var stack = client.player.getInventory().getStack(slotIndex);
                    if (!stack.isEmpty()
                            && Registries.ITEM.getId(stack.getItem()).getPath().contains("spear")) {
                        spears += stack.getCount();
                    }
                }
                drawContext.drawText(client.textRenderer, Text.literal("Spears: " + spears),
                        x, y, 0xFF9CE8FF, true);
                y += 12;
            }

            // Manual spear helper: only reports the player's own hotbar slot
            // and the entity already under the crosshair. It never swaps items.
            if (enabled("Spear Assist HUD")) {
                int spearSlot = -1;
                for (int slotIndex = 0; slotIndex < 9; slotIndex++) {
                    var stack = client.player.getInventory().getStack(slotIndex);
                    if (!stack.isEmpty()
                            && Registries.ITEM.getId(stack.getItem()).getPath().contains("spear")) {
                        spearSlot = slotIndex + 1;
                        break;
                    }
                }
                String targetRange = "no target";
                if (client.crosshairTarget instanceof EntityHitResult spearHit) {
                    targetRange = String.format(java.util.Locale.ROOT, "%.1fm",
                            client.player.distanceTo(spearHit.getEntity()));
                }
                String spearAssist = (spearSlot < 0 ? "Spear: not in hotbar"
                        : "Spear slot: " + spearSlot) + "  |  Crosshair: " + targetRange;
                drawContext.drawText(client.textRenderer, Text.literal(spearAssist),
                        x, y, 0xFF9CE8FF, true);
                y += 12;

                String spearReach = currentSpearRangeLine(client);
                int availableHudWidth = Math.max(1, client.getWindow().getScaledWidth() - x - 8);
                spearReach = client.textRenderer.trimToWidth(spearReach, availableHudWidth);
                drawContext.drawText(client.textRenderer, Text.literal(spearReach),
                        x, y, 0xFF9CE8FF, true);
                y += 12;
            }

            // Manual spear telemetry only: read the active-use timer and the
            // player's horizontal velocity; never trigger or release an attack.
            if (enabled("Spear Charge HUD")) {
                boolean chargingSpear = client.player.isUsingItem()
                        && Registries.ITEM.getId(client.player.getActiveItem().getItem())
                                .getPath().contains("spear");
                int useTicks = chargingSpear ? client.player.getItemUseTime() : 0;
                var velocity = client.player.getVelocity();
                double horizontalSpeed = Math.sqrt(
                        velocity.x * velocity.x + velocity.z * velocity.z);
                String spearCharge = com.dipx.client.module.CombatHudTelemetry.spearChargeLine(
                        chargingSpear, useTicks, horizontalSpeed);
                drawContext.drawText(client.textRenderer, Text.literal(spearCharge),
                        x, y, 0xFF9CE8FF, true);
                y += 12;
            }

            // Read-only movement telemetry for clutch timing and aerial mace play.
            if (enabled("Movement Telemetry HUD")) {
                var velocity = client.player.getVelocity();
                double horizontalSpeed = Math.sqrt(
                        velocity.x * velocity.x + velocity.z * velocity.z);
                String movementLine = com.dipx.client.module.MovementHudTelemetry.movementLine(
                        horizontalSpeed, velocity.y, client.player.isOnGround(),
                        client.player.isSprinting(), client.player.isSneaking());
                drawContext.drawText(client.textRenderer, Text.literal(movementLine),
                        x, y, 0xFFB7D8FF, true);
                y += 12;
            }

            if (enabled("Weapon Swap HUD")) {
                String held = client.player.getMainHandStack().isEmpty()
                        ? "Empty" : client.player.getMainHandStack().getName().getString();
                String swap = "Held: " + held + "  Slot: " + (client.player.getInventory().getSelectedSlot() + 1);
                drawContext.drawText(client.textRenderer, Text.literal(swap),
                        x, y, 0xFFFFD9A8, true);
                y += 12;
            }

            if (enabled("Breach Status HUD") && client.crosshairTarget instanceof EntityHitResult armorHit
                    && armorHit.getEntity() instanceof PlayerEntity targetPlayer) {
                int remaining = 0, maximum = 0;
                for (EquipmentSlot slot : new EquipmentSlot[] {
                        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
                }) {
                    var stack = targetPlayer.getEquippedStack(slot);
                    if (!stack.isEmpty() && stack.isDamageable()) {
                        remaining += stack.getMaxDamage() - stack.getDamage();
                        maximum += stack.getMaxDamage();
                    }
                }
                drawContext.drawText(client.textRenderer,
                        Text.literal("Target armor: " + (maximum == 0 ? "none" : remaining + "/" + maximum)),
                        x, y, 0xFFFFA6A6, true);
                y += 12;
            }

            // Manual shield information only: inspect the player already under the crosshair.
            if (enabled("Shield Status HUD")) {
                String shieldLine;
                if (client.crosshairTarget instanceof EntityHitResult shieldHit
                        && shieldHit.getEntity() instanceof PlayerEntity targetShieldPlayer) {
                    var activeStack = targetShieldPlayer.getActiveItem();
                    var mainStack = targetShieldPlayer.getMainHandStack();
                    var offStack = targetShieldPlayer.getOffHandStack();
                    var shieldStack = targetShieldPlayer.isUsingItem()
                            && activeStack.getItem() == Items.SHIELD
                            ? activeStack
                            : mainStack.getItem() == Items.SHIELD ? mainStack
                            : offStack.getItem() == Items.SHIELD ? offStack : null;
                    boolean hasShield = shieldStack != null;
                    int maximum = hasShield ? shieldStack.getMaxDamage() : 0;
                    int remaining = hasShield
                            ? Math.max(0, maximum - shieldStack.getDamage()) : 0;
                    shieldLine = com.dipx.client.module.ShieldStatusTelemetry.shieldLine(
                            true, hasShield, targetShieldPlayer.isBlocking(), remaining, maximum);
                } else {
                    shieldLine = com.dipx.client.module.ShieldStatusTelemetry.shieldLine(
                            false, false, false, 0, 0);
                }
                drawContext.drawText(client.textRenderer, Text.literal(shieldLine),
                        x, y, 0xFFFFC68A, true);
                y += 12;
            }

            if (enabled("Mace Smash HUD")) {
                var velocity = client.player.getVelocity();
                boolean maceHeld = client.player.getMainHandStack().getItem() == Items.MACE;
                boolean vanillaSmashReady = MaceItem.shouldDealAdditionalDamage(client.player);
                String impactLine = com.dipx.client.module.MaceImpactTelemetry.impactLine(
                        maceHeld, vanillaSmashReady, client.player.fallDistance, velocity.y);
                drawContext.drawText(client.textRenderer, Text.literal(impactLine),
                        x, y, vanillaSmashReady && maceHeld ? 0xFF9AF5BD : 0xFFFF9B75, true);
                y += 12;
            }

            if (enabled("Mace Landing HUD")) {
                String landingLine = maceLandingLine(client);
                drawContext.drawText(client.textRenderer, Text.literal(landingLine),
                        x, y, 0xFFFFC58A, true);
                y += 12;
            }

            if (enabled("Pearl Trajectory HUD")) {
                String pearlLine = PearlTrajectoryTelemetry.summary(pearlAvailable, pearlPreview);
                drawContext.drawText(client.textRenderer,
                        Text.literal(client.textRenderer.trimToWidth(pearlLine, 260)),
                        x, y, 0xFFD9B7FF, true);
                y += 12;
                if (pearlAvailable) drawPearlGraph(drawContext, client, pearlPreview);
            }
        });
    }
}
