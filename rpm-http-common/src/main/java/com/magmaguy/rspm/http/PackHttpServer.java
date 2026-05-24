package com.magmaguy.rspm.http;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server that serves a single resource-pack zip with the headers
 * Geyser requires (Content-Type: application/zip + accurate Content-Length).
 * Bound on 0.0.0.0:port. Callers supply the externally-reachable host name
 * when constructing the URL via {@link #urlOn(String)}.
 *
 * Geyser fetches the URL itself on the proxy JVM and serves bytes onward to
 * Bedrock clients via the Bedrock protocol — so plain HTTP works here even
 * for Bedrock. See https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/pack/url/GeyserUrlPackCodec.java
 */
public final class PackHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final File pack;
    private final String pathPrefix;

    private PackHttpServer(HttpServer server, File pack, String pathPrefix) {
        this.server = server;
        this.pack = pack;
        this.pathPrefix = pathPrefix;
    }

    /**
     * Bind and start. Pass port=0 to let the OS pick a free port (useful in tests).
     */
    public static PackHttpServer start(File packFile, int port, String pathPrefix) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext(pathPrefix, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            long length = packFile.length();
            exchange.sendResponseHeaders(200, length);
            try (var out = exchange.getResponseBody();
                 var in = new FileInputStream(packFile)) {
                in.transferTo(out);
            }
        });
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        return new PackHttpServer(s, packFile, pathPrefix);
    }

    /** External URL for clients. Caller picks the public host. */
    public String urlOn(String publicHost) {
        return "http://" + publicHost + ":" + server.getAddress().getPort() + pathPrefix;
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }
}
