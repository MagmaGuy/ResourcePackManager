package com.magmaguy.resourcepackmanager.http;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Minimal HTTP server that serves:
 * <ul>
 *     <li>The pack zip itself on {@code <pathPrefix>}, with the headers Geyser needs
 *         (Content-Type: application/zip + accurate Content-Length). If the pack file
 *         is absent, this route 404s — relevant when self-hosting hasn't produced a
 *         pack yet on a fresh backend.</li>
 *     <li>{@code /.rspm-pack-info.json}, always-on metadata describing this backend's
 *         current pack (uuid / url / sha1 / network-key). Used by the proxy plugin's
 *         {@code BackendMetadataPoller} to discover backend pack URLs. Returns 200
 *         with JSON even when no pack is uploaded yet (null fields) so polling never
 *         fails — the proxy gracefully skips backends whose url is null.</li>
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

    public static final String METADATA_PATH = "/.rspm-pack-info.json";

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
     * No metadata endpoint registered — equivalent to passing {@code null} to
     * {@link #start(File, int, String, Supplier)}.
     */
    public static PackHttpServer start(File packFile, int port, String pathPrefix) throws IOException {
        return start(packFile, port, pathPrefix, null);
    }

    /**
     * Bind and start with optional metadata endpoint.
     *
     * @param packFile         the file to serve at {@code pathPrefix}. The route reads
     *                         the file fresh on each request, so the URL stays stable
     *                         across re-mixes. May be null/absent at start time — the
     *                         route 404s until the file appears.
     * @param port             bind port; 0 = OS-picked.
     * @param pathPrefix       request path for the pack zip (e.g. {@code /rspm.zip}).
     * @param metadataSupplier optional callback producing the JSON body for the
     *                         {@link #METADATA_PATH} route. Called on every request so
     *                         the response stays current as the backend's pack state
     *                         changes. Pass {@code null} to disable the route.
     */
    public static PackHttpServer start(File packFile,
                                       int port,
                                       String pathPrefix,
                                       Supplier<String> metadataSupplier) throws IOException {
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
        if (metadataSupplier != null) {
            s.createContext(METADATA_PATH, exchange -> {
                String body = metadataSupplier.get();
                if (body == null) body = "{}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }
        ExecutorService exec = Executors.newCachedThreadPool();
        s.setExecutor(exec);
        s.start();
        return new PackHttpServer(s, pathPrefix, exec);
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
