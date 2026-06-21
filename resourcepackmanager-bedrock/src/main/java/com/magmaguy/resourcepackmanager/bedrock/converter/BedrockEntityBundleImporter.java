package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class BedrockEntityBundleImporter {
    public static final String BUNDLE_ROOT = "rspm_bedrock_pack";

    private static final Set<String> ALLOWED_TOP_LEVEL = Set.of(
            "entity",
            "models",
            "animations",
            "animation_controllers",
            "render_controllers",
            "materials",
            "textures"
    );

    private BedrockEntityBundleImporter() {
    }

    /**
     * Copies first-party Bedrock entity bundle files from
     * {@code assets/<namespace>/rspm_bedrock_pack/**} into the generated Bedrock
     * pack. The importer intentionally allows only entity-related Bedrock
     * directories so producer plugins cannot accidentally shadow unrelated pack
     * files such as manifest.json or item_texture.json.
     *
     * @return number of files copied
     */
    public static int importBundles(File mergedJavaPack, File bedrockPackDir) {
        File assetsDir = new File(mergedJavaPack, "assets");
        if (!assetsDir.isDirectory()) {
            return 0;
        }

        File[] namespaces = assetsDir.listFiles(File::isDirectory);
        if (namespaces == null || namespaces.length == 0) {
            return 0;
        }

        AtomicInteger copied = new AtomicInteger();
        for (File namespace : namespaces) {
            Path bundleRoot = namespace.toPath().resolve(BUNDLE_ROOT);
            if (!Files.isDirectory(bundleRoot)) {
                continue;
            }
            copied.addAndGet(copyBundleRoot(bundleRoot, bedrockPackDir.toPath()));
        }
        if (copied.get() > 0) {
            BedrockLog.debug("[BedrockConverter] Imported " + copied.get() + " Bedrock custom entity bundle files.");
        }
        return copied.get();
    }

    private static int copyBundleRoot(Path bundleRoot, Path bedrockPackRoot) {
        AtomicInteger copied = new AtomicInteger();
        try (Stream<Path> files = Files.walk(bundleRoot)) {
            files.filter(Files::isRegularFile).forEach(source -> {
                Path relative = bundleRoot.relativize(source).normalize();
                if (!isAllowed(relative)) {
                    BedrockLog.warn("[BedrockConverter] Skipping unsupported Bedrock entity bundle path "
                            + relative + " under " + bundleRoot);
                    return;
                }

                Path destination = bedrockPackRoot.resolve(relative).normalize();
                if (!destination.startsWith(bedrockPackRoot.normalize())) {
                    BedrockLog.warn("[BedrockConverter] Skipping unsafe Bedrock entity bundle path " + relative);
                    return;
                }

                try {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied.incrementAndGet();
                } catch (IOException exception) {
                    BedrockLog.warn("[BedrockConverter] Failed to copy Bedrock entity bundle file "
                            + source + ": " + exception.getMessage());
                }
            });
        } catch (IOException exception) {
            BedrockLog.warn("[BedrockConverter] Failed to scan Bedrock entity bundle root "
                    + bundleRoot + ": " + exception.getMessage());
        }
        return copied.get();
    }

    private static boolean isAllowed(Path relative) {
        if (relative.getNameCount() < 2) {
            return false;
        }
        String first = relative.getName(0).toString().replace('\\', '/');
        if (!ALLOWED_TOP_LEVEL.contains(first)) {
            return false;
        }
        if ("models".equals(first)) {
            return relative.getNameCount() >= 3 && "entity".equals(relative.getName(1).toString());
        }
        if ("textures".equals(first)) {
            return relative.getNameCount() >= 3 && "entity".equals(relative.getName(1).toString());
        }
        return true;
    }
}
