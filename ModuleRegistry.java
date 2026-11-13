package com.dipx.client.module;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Registry contains only modules with real behavior wired in DIPXClient. */
public final class ModuleRegistry {
    private static final List<DIPXModule> MODULES = new ArrayList<>();
    private static boolean registered;

    private ModuleRegistry() {}

    private static void add(List<DIPXModule> target, String name,
                            String description, ModuleCategory category) {
        target.add(new DIPXModule(name, description, category));
    }

    /**
     * Builds the registry atomically and safely tolerates repeated initializer
     * calls. The registered flag is set only after all entries validate.
     */
    public static synchronized void registerDefaults() {
        if (registered) return;

        List<DIPXModule> pending = new ArrayList<>();
        add(pending, "FullBright", "Client gamma visibility toggle", ModuleCategory.RENDER);
        add(pending, "Toggle Sprint", "Sprint while moving forward", ModuleCategory.MOVEMENT);
        add(pending, "Coordinates HUD", "Live XYZ position overlay", ModuleCategory.PLAYER);
        add(pending, "Totem Counter", "Count totems in inventory", ModuleCategory.PLAYER);
        add(pending, "Wind Charge Counter", "Count wind charges in inventory", ModuleCategory.PLAYER);
        add(pending, "Armor HUD", "Show equipped armor durability", ModuleCategory.PLAYER);
        add(pending, "Target Info HUD", "Show crosshair target and distance", ModuleCategory.COMBAT);
        add(pending, "Mace Smash HUD", "Show current fall distance for smash timing", ModuleCategory.MACE_PVP);
        add(pending, "Mace Inventory HUD", "Count mace items available in inventory", ModuleCategory.MACE_PVP);
        add(pending, "Spear Inventory HUD", "Count spears available in inventory", ModuleCategory.SPEAR_PVP);
        add(pending, "Breach Status HUD", "Show target player armor durability", ModuleCategory.COMBAT);
        add(pending, "Weapon Swap HUD", "Show held item and selected hotbar slot", ModuleCategory.COMBAT);

        Set<String> names = new HashSet<>();
        for (DIPXModule module : pending) {
            if (module.name() == null || module.name().isBlank()
                    || module.description() == null || module.description().isBlank()
                    || module.category() == null || !names.add(module.name())) {
                throw new IllegalStateException("Invalid or duplicate DIPX module: " + module.name());
            }
        }

        MODULES.addAll(pending);
        registered = true;
    }

    /** Returns an immutable snapshot, never a mutable view of registry state. */
    public static synchronized List<DIPXModule> all() {
        return List.copyOf(MODULES);
    }

    public static synchronized long enabledCount() {
        return MODULES.stream().filter(DIPXModule::enabled).count();
    }
}
