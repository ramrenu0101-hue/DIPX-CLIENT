package com.dipx.client.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/**
 * Persists module enable states in the Fabric config directory.
 * Unknown keys and malformed values are ignored so removed modules cannot
 * become active accidentally after an upgrade.
 */
public final class ModuleConfigStore {
    public static final String FILE_NAME = "dipx-modules.properties";
    private static final String PREFIX = "module.";

    private ModuleConfigStore() {}

    public static void load(Path configDirectory, List<DIPXModule> modules) throws IOException {
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IllegalArgumentException malformedFile) {
            throw new IOException("Malformed DIPX module configuration", malformedFile);
        }

        for (DIPXModule module : modules) {
            String value = properties.getProperty(PREFIX + module.name());
            if ("true".equalsIgnoreCase(value)) {
                module.setEnabled(true);
            } else if ("false".equalsIgnoreCase(value)) {
                module.setEnabled(false);
            }
        }
    }

    public static void save(Path configDirectory, List<DIPXModule> modules) throws IOException {
        Files.createDirectories(configDirectory);
        Properties properties = new Properties();
        for (DIPXModule module : modules) {
            properties.setProperty(PREFIX + module.name(), Boolean.toString(module.enabled()));
        }

        Path target = configDirectory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(configDirectory, ".dipx-modules-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "DIPX module settings");
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
