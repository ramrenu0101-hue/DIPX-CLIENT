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

            if (enabled("Mace Smash HUD")) {
                double fall = client.player.fallDistance;
                drawContext.drawText(client.textRenderer,
                        Text.literal(String.format(java.util.Locale.ROOT, "Fall: %.1f blocks", fall)),
                        x, y, 0xFFFF9B75, true);
            }
        });
    }
}
