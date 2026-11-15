package com.dipx.client.ci;

import com.dipx.client.gui.DIPXLayout;
import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleCategory;
import com.dipx.client.module.ModuleRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dependency-free regression checks run by the GitHub Actions build. */
public final class ModuleRegistrySmokeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ModuleRegistry.registerDefaults();
        List<DIPXModule> modules = ModuleRegistry.all();
        check(modules.size() == 14, "Expected 14 implemented modules, got " + modules.size());
        check(ModuleRegistry.enabledCount() == 0, "Modules must start disabled");

        Set<String> names = new HashSet<>();
        for (DIPXModule module : modules) {
            check(module.name() != null && !module.name().isBlank(), "Module name is empty");
            check(module.description() != null && !module.description().isBlank(),
                    "Description missing for " + module.name());
            check(module.category() != null, "Category missing for " + module.name());
            check(names.add(module.name()), "Duplicate module " + module.name());
        }

        check(names.contains("Mace Smash HUD"), "Mace timing HUD is missing");
        check(names.contains("Spear Inventory HUD"), "Spear inventory HUD is missing");
        check(names.contains("Breach Status HUD"), "Breach status HUD is missing");
        check(names.contains("Weapon Swap HUD"), "Weapon swap HUD is missing");
        check(names.contains("Attack Cooldown HUD"), "Manual attack timing HUD is missing");
        check(names.contains("Spear Assist HUD"), "Manual spear helper HUD is missing");

        DIPXModule first = modules.get(0);
        first.toggle();
        check(first.enabled(), "Toggle did not enable the module");
        check(ModuleRegistry.enabledCount() == 1, "Enabled count did not update");
        first.setEnabled(false);
        check(ModuleRegistry.enabledCount() == 0, "Enabled count did not reset");

        ModuleRegistry.registerDefaults();
        check(ModuleRegistry.all().size() == 14, "Repeated registration duplicated modules");

        // Settings round-trip through a temporary config directory, never the user's config.
        java.nio.file.Path configDirectory = null;
        try {
            configDirectory = java.nio.file.Files.createTempDirectory("dipx-config-test");
            DIPXModule fullBright = modules.stream()
                    .filter(module -> module.name().equals("FullBright")).findFirst().orElseThrow();
            DIPXModule spearAssist = modules.stream()
                    .filter(module -> module.name().equals("Spear Assist HUD")).findFirst().orElseThrow();
            fullBright.setEnabled(true);
            spearAssist.setEnabled(true);
            ModuleConfigStore.save(configDirectory, modules);
            java.nio.file.Path configFile = configDirectory.resolve(ModuleConfigStore.FILE_NAME);
            check(java.nio.file.Files.isRegularFile(configFile), "Config file was not written");

            for (DIPXModule module : modules) module.setEnabled(false);
            ModuleConfigStore.load(configDirectory, modules);
            check(fullBright.enabled() && spearAssist.enabled(),
                    "Enabled module states were not restored");
            check(ModuleRegistry.enabledCount() == 2, "Restored enabled count is incorrect");

            java.util.Properties malformed = new java.util.Properties();
            malformed.setProperty("module.FullBright", "not-a-boolean");
            malformed.setProperty("module.Toggle Sprint", "true");
            malformed.setProperty("module.Removed Module", "true");
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(configFile)) {
                malformed.store(output, "regression input");
            }
            ModuleConfigStore.load(configDirectory, modules);
            check(fullBright.enabled(), "Malformed value should not overwrite a valid in-memory state");
            check(modules.stream().filter(module -> module.name().equals("Toggle Sprint"))
                    .findFirst().orElseThrow().enabled(), "Known setting was not loaded");
            check(ModuleRegistry.enabledCount() == 3,
                    "Unknown settings or malformed values changed the wrong modules");

            java.nio.file.Files.writeString(configFile, "module.FullBright=\\uZZZZ\\n");
            boolean corruptFileRejected = false;
            try {
                ModuleConfigStore.load(configDirectory, modules);
            } catch (java.io.IOException expected) {
                corruptFileRejected = true;
            }
            check(corruptFileRejected, "Corrupt properties file was not reported safely");
        } catch (java.io.IOException configFailure) {
            throw new AssertionError("Module config persistence failed", configFailure);
        } finally {
            if (configDirectory != null) {
                try (var paths = java.nio.file.Files.walk(configDirectory)) {
                    for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        java.nio.file.Files.deleteIfExists(path);
                    }
                } catch (java.io.IOException cleanupFailure) {
                    throw new AssertionError("Could not clean isolated config test directory", cleanupFailure);
                }
            }
        }

        boolean immutable = false;
        try {
            modules.clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "Registry snapshot must be immutable");

        int[] widths = {128, 160, 240, 320, 480, 854, 1280};
        int[] heights = {100, 120, 160, 240, 480, 720};
        for (int width : widths) {
            for (int height : heights) {
                DIPXLayout layout = DIPXLayout.calculate(width, height);
                check(layout.panelX() >= 0 && layout.panelY() >= 0,
                        "Panel origin outside viewport " + width + "x" + height);
                check(layout.panelX() + layout.panelWidth() <= width,
                        "Panel width overflow at " + width + "x" + height);
                check(layout.panelY() + layout.panelHeight() <= height,
                        "Panel height overflow at " + width + "x" + height);
                check(layout.sidebarWidth() <= layout.panelWidth(),
                        "Sidebar exceeds panel at " + width + "x" + height);
                check(layout.contentX() + layout.contentWidth() <= layout.panelWidth(),
                        "Content exceeds panel at " + width + "x" + height);
                check(layout.cardWidth() >= 1 && layout.cardHeight() >= 1,
                        "Card has invalid size at " + width + "x" + height);
                check(layout.columns() == 1 || layout.columns() == 2,
                        "Unexpected column count");
                check(layout.perPage() >= 1 && layout.categorySlots() >= 1,
                        "Pagination must always make progress");
                int rows = (layout.perPage() + layout.columns() - 1) / layout.columns();
                int contentBottom = layout.contentTop()
                        + rows * layout.cardHeight() + (rows - 1) * layout.rowGap();
                check(contentBottom <= layout.panelHeight() - layout.footerHeight() - 8,
                        "Cards overlap footer at " + width + "x" + height);
            }
        }

        System.out.println("DIPX regression checks passed: 14 modules, idempotent registry, "
                + "enabled counter, immutable snapshot, and responsive layouts.");
    }
}
