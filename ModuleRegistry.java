package com.dipx.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only modules with implemented behavior are exposed in the client GUI. */
public final class ModuleRegistry {
    private static final List<DIPXModule> MODULES = new ArrayList<>();
    private static boolean registered;
    private ModuleRegistry() {}
    private static void add(String name, String description, ModuleCategory category) {
        MODULES.add(new DIPXModule(name, description, category));
    }
    public static synchronized void registerDefaults() {
        if (registered) return;
        registered = true;
        // Real behavior currently wired in DIPXClient. No placeholder entries.
        add("FullBright", "Client gamma visibility toggle", ModuleCategory.RENDER);
        add("Toggle Sprint", "Sprint while moving forward", ModuleCategory.MOVEMENT);
        add("Coordinates HUD", "Live XYZ position overlay", ModuleCategory.PLAYER);
        add("Totem Counter", "Count totems in inventory", ModuleCategory.PLAYER);
        add("Wind Charge Counter", "Count wind charges in inventory", ModuleCategory.PLAYER);
        add("Armor HUD", "Show equipped armor durability", ModuleCategory.PLAYER);
        add("Target Info HUD", "Show crosshair target and distance", ModuleCategory.COMBAT);
        add("Mace Smash HUD", "Show current fall distance for smash timing", ModuleCategory.MACE_PVP);
        add("Mace Inventory HUD", "Count mace items available in inventory", ModuleCategory.MACE_PVP);
        add("Spear Inventory HUD", "Count spears available in inventory", ModuleCategory.SPEAR_PVP);
        add("Breach Status HUD", "Show target player armor durability", ModuleCategory.COMBAT);
        add("Weapon Swap HUD", "Show held item and selected hotbar slot", ModuleCategory.COMBAT);
    }
    public static List<DIPXModule> all() { return Collections.unmodifiableList(MODULES); }
    public static long enabledCount() { return MODULES.stream().filter(DIPXModule::enabled).count(); }
}
