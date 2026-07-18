package dev.comfyfluffy.caustica.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ensures Minecraft selects the only graphics backend Caustica supports before client bootstrap. */
public final class CausticaPreLaunch implements PreLaunchEntrypoint {
    private static final String VULKAN_OPTION = "preferredGraphicsBackend:\"vulkan\"";
    private static final Pattern BACKEND_OPTION = Pattern.compile("(?m)^preferredGraphicsBackend\\s*:.*$");

    @Override
    public void onPreLaunch() {
        forceVulkan(FabricLoader.getInstance().getGameDir().resolve("options.txt"));
    }

    static void forceVulkan(Path options) {
        try {
            String current = Files.exists(options) ? Files.readString(options, StandardCharsets.UTF_8) : "";
            Matcher matcher = BACKEND_OPTION.matcher(current);
            if (matcher.find() && matcher.group().equals(VULKAN_OPTION)) {
                return;
            }

            String updated;
            if (matcher.find(0)) {
                updated = matcher.replaceFirst(Matcher.quoteReplacement(VULKAN_OPTION));
            } else {
                String newline = current.contains("\r\n") ? "\r\n" : "\n";
                String separator = current.isEmpty() || current.endsWith("\n") ? "" : newline;
                updated = current + separator + VULKAN_OPTION + newline;
            }

            Path parent = options.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "caustica-options-", ".tmp");
            try {
                Files.writeString(temporary, updated, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, options, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, options, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Caustica could not force Minecraft's Vulkan graphics backend", e);
        }
    }
}
