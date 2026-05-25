package com.magmaguy.resourcepackmanager.http;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal HTTP server that serves:
 * <ul>
 *     <li>The pack zip itself on {@code <pathPrefix>}, with the headers Geyser needs
 *         (Content-Type: application/zip + accurate Content-Length). If the pack file
 *         is absent, this route 404s — relevant when self-hosting hasn't produced a
 *         pack yet on a fresh backend.</li>
 *     <li>Additional file-serving routes registered via
 *         {@link #registerFileRoute(String, File, String)} after start — used by the
 *         backend to expose its Bedrock-conversion outputs ({@code /bedrock.zip},
 *         {@code /mappings.json}) for the proxy to pull. These routes read the file
 *         fresh on every request, support {@code If-Modified-Since} so unchanged
 *         files return 304, and 404 cleanly when the file is absent.</li>
 * </ul>
 *
 * Bound on 0.0.0.0:port. Callers supply the externally-reachable host name
 * when constructing the URL via {@link #urlOn(String)}.
 *
 * Geyser fetches the URL itself on the proxy JVM and serves bytes onward to
 * Bedrock clients via the Bedrock protocol — so plain HTTP works here even
 * for Bedrock. See https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/pack/url/GeyserUrlPackCodec.java
 */
public final class PackHttpServer implements AutoCloseable {

    public static final String BEDROCK_PACK_PATH = "/bedrock.zip";
    public static final String GEYSER_MAPPINGS_PATH = "/mappings.json";

    /**
     * RFC 1123 / HTTP-date formatter, fixed to GMT as required by RFC 7231.
     * Used for both {@code Last-Modified} responses and parsing
     * {@code If-Modified-Since} requests.
     */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private final HttpServer server;
    private final String pathPrefix;
    private final ExecutorService executor;

    private PackHttpServer(HttpServer server, String pathPrefix, ExecutorService executor) {
        this.server = server;
        this.pathPrefix = pathPrefix;
        this.executor = executor;
    }

    /**
     * Bind and start. Pass port=0 to let the OS pick a free port (useful in tests).
     *
     * @param packFile   the file to serve at {@code pathPrefix}. The route reads
     *                   the file fresh on each request, so the URL stays stable
     *                   across re-mixes. May be null/absent at start time — the
     *                   route 404s until the file appears.
     * @param port       bind port; 0 = OS-picked.
     * @param pathPrefix request path for the pack zip (e.g. {@code /rspm.zip}).
     */
    public static PackHttpServer start(File packFile,
                                       int port,
                                       String pathPrefix) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext(pathPrefix, exchange -> {
            if (packFile == null || !packFile.exists()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            long length = packFile.length();
            exchange.sendResponseHeaders(200, length);
            try (var out = exchange.getResponseBody();
                 var in = new FileInputStream(packFile)) {
                in.transferTo(out);
            }
        });
        ExecutorService exec = Executors.newCachedThreadPool();
        s.setExecutor(exec);
        s.start();
        return new PackHttpServer(s, pathPrefix, exec);
    }

    /**
     * Register an additional route that serves a single file from disk. Reads the
     * file fresh on every request (no in-memory cache) so subsequent regenerations
     * are picked up immediately. Supports {@code If-Modified-Since} — if the file's
     * {@link File#lastModified()} is &le; the header's instant (truncated to
     * whole seconds, matching HTTP-date precision), the route returns 304 with no
     * body so downstream pollers can skip unchanged downloads.
     *
     * <p>If the file does not exist when the request arrives, the route returns
     * 404 with no body. This is intentional: it lets callers wire the route up
     * before the producing step (e.g. BedrockConversion) has run, with the route
     * starting to 200 the moment the file lands.</p>
     *
     * @param path        the request path (e.g. {@link #BEDROCK_PACK_PATH}).
     *                    Must start with {@code /}.
     * @param file        the file to serve. Read per-request; may be missing.
     * @param contentType the value of the {@code Content-Type} response header
     *                    on 200 responses.
     */
    public void registerFileRoute(String path, File file, String contentType) {
        server.createContext(path, exchange -> {
            try {
                if (file == null || !file.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                long lastModifiedMs = file.lastModified();
                // HTTP-date has whole-second precision, so truncate before
                // comparing — otherwise a file written within the same second
                // as the previous response would always look "newer".
                long lastModifiedSec = lastModifiedMs / 1000L;

                String ifModifiedSince = exchange.getRequestHeaders().getFirst("If-Modified-Since");
                if (ifModifiedSince != null) {
                    Long sinceSec = parseHttpDateSeconds(ifModifiedSince);
                    if (sinceSec != null && lastModifiedSec <= sinceSec) {
                        // 304 must not have a body; sendResponseHeaders(-1)
                        // signals "no Content-Length, no body" to JDK HttpServer.
                        exchange.sendResponseHeaders(304, -1);
                        return;
                    }
                }

                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.getResponseHeaders().add("Last-Modified",
                        HTTP_DATE.format(Instant.ofEpochSecond(lastModifiedSec)));
                long length = file.length();
                exchange.sendResponseHeaders(200, length);
                try (var out = exchange.getResponseBody();
                     var in = new FileInputStream(file)) {
                    in.transferTo(out);
                }
            } finally {
                exchange.close();
            }
        });
    }

    /**
     * Parse an HTTP-date header to whole-second epoch. Returns null if the value
     * is malformed (per RFC 7232 we MUST ignore unparseable If-Modified-Since
     * values, treating the request as if the header weren't sent).
     */
    private static Long parseHttpDateSeconds(String httpDate) {
        try {
            ZonedDateTime parsed = ZonedDateTime.parse(httpDate.trim(), HTTP_DATE);
            return parsed.toEpochSecond();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** External URL for clients. Caller picks the public host. */
    public String urlOn(String publicHost) {
        return "http://" + publicHost + ":" + server.getAddress().getPort() + pathPrefix;
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() {
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
