package com.magmaguy.resourcepackmanager.mixer.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporary regression coverage for custom mixer-folder packs (public ticket #17). */
final class MixerFolderTicketTest {
    @Test
    void customMixerPackIsPresentInPublishedOutput(@TempDir Path root) throws Exception {
        Path input = root.resolve("custom-pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            entry(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":75,\"description\":\"ticket\"}}");
            entry(zip, "assets/ticket/custom.txt", "custom mixer asset");
        }
        MixerLogger logger = new MixerLogger() {
            public void info(String message) { }
            public void warn(String message) { }
            public void collision(String message) { }
        };
        MixOutput output = new MixEngine(logger, () -> false).run(new MixInput(
                List.of(input.toFile()), root.resolve("working").toFile(),
                root.resolve("output").toFile(), root.resolve("logs").toFile(), "mixed", false));

        assertTrue(output.mergedZip().isFile(), "the mixer must publish an output archive");
        try (ZipFile zip = new ZipFile(output.mergedZip())) {
            assertNotNull(zip.getEntry("assets/ticket/custom.txt"),
                    "custom mixer-folder assets must survive into the merged archive");
        }
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
