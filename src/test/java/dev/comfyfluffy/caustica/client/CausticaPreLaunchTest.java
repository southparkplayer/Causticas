package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaPreLaunchTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesOnlyThePersistedBackendAndPreservesLineEndings() throws Exception {
        Path options = temporaryDirectory.resolve("options.txt");
        Files.writeString(options, "graphicsPreset:\"fancy\"\r\n"
                + "preferredGraphicsBackend:\"opengl\"\r\n"
                + "renderDistance:32\r\n");

        CausticaPreLaunch.forceVulkan(options);

        assertEquals("graphicsPreset:\"fancy\"\r\n"
                + "preferredGraphicsBackend:\"vulkan\"\r\n"
                + "renderDistance:32\r\n", Files.readString(options));
    }

    @Test
    void createsThePreferenceWhenOptionsDoNotExist() throws Exception {
        Path options = temporaryDirectory.resolve("new-game").resolve("options.txt");

        CausticaPreLaunch.forceVulkan(options);

        assertEquals("preferredGraphicsBackend:\"vulkan\"\n", Files.readString(options));
    }

    @Test
    void gradleAndPackagedLaunchesBothOwnTheBackendSelection() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));

        assertTrue(build.contains("programArg \"--graphicsBackend\""));
        assertTrue(build.contains("programArg \"VULKAN\""));
        assertTrue(metadata.contains("dev.comfyfluffy.caustica.client.CausticaPreLaunch"));
    }
}
