package com.dipx.client;

import com.dipx.client.gui.DIPXScreen;
import com.dipx.client.module.ModuleRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class DIPXClient implements ClientModInitializer {
    private static KeyBinding openGui;

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
            int enabled = ModuleRegistry.enabledCount();
            drawContext.drawText(
                    client.textRenderer,
                    Text.literal("DIPX  |  " + enabled + "/" + total + " modules"),
                    8, 8, 0xFFB58CFF, true
            );
        });
    }
}
