package com.dipx.client;

import com.dipx.client.gui.DIPXScreen;
import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** DIPX client bootstrap and lightweight, client-side PvP information HUDs. */
public final class DIPXClient implements ClientModInitializer {
    private static KeyBinding openGui;

    private static boolean enabled(String name) {
        for (DIPXModule module : ModuleRegistry.all()) {
            if (module.name().equals(name)) return module.enabled();
        }
        return false;
    }

    @Override
    public void onInitializeClient() {
        ModuleRegistry.registerDefaults();

        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.dipx.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGui.wasPressed()) {
                if (client.currentScreen instanceof DIPXScreen) {
                    client.setScreen(null);
                } else if (client.currentScreen == null) {
                    client.setScreen(new DIPXScreen());
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
                    Text.literal("DIPX  |  " + active + "/" + total + " modules"),
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

            if (enabled("Mace Smash HUD")) {
                double fall = client.player.fallDistance;
                drawContext.drawText(client.textRenderer,
                        Text.literal(String.format(java.util.Locale.ROOT, "Fall: %.1f blocks", fall)),
                        x, y, 0xFFFF9B75, true);
            }
        });
    }
}
