package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericJavaScannerJavaOnlyTest {
    @TempDir
    Path directory;

    @AfterEach
    void resetLog() {
        BedrockLog.set(null);
    }

    private void item(String namespace, String path) throws Exception {
        Path file = directory.resolve("assets/" + namespace + "/items/" + path + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"" + namespace + ":" + path + "\"}}");
    }

    private void javaOnly(String namespace, String path) throws Exception {
        Path file = directory.resolve("assets/" + namespace + "/rspm_item_java_only/" + path + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
    }

    private List<String> scannedIds() throws Exception {
        return GenericJavaScanner.scan(directory.toFile()).stream().map(ItemsDefinition::itemIdentifier).toList();
    }

    @Test
    void aDeclarationLeavesOutEveryDefinitionBeneathIt() throws Exception {
        item("freeminecraftmodels", "display/wand");
        item("freeminecraftmodels", "particle/wand/spark/0");
        item("freeminecraftmodels", "particle/wand/spark/1");
        javaOnly("freeminecraftmodels", "particle/wand/spark");

        assertEquals(List.of("freeminecraftmodels:display/wand"), scannedIds(),
                "Particle sprites only Java item displays use must not become Geyser custom items");
    }

    @Test
    void aDeclarationLeavesOutTheDefinitionAtItsOwnPath() throws Exception {
        item("acme", "gui/blank");
        item("acme", "gui/button");
        javaOnly("acme", "gui/blank");

        assertEquals(List.of("acme:gui/button"), scannedIds());
    }

    @Test
    void aDeclarationOnlyCoversWholePathSegments() throws Exception {
        item("freeminecraftmodels", "particle/wand/sparkle/0");
        javaOnly("freeminecraftmodels", "particle/wand/spark");

        assertEquals(List.of("freeminecraftmodels:particle/wand/sparkle/0"), scannedIds());
    }

    @Test
    void aDeclarationOnlyCoversItsOwnNamespace() throws Exception {
        item("acme", "particle/wand/spark/0");
        javaOnly("freeminecraftmodels", "particle/wand/spark");

        assertEquals(List.of("acme:particle/wand/spark/0"), scannedIds());
    }

    @Test
    void aPackWhoseItemsAreAllJavaOnlyIsNotReportedAsLegacy() throws Exception {
        item("freeminecraftmodels", "particle/wand/spark/0");
        Path model = directory.resolve("assets/freeminecraftmodels/models/particle/wand/spark/0.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{}");
        javaOnly("freeminecraftmodels", "particle/wand/spark");
        List<String> warnings = new ArrayList<>();
        BedrockLog.set(new MixerLogger() {
            @Override public void info(String message) {}
            @Override public void warn(String message) { warnings.add(message); }
            @Override public void collision(String message) {}
        });

        assertEquals(List.of(), scannedIds());
        assertEquals(List.of(), warnings,
                "The producer declared these items Java-only, so the pack is not in an unsupported format");
    }
}
