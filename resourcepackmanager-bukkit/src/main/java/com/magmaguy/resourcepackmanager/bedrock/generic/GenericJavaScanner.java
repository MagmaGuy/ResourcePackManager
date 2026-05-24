package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans a merged Java resource pack directory for 1.21.4+ items definition files
 * across every namespace except {@code minecraft} (vanilla — handled by Bedrock) and
 * {@code freeminecraftmodels} (handled by the FMM-specific scanner that walks
 * {@code assets/freeminecraftmodels/models/} instead).
 *
 * <p>This is the entry point of the generic Java→Bedrock pipeline. Subsequent phases
 * (model walker, base-item resolver, geometry/attachable emission) consume the
 * {@link ItemsDefinition} list produced here.
 *
 * <p>Phase 1 scope: discovery only. Files are parsed into {@link ItemsDefinition}
 * records but no conversion is performed.
 */
public final class GenericJavaScanner {

    private static final Set<String> SKIP_NAMESPACES = Set.of("minecraft", "freeminecraftmodels");

    private GenericJavaScanner() {}

    /**
     * Walks the merged pack's {@code assets/} tree and returns every parseable items
     * definition file (1.21.4+ format) outside the skipped namespaces.
     */
    public static List<ItemsDefinition> scan(File mergedJavaPack) {
        List<ItemsDefinition> result = new ArrayList<>();
        File assetsDir = new File(mergedJavaPack, "assets");
        if (!assetsDir.isDirectory()) return result;

        File[] namespaceDirs = assetsDir.listFiles(File::isDirectory);
        if (namespaceDirs == null) return result;

        for (File nsDir : namespaceDirs) {
            String namespace = nsDir.getName();
            if (SKIP_NAMESPACES.contains(namespace)) continue;
            File itemsDir = new File(nsDir, "items");
            if (!itemsDir.isDirectory()) continue;
            scanItemsDir(namespace, itemsDir, "", result);
        }

        Logger.info("[BedrockConverter] Generic scanner: discovered " + result.size()
                + " items definition files across " + namespaceDirs.length + " namespace(s).");
        return result;
    }

    private static void scanItemsDir(String namespace, File dir, String relPath, List<ItemsDefinition> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                String childRel = relPath.isEmpty() ? entry.getName() : relPath + "/" + entry.getName();
                scanItemsDir(namespace, entry, childRel, out);
            } else if (entry.isFile() && entry.getName().endsWith(".json")) {
                String stem = entry.getName().substring(0, entry.getName().length() - ".json".length());
                String fullRel = relPath.isEmpty() ? stem : relPath + "/" + stem;
                try (FileReader reader = new FileReader(entry, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    out.add(new ItemsDefinition(namespace, fullRel, entry, root));
                } catch (Exception e) {
                    Logger.warn("[BedrockConverter] Failed to parse items definition "
                            + entry.getPath() + ": " + e.getMessage());
                }
            }
        }
    }
}
