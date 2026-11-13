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
        add(pending, "FullBright", "Gamma visibility", ModuleCategory.RENDER);
        add(pending, "Toggle Sprint", "Sprint while moving", ModuleCategory.MOVEMENT);
        add(pending, "Coordinates HUD", "Live XYZ coordinates", ModuleCategory.PLAYER);
        add(pending, "Totem Counter", "Carried totem count", ModuleCategory.PLAYER);
        add(pending, "Wind Charge Counter", "Wind charge count", ModuleCategory.PLAYER);
        add(pending, "Armor HUD", "Equipped armor status", ModuleCategory.PLAYER);
        add(pending, "Target Info HUD", "Target name and range", ModuleCategory.COMBAT);
        add(pending, "Mace Smash HUD", "Mace fall timing", ModuleCategory.MACE_PVP);
        add(pending, "Mace Inventory HUD", "Mace count", ModuleCategory.MACE_PVP);
        add(pending, "Spear Inventory HUD", "Spear count", ModuleCategory.SPEAR_PVP);
        add(pending, "Breach Status HUD", "Target armor status", ModuleCategory.COMBAT);
        add(pending, "Weapon Swap HUD", "Held item and slot", ModuleCategory.COMBAT);

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
