package com.magmaguy.resourcepackmanager.mixer.engine;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary #13 reproduction with hash-verified upstream archives supplied by the diagnostic run. */
public class EnchantmentOutlinesTicketTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.10.5", "1.10.8"})
    @EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TICKET_PACKS", matches = ".+")
    public void reportedEraAndCurrentPackProduceUsableMergedArchives(String version, @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path work) throws Exception {
        String input = System.getenv("NIGHTBREAK_TICKET_PACKS");
        assertNotNull(input, "Set NIGHTBREAK_TICKET_PACKS to the retained hash-verified archive directory");
        MixerLogger logger = new MixerLogger() {
            public void info(String message) { System.out.println(message); }
            public void warn(String message) { System.out.println("WARN: " + message); }
            public void collision(String message) { }
        };
        Path pack = Path.of(input, "enchantment-outlines-" + version + ".zip");
        assertTrue(Files.isRegularFile(pack), "Missing upstream pack " + pack);
        Path root = work.resolve(version);
        MixOutput output;
        try {
            output = new MixEngine(logger, () -> false).run(new MixInput(List.of(pack.toFile()),
                root.resolve("working").toFile(), root.resolve("output").toFile(),
                root.resolve("logs").toFile(), "mixed", false));
        } finally {
            // The production mixer reclaims staging directories after publication. Let that
            // owned work finish before JUnit traverses the same temporary tree for cleanup.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (hasTrash(root.resolve("output"))) {
                assertTrue(System.nanoTime() < deadline, "Mixer staging cleanup did not finish");
                Thread.sleep(20);
            }
        }
        assertTrue(output.mergedZip().isFile(), "No pack was published for " + version);
        try (ZipFile zip = new ZipFile(output.mergedZip())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("assets/") && !e.isDirectory()));
        }
        System.out.println("PASS Enchantment Outlines " + version + " -> " + output.sha1Hex());
    }
    private static boolean hasTrash(Path output) throws Exception {
        if (!Files.isDirectory(output)) return false;
        try (var children = Files.list(output)) {
            return children.anyMatch(path -> path.getFileName().toString().startsWith(".rspm_trash_"));
        }
    }
}
