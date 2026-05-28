package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * On startup, copies the bundled {@code ResourcePackManager-Velocity.jar} and
 * {@code ResourcePackManager-BungeeCord.jar} from this plugin's resources to
 * {@code plugins/ResourcePackManager/proxy-extension/}. Admins then copy ONE of
 * those to their proxy's plugins/ folder.
 *
 * <h2>Why we always extract (even in standalone mode)</h2>
 * The backend can't reliably auto-deploy to the proxy because the proxy may be
 * on a different host, container, or filesystem. The next best UX is "the jars
 * are always discoverable in a predictable location, regardless of how RSPM
 * detected its current topology." A standalone admin who later decides to add
 * a proxy doesn't need to flip a config flag and re-bootstrap — the jars are
 * already there waiting. Cost: ~3 MB of disk per backend, trivial.
 *
 * <h2>Boot-log behavior</h2>
 * <ul>
 *   <li><b>Network mode detected</b>: print the full setup banner including the
 *       network-key. Admin needs both to wire up the proxy.</li>
 *   <li><b>Standalone mode</b>: print one info line stating where the jars are
 *       so the path lives in the boot log for grep. No full banner — we don't
 *       want to spam standalone operators with proxy-setup instructions they
 *       don't need.</li>
 * </ul>
 *
 * <p>Re-extracts every boot — JARs are immutable per RSPM build; if RSPM is
 * updated, admins re-copy. This is the trade-off for independent extension
 * versioning (extension version evolves slowly, so re-copies are rare).</p>
 */
public final class ProxyExtensionExtractor {

    private ProxyExtensionExtractor() {}

    /**
     * @return the directory the jars were extracted to, or {@code null} if
     *         extraction failed entirely. Exposed for {@code /rspm status} so
     *         operators can find the path on-demand without scrolling the
     *         boot log.
     */
    public static File extract(JavaPlugin plugin) {
        File outDir = new File(plugin.getDataFolder(), "proxy-extension");
        if (!outDir.exists() && !outDir.mkdirs()) {
            Logger.warn("Failed to create proxy-extension directory at " + outDir.getAbsolutePath());
            return null;
        }

        File velocityOut = new File(outDir, "ResourcePackManager-Velocity.jar");
        File bungeeOut = new File(outDir, "ResourcePackManager-BungeeCord.jar");

        boolean velocityOk = extractOne(plugin, "proxy-extension/ResourcePackManager-Velocity.jar", velocityOut);
        boolean bungeeOk = extractOne(plugin, "proxy-extension/ResourcePackManager-BungeeCord.jar", bungeeOut);

        if (!velocityOk && !bungeeOk) {
            Logger.warn("Could not extract any proxy plugin jars — bundled resources missing?");
            return null;
        }

        // Always (re)write a README alongside the jars. Regenerated every boot so the
        // embedded network-key stays in sync if the admin rotates it via config.yml,
        // and so RSPM version bumps can ship updated instructions without an admin
        // having to delete a stale README first.
        writeReadme(outDir, velocityOk, bungeeOk, plugin);

        if (NetworkMode.isActive()) {
            printSetupBanner(velocityOut, bungeeOut, velocityOk, bungeeOk);
        } else {
            // Standalone: one quiet info line so the path is grep-able in the boot log
            // without spamming proxy setup steps the admin doesn't need.
            Logger.info("Proxy plugin jars available at " + outDir.getAbsolutePath()
                    + " (copy ResourcePackManager-Velocity.jar or -BungeeCord.jar to your proxy's plugins/"
                    + " folder if you ever add a proxy to this server). See README.md in that folder.");
        }
        return outDir;
    }

    /**
     * Filename suffix → resource path. Templates live under
     * {@code src/main/resources/proxy-extension/readme-<code>.md} and are
     * extracted alongside the jars with the user-friendly filename below.
     *
     * <p>Naming convention follows what the user asked for: English keeps the
     * plain {@code README.md}; other languages get
     * {@code README - <native-language-name-in-ASCII>.md}. Top 10 languages by
     * total speakers (English + 9 others) are bundled; further translations
     * can be added by dropping additional {@code readme-XX.md} files in the
     * resources dir and adding an entry to this map.</p>
     */
    private static final java.util.LinkedHashMap<String, String> README_LANGUAGES = new java.util.LinkedHashMap<>();
    static {
        // Order matters only for the boot-log "wrote N readmes" diagnostic; iteration
        // is otherwise arbitrary. Listed roughly by total-speaker count so the
        // most-likely-to-be-useful ones extract first when log truncation hits.
        README_LANGUAGES.put("en", "README.md");
        README_LANGUAGES.put("zh", "README - zhongwen.md");
        README_LANGUAGES.put("hi", "README - hindi.md");
        README_LANGUAGES.put("es", "README - espanol.md");
        README_LANGUAGES.put("fr", "README - francais.md");
        README_LANGUAGES.put("ar", "README - arabi.md");
        README_LANGUAGES.put("bn", "README - bangla.md");
        README_LANGUAGES.put("pt", "README - portugues.md");
        README_LANGUAGES.put("ru", "README - russkij.md");
        README_LANGUAGES.put("ur", "README - urdu.md");
    }

    /**
     * Extracts every bundled README template, substitutes {@code ${networkKey}}
     * and {@code ${version}} placeholders, and writes the result next to the
     * proxy jars under user-friendly filenames (see {@link #README_LANGUAGES}).
     *
     * <p>Re-written on every boot — keeps the key in sync if the admin rotates
     * it, picks up updated wording if RSPM is updated, and survives accidental
     * admin edits. Idempotent: writing the same content over an existing
     * identical file is a no-op other than the mtime.</p>
     *
     * <p>Non-fatal: a single missing template logs a warning and continues
     * with the rest. If ALL templates fail (catastrophic packaging error),
     * the jars are still extracted — the boot banner still prints — but the
     * READMEs are absent. Operators can fall back to the inline boot banner
     * for setup instructions.</p>
     */
    private static void writeReadme(File outDir, boolean velocityOk, boolean bungeeOk, JavaPlugin plugin) {
        String networkKey = NetworkMode.getNetworkKey();
        String version = plugin.getDescription().getVersion();
        int written = 0;
        int failed = 0;
        for (java.util.Map.Entry<String, String> entry : README_LANGUAGES.entrySet()) {
            String langCode = entry.getKey();
            String outFilename = entry.getValue();
            String resourcePath = "proxy-extension/readme-" + langCode + ".md";
            if (writeOneReadme(plugin, resourcePath, new File(outDir, outFilename),
                    networkKey, version)) {
                written++;
            } else {
                failed++;
            }
        }
        if (written == 0) {
            // All templates missing — packaging failure. The original inline-fallback
            // README is kept below as a safety net so the operator still gets SOMETHING.
            Logger.warn("All bundled README templates failed to extract; writing inline fallback to README.md");
            writeInlineFallbackReadme(outDir, velocityOk, bungeeOk, plugin, networkKey, version);
        } else if (failed > 0) {
            Logger.warn("Wrote " + written + " of " + README_LANGUAGES.size()
                    + " READMEs (" + failed + " template(s) missing from the jar).");
        }
    }

    /**
     * Load one bundled markdown template, substitute placeholders, write to disk.
     * Returns true on success, false on missing-resource or write-IO failure.
     */
    private static boolean writeOneReadme(JavaPlugin plugin, String resourcePath, File outFile,
                                          String networkKey, String version) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                Logger.warn("README template missing from jar: " + resourcePath);
                return false;
            }
            byte[] bytes = in.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            // Substitute ${networkKey} and ${version} placeholders. Using
            // straight String.replace (not regex) so a stray $1 in a code block
            // can't accidentally backref-substitute.
            String safeKey = networkKey == null ? "(could not resolve — see backend boot log)" : networkKey;
            body = body.replace("${networkKey}", safeKey).replace("${version}", version);
            Files.writeString(outFile.toPath(), body, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to write README " + outFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Catastrophic-failure fallback: build a minimal English README in code, so
     * operators always have SOMETHING explaining what's in this folder even if
     * the bundled templates failed to ship. Kept short — the rich templates
     * are the canonical source, this is just a survivable degradation.
     */
    private static void writeInlineFallbackReadme(File outDir, boolean velocityOk, boolean bungeeOk,
                                                  JavaPlugin plugin, String networkKey, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ResourcePackManager — Proxy Plugin (fallback README)\n");
        sb.append("\n");
        sb.append("This fallback README is generated inline by RSPM v").append(version).append(" because the\n");
        sb.append("bundled multi-language README templates failed to extract from the jar. Full\n");
        sb.append("setup instructions in 10 languages are normally available here as separate files\n");
        sb.append("(`README - espanol.md`, `README - francais.md`, etc.) — if you don't see them,\n");
        sb.append("packaging or extraction failed and the canonical source is the upstream RSPM repo.\n");
        sb.append("\n");
        sb.append("## Quick install (English fallback only)\n");
        sb.append("\n");
        sb.append("1. Copy `ResourcePackManager-Velocity.jar` (Velocity) OR `ResourcePackManager-BungeeCord.jar`\n");
        sb.append("   (BungeeCord/Waterfall) to your proxy's `plugins/` folder. Do not install both.\n");
        sb.append("2. Restart the proxy. That's it — no config to edit, no key to paste.\n");
        sb.append("\n");
        sb.append("The network identity is auto-derived from `plugins/floodgate/key.pem` on the proxy.\n");
        sb.append("Floodgate already requires that file to be the same on every backend AND on the\n");
        sb.append("proxy for Bedrock authentication to work, so the derived key matches every backend's\n");
        sb.append("automatically. If you see `Floodgate key.pem missing` on proxy boot, install Floodgate.\n");
        sb.append("\n");
        sb.append("If Bedrock sees no models on first boot, restart the proxy once more — Geyser's\n");
        sb.append("custom-item registry is set at boot only.\n");
        sb.append("\n");
        sb.append("---\n");
        sb.append("\n");
        sb.append("Files in this folder:\n");
        if (velocityOk) sb.append("- `ResourcePackManager-Velocity.jar` — for Velocity proxies\n");
        if (bungeeOk)   sb.append("- `ResourcePackManager-BungeeCord.jar` — for BungeeCord / Waterfall proxies\n");
        sb.append("- `README.md` — this fallback file\n");

        File readme = new File(outDir, "README.md");
        try {
            Files.writeString(readme.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Non-fatal: jars are still extracted, banner still prints, /rspm status still works.
            Logger.warn("Failed to write proxy-extension README at " + readme.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }

    /**
     * @deprecated kept for source-compat with callers that haven't been
     *             migrated; use {@link #extract(JavaPlugin)} directly. This
     *             method always extracts now — the {@code IfNetworkMode}
     *             gate is gone (see class javadoc).
     */
    @Deprecated
    public static void extractIfNetworkMode(JavaPlugin plugin) {
        extract(plugin);
    }

    private static boolean extractOne(JavaPlugin plugin, String resourcePath, File outFile) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                Logger.warn("Bundled resource not found: " + resourcePath);
                return false;
            }
            Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to extract " + resourcePath + " to " + outFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private static void printSetupBanner(File velocityOut, File bungeeOut, boolean velocityOk, boolean bungeeOk) {
        Logger.info("===== PROXY EXTENSION =====");
        Logger.info("Network mode detected. Proxy plugin jars extracted to:");
        if (velocityOk) Logger.info("  Velocity: " + velocityOut.getAbsolutePath());
        if (bungeeOk)   Logger.info("  Bungee:   " + bungeeOut.getAbsolutePath());
        Logger.info("");
        Logger.info("Setup (2 steps — no key paste required):");
        Logger.info("  1. Copy the right file (Velocity or Bungee) to your proxy's plugins/ folder.");
        Logger.info("  2. Restart the proxy.");
        Logger.info("");
        Logger.info("The network-key is auto-derived from plugins/floodgate/key.pem on both this");
        Logger.info("backend and the proxy — Floodgate requires that file to be the same across");
        Logger.info("the whole network anyway, so the key matches automatically. No manual paste.");
        Logger.info("");
        Logger.info("See proxy-extension/README.md (or README in your language) for verification");
        Logger.info("steps + troubleshooting.");
        Logger.info("===========================");
    }
}
