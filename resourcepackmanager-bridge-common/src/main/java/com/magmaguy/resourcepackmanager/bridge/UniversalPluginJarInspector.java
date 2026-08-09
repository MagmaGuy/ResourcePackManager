package com.magmaguy.resourcepackmanager.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates the single ResourcePackManager artifact shared by Bukkit, Velocity,
 * BungeeCord, and Geyser before it is installed or offered to another process.
 *
 * <p>This is deliberately independent of every platform API. Update transports
 * can therefore use one validation policy before publishing executable bytes,
 * while {@link UniversalPluginJarInstaller} uses the same policy before writing
 * Geyser's extension directory.</p>
 */
public final class UniversalPluginJarInspector {
    public static final long MAX_JAR_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L;
    public static final int MAX_ENTRY_COUNT = 100_000;
    public static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    public static final List<String> DESCRIPTOR_ENTRIES = List.of(
            "plugin.yml",
            "velocity-plugin.json",
            "bungee.yml",
            "extension.yml"
    );

    public static final List<String> ENTRYPOINT_ENTRIES = List.of(
            "com/magmaguy/resourcepackmanager/ResourcePackManager.class",
            "com/magmaguy/resourcepackmanager/bungee/RspmBungeePlugin.class",
            "com/magmaguy/resourcepackmanager/velocity/RspmVelocityPlugin.class",
            "com/magmaguy/resourcepackmanager/geyserbridge/RspmGeyserBridgeExtension.class"
    );

    private static final Pattern YAML_VERSION = Pattern.compile(
            "(?m)^version[ \\t]*:[ \\t]*([^#\\r\\n]+?)[ \\t]*(?:#.*)?$");
    private static final Pattern VERSION = Pattern.compile(
            "[0-9]+(?:\\.[0-9]+)*(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");

    private UniversalPluginJarInspector() {
    }

    public record Inspection(Path jar,
                             String version,
                             String sha256,
                             long sizeBytes,
                             long uncompressedBytes,
                             int entryCount) {
    }

    public static Inspection inspect(Path sourceJar) throws IOException {
        if (sourceJar == null) {
            throw new IOException("RSPM JAR path is missing");
        }
        Path jar = sourceJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("RSPM code source is not a regular JAR: " + jar);
        }

        long size = Files.size(jar);
        if (size <= 0L || size > MAX_JAR_BYTES) {
            throw new IOException("RSPM JAR size is outside the allowed range: " + size);
        }

        Map<String, ZipEntry> entries = new HashMap<>();
        Set<String> names = new HashSet<>();
        long totalUncompressed = 0L;
        int entryCount = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("RSPM JAR contains too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("RSPM JAR contains duplicate entry: " + entry.getName());
                }
                long entrySize = entry.getSize();
                if (entrySize < 0L) {
                    throw new IOException("RSPM JAR entry has unknown size: " + entry.getName());
                }
                if (entrySize > MAX_ENTRY_BYTES) {
                    throw new IOException("RSPM JAR entry is too large: " + entry.getName());
                }
                if (Long.MAX_VALUE - totalUncompressed < entrySize) {
                    throw new IOException("RSPM JAR uncompressed size overflow");
                }
                totalUncompressed += entrySize;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new IOException("RSPM JAR expands beyond the allowed size");
                }
                entries.put(entry.getName(), entry);
            }

            for (String required : DESCRIPTOR_ENTRIES) {
                requireFileEntry(entries, required);
            }
            for (String required : ENTRYPOINT_ENTRIES) {
                requireFileEntry(entries, required);
            }

            Map<String, String> versions = new java.util.LinkedHashMap<>();
            versions.put("plugin.yml", readYamlVersion(zip, entries.get("plugin.yml"), "plugin.yml"));
            versions.put("velocity-plugin.json",
                    readVelocityVersion(zip, entries.get("velocity-plugin.json")));
            versions.put("bungee.yml", readYamlVersion(zip, entries.get("bungee.yml"), "bungee.yml"));
            versions.put("extension.yml",
                    readYamlVersion(zip, entries.get("extension.yml"), "extension.yml"));

            Set<String> distinctVersions = new HashSet<>(versions.values());
            if (distinctVersions.size() != 1) {
                throw new IOException("RSPM platform descriptor versions do not match: " + versions);
            }
            String version = versions.values().iterator().next();
            validateVersion(version);
            return new Inspection(jar, version, sha256(jar), size, totalUncompressed, entryCount);
        } catch (RuntimeException exception) {
            throw new IOException("Could not inspect RSPM JAR " + jar + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Compares validated semantic-ish plugin versions. Numeric components are
     * compared numerically and a stable release sorts after its prereleases.
     */
    public static int compareVersions(String left, String right) {
        validateVersionArgument(left);
        validateVersionArgument(right);

        VersionParts leftParts = VersionParts.parse(left);
        VersionParts rightParts = VersionParts.parse(right);
        int length = Math.max(leftParts.numbers().size(), rightParts.numbers().size());
        for (int i = 0; i < length; i++) {
            long a = i < leftParts.numbers().size() ? leftParts.numbers().get(i) : 0L;
            long b = i < rightParts.numbers().size() ? rightParts.numbers().get(i) : 0L;
            int compared = Long.compare(a, b);
            if (compared != 0) return compared;
        }

        if (leftParts.preRelease().isEmpty() && rightParts.preRelease().isEmpty()) return 0;
        if (leftParts.preRelease().isEmpty()) return 1;
        if (rightParts.preRelease().isEmpty()) return -1;
        return comparePreRelease(leftParts.preRelease(), rightParts.preRelease());
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireFileEntry(Map<String, ZipEntry> entries, String name) throws IOException {
        ZipEntry entry = entries.get(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("RSPM JAR is not universal; missing " + name);
        }
    }

    private static String readYamlVersion(ZipFile zip, ZipEntry entry, String descriptor)
            throws IOException {
        String text = readDescriptor(zip, entry, descriptor);
        Matcher matcher = YAML_VERSION.matcher(text);
        if (!matcher.find()) {
            throw new IOException(descriptor + " does not declare a version");
        }
        String version = unquote(matcher.group(1).trim());
        if (matcher.find()) {
            throw new IOException(descriptor + " declares version more than once");
        }
        validateVersion(version);
        return version;
    }

    private static String readVelocityVersion(ZipFile zip, ZipEntry entry) throws IOException {
        String text = readDescriptor(zip, entry, "velocity-plugin.json");
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IOException("velocity-plugin.json is not a JSON object");
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement versionElement = object.get("version");
            if (versionElement == null || !versionElement.isJsonPrimitive()
                    || !versionElement.getAsJsonPrimitive().isString()) {
                throw new IOException("velocity-plugin.json does not declare a string version");
            }
            String version = versionElement.getAsString().trim();
            validateVersion(version);
            return version;
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new IOException("velocity-plugin.json is invalid: " + exception.getMessage(), exception);
        }
    }

    private static String readDescriptor(ZipFile zip, ZipEntry entry, String descriptor)
            throws IOException {
        if (entry.getSize() > MAX_DESCRIPTOR_BYTES) {
            throw new IOException(descriptor + " is too large");
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException(descriptor + " is too large");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static void validateVersion(String version) throws IOException {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IOException("invalid RSPM descriptor version: " + version);
        }
        try {
            VersionParts.parse(version);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid RSPM descriptor version: " + version, exception);
        }
    }

    private static void validateVersionArgument(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid version: " + version);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static int comparePreRelease(List<String> left, List<String> right) {
        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            if (i >= left.size()) return -1;
            if (i >= right.size()) return 1;
            String a = left.get(i);
            String b = right.get(i);
            boolean aNumeric = a.chars().allMatch(Character::isDigit);
            boolean bNumeric = b.chars().allMatch(Character::isDigit);
            int compared;
            if (aNumeric && bNumeric) {
                compared = compareNumericIdentifier(a, b);
            } else if (aNumeric != bNumeric) {
                compared = aNumeric ? -1 : 1;
            } else {
                compared = a.compareTo(b);
            }
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int compareNumericIdentifier(String left, String right) {
        String normalizedLeft = left.replaceFirst("^0+(?!$)", "");
        String normalizedRight = right.replaceFirst("^0+(?!$)", "");
        int lengthComparison = Integer.compare(normalizedLeft.length(), normalizedRight.length());
        return lengthComparison != 0 ? lengthComparison : normalizedLeft.compareTo(normalizedRight);
    }

    private record VersionParts(List<Long> numbers, List<String> preRelease) {
        private static VersionParts parse(String version) {
            String withoutBuild = version.split("\\+", 2)[0];
            String[] mainAndPre = withoutBuild.split("-", 2);
            List<Long> numbers = new ArrayList<>();
            for (String part : mainAndPre[0].split("\\.")) {
                try {
                    numbers.add(Long.parseLong(part));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("version component is too large: " + version, exception);
                }
            }
            List<String> preRelease = mainAndPre.length == 1
                    ? List.of()
                    : List.of(mainAndPre[1].split("\\."));
            return new VersionParts(numbers, preRelease);
        }
    }
}
