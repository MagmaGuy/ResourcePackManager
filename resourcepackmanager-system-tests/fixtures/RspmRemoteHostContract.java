package com.magmaguy.resourcepackmanager.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Hermetic executable contract for the real shaded remote-host client.
 * Every request is redirected to this process's loopback server through the
 * production client's test-only scoped override.
 */
public final class RspmRemoteHostContract {
    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final Map<String, Integer> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean UPLOAD_BYTES_OBSERVED = new AtomicBoolean();

    private RspmRemoteHostContract() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: RspmRemoteHostContract <workspace> <evidence-json>");
        }
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path evidence = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.createDirectories(evidence.getParent());
        Path pack = workspace.resolve("contract-pack.zip");
        Files.writeString(pack, "rspm-remote-upload-contract", StandardCharsets.UTF_8);
        Path compliance = workspace.resolve("data-compliance.zip");

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/initialize", exchange -> {
            observe(exchange);
            sendJson(exchange, 200,
                    "{\"success\":true,\"uuid\":\"" + UUID
                            + "\",\"message\":\"fixture\"}");
        });
        server.createContext("/sha1", exchange -> {
            String body = observe(exchange);
            if (body.contains("missing-session")) {
                sendJson(exchange, 404,
                        "{\"error\":{\"code\":\"SESSION_NOT_FOUND\","
                                + "\"type\":\"not_found\",\"message\":\"expired\"}}");
                return;
            }
            boolean known = body.contains("known-sha");
            sendJson(exchange, 200,
                    "{\"success\":true,\"uploadNeeded\":" + !known + "}");
        });
        server.createContext("/upload", exchange -> {
            String body = observe(exchange);
            UPLOAD_BYTES_OBSERVED.set(
                    body.contains("rspm-remote-upload-contract"));
            sendJson(exchange, 200, "{\"success\":true}");
        });
        server.createContext("/still_alive", exchange -> {
            observe(exchange);
            sendJson(exchange, 200, "{\"success\":true}");
        });
        byte[] complianceZip = complianceZip();
        server.createContext("/data_compliance", exchange -> {
            observe(exchange);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, complianceZip.length);
            exchange.getResponseBody().write(complianceZip);
            exchange.close();
        });
        server.start();

        boolean initialized = false;
        boolean uploadNeeded = false;
        boolean knownHashMatched = false;
        boolean structuredExpiry = false;
        boolean uploaded = false;
        boolean keptAlive = false;
        boolean complianceValid = false;
        try {
            URI base = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Logger logger = Logger.getLogger(RspmRemoteHostContract.class.getName());
            logger.setLevel(Level.OFF);
            try (AutoCloseable ignored =
                         MagmaguyRspClient.useLoopbackBaseUrlForTests(base);
                 MagmaguyRspClient client =
                         new MagmaguyRspClient(logger, 5, 5)) {
                Optional<String> assigned = client.initialize(null);
                initialized = assigned.isPresent() && UUID.equals(assigned.get());
                uploadNeeded = !client.sha1Check(UUID, "new-sha").matched();
                knownHashMatched = client.sha1Check(UUID, "known-sha").matched();
                MagmaguyRspClient.Sha1Result expired =
                        client.sha1Check(UUID, "missing-session");
                structuredExpiry = !expired.matched()
                        && expired.errorOrNull() != null
                        && "SESSION_NOT_FOUND".equals(expired.errorOrNull().code())
                        && expired.errorOrNull().httpStatus() == 404;
                MagmaguyRspClient.UploadResult upload =
                        client.upload(UUID, pack.toFile());
                uploaded = upload.success()
                        && upload.urlOrNull() != null
                        && upload.urlOrNull().endsWith(UUID)
                        && UPLOAD_BYTES_OBSERVED.get();
                keptAlive = client.stillAlive(UUID);
                client.downloadDataCompliance(UUID, compliance.toFile());
                try (ZipFile zip = new ZipFile(compliance.toFile())) {
                    complianceValid = zip.getEntry("account/session.json") != null
                            && zip.size() == 1;
                }
            }
        } finally {
            server.stop(0);
        }

        boolean exactRequestCounts =
                REQUESTS.getOrDefault("/initialize", 0) == 1
                        && REQUESTS.getOrDefault("/sha1", 0) == 3
                        && REQUESTS.getOrDefault("/upload", 0) == 1
                        && REQUESTS.getOrDefault("/still_alive", 0) == 1
                        && REQUESTS.getOrDefault("/data_compliance", 0) == 1;
        boolean passed = initialized && uploadNeeded && knownHashMatched
                && structuredExpiry && uploaded && keptAlive
                && complianceValid && exactRequestCounts;
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"status\":\"" + (passed ? "PASS" : "FAIL") + "\","
                + "\"productionRemoteContacted\":false,"
                + "\"loopbackOnly\":true,"
                + "\"initializeAssignedUuid\":" + initialized + ","
                + "\"unknownHashRequestedUpload\":" + uploadNeeded + ","
                + "\"knownHashMatched\":" + knownHashMatched + ","
                + "\"structuredSessionExpiry\":" + structuredExpiry + ","
                + "\"uploadedExactBytes\":" + uploaded + ","
                + "\"keepAliveAccepted\":" + keptAlive + ","
                + "\"dataComplianceZipValidated\":" + complianceValid + ","
                + "\"exactRequestCounts\":" + exactRequestCounts
                + "}";
        Files.writeString(evidence, json, StandardCharsets.UTF_8);
        if (!passed) {
            throw new IllegalStateException(
                    "Remote host loopback contract failed: " + json);
        }
        System.out.println("PASS remote-host-stub: " + json);
    }

    private static String observe(HttpExchange exchange) throws java.io.IOException {
        REQUESTS.merge(exchange.getRequestURI().getPath(), 1, Integer::sum);
        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.ISO_8859_1);
    }

    private static void sendJson(
            HttpExchange exchange,
            int status,
            String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] complianceZip() throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("account/session.json");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
