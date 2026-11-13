package com.dipx.client;

import com.dipx.client.gui.DIPXScreen;
import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
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
    private static final Logger LOGGER = LoggerFactory.getLogger("DIPX/Client");

    private static boolean enabled(String name) {
        for (DIPXModule module : ModuleRegistry.all()) {
            if (module.name().equals(name)) return module.enabled();
        }
        return false;
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
        LOGGER.info("DIPX initialized: {} modules registered", ModuleRegistry.all().size());
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

            if (enabled("Mace Smash HUD")) {
                double fall = client.player.fallDistance;
                drawContext.drawText(client.textRenderer,
                        Text.literal(String.format(java.util.Locale.ROOT, "Fall: %.1f blocks", fall)),
                        x, y, 0xFFFF9B75, true);
            }
        });
    }
}
