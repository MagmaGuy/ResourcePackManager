import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Lightweight process fixture for the explicit real-proxy boot suite. */
public final class RspmBackendFixtureServer {
    private static final String UPDATE_ROUTE = "/rspm-update.jar";
    private static final String AUTHORITY_ROUTE =
            "/server/plugins/resourcepackmanager/version";
    private static final String UPDATE_TOKEN_DOMAIN =
            "resourcepackmanager.network-executable-update.v1";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: RspmBackendFixtureServer <port> <fixture-directory> "
                    + "[--update-jar <jar> --key-pem <key>] [--authority-jar <jar>]");
        }
        int port = Integer.parseInt(args[0]);
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Path updateJar = null;
        Path keyPem = null;
        Path authorityJar = null;
        for (int index = 2; index < args.length; index += 2) {
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[index]);
            }
            Path value = Path.of(args[index + 1]).toAbsolutePath().normalize();
            switch (args[index]) {
                case "--update-jar" -> updateJar = value;
                case "--key-pem" -> keyPem = value;
                case "--authority-jar" -> authorityJar = value;
                default -> throw new IllegalArgumentException("unknown option " + args[index]);
            }
        }
        if ((updateJar == null) != (keyPem == null)) {
            throw new IllegalArgumentException("--update-jar and --key-pem must be supplied together");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        register(server, "/bedrock.zip", root.resolve("bedrock.zip"), "application/zip");
        register(server, "/mappings.json", root.resolve("mappings.json"), "application/json");
        if (updateJar != null) {
            String expectedAuthorization = updateAuthorization(keyPem);
            Path executable = updateJar;
            server.createContext(UPDATE_ROUTE,
                    exchange -> serveProtectedUpdate(exchange, executable, expectedAuthorization));
        }
        if (authorityJar != null) {
            Path release = authorityJar;
            server.createContext(AUTHORITY_ROUTE,
                    exchange -> serveAuthority(exchange, release));
        }
        server.start();
        System.out.println("RSPM_BACKEND_FIXTURE_READY port=" + port);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                String line = input.readLine();
                if (line == null || line.trim().equalsIgnoreCase("stop")) break;
            }
        } finally {
            server.stop(0);
        }
    }

    private static void serveProtectedUpdate(
            HttpExchange exchange, Path jar, String expectedAuthorization) {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(UPDATE_ROUTE)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String provided = exchange.getRequestHeaders().getFirst("Authorization");
            if (!constantTimeEquals(expectedAuthorization, provided)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            serveOpenFile(exchange, jar, "application/java-archive", method);
        } catch (Exception exception) {
            sendFailure(exchange);
        }
    }

    private static void serveAuthority(HttpExchange exchange, Path jar) {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(AUTHORITY_ROUTE)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = "{\"version\":\"" + pluginVersion(jar)
                    + "\",\"checksum\":\"" + sha256(jar)
                    + "\",\"fileSize\":" + Files.size(jar)
                    + ",\"fileName\":\"ResourcePackManager.jar\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception exception) {
            sendFailure(exchange);
        }
    }

    private static void register(HttpServer server, String route, Path file, String contentType) {
        server.createContext(route, exchange -> serve(exchange, route, file, contentType));
    }

    private static void serve(HttpExchange exchange, String route, Path file, String contentType) {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(route)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            serveOpenFile(exchange, file, contentType, method);
        } catch (Exception exception) {
            sendFailure(exchange);
        }
    }

    private static void serveOpenFile(
            HttpExchange exchange, Path file, String contentType, String method) throws Exception {
        if (!Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Last-Modified", Files.getLastModifiedTime(file).toString());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(bytes.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String updateAuthorization(Path keyPem) throws Exception {
        byte[] keyHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(keyPem));
        long most = 0L;
        long least = 0L;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (keyHash[index] & 0xffL);
        }
        for (int index = 8; index < 16; index++) {
            least = (least << 8) | (keyHash[index] & 0xffL);
        }
        String networkKey = new java.util.UUID(most, least).toString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(networkKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] token = mac.doFinal(UPDATE_TOKEN_DOMAIN.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String pluginVersion(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry descriptor = zip.getEntry("plugin.yml");
            if (descriptor == null) throw new IllegalStateException("plugin.yml is missing");
            String text = new String(zip.getInputStream(descriptor).readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("version:")) {
                    String version = trimmed.substring("version:".length()).trim();
                    if ((version.startsWith("\"") && version.endsWith("\""))
                            || (version.startsWith("'") && version.endsWith("'"))) {
                        version = version.substring(1, version.length() - 1);
                    }
                    if (!version.isBlank()) return version;
                }
            }
        }
        throw new IllegalStateException("plugin.yml has no version");
    }

    private static String sha256(Path file) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void sendFailure(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(500, -1);
        } catch (Exception ignored) {
        }
    }
}
