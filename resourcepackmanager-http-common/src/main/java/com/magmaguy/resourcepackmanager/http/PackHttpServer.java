package com.magmaguy.resourcepackmanager.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    public static final String EXECUTABLE_UPDATE_PATH = "/rspm-update.jar";
    public static final String EXECUTABLE_UPDATE_TOKEN_DOMAIN =
            "resourcepackmanager.network-executable-update.v1";

    /**
     * RFC 1123 / HTTP-date formatter, fixed to GMT as required by RFC 7231.
     * Used for both {@code Last-Modified} responses and parsing
     * {@code If-Modified-Since} requests.
     */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final int HTTP_WORKERS = 8;
    private static final int HTTP_QUEUE_CAPACITY = 32;
    private static final int FILE_BUFFER_SIZE = 64 * 1024;
    private static final AtomicInteger HTTP_THREAD_INDEX = new AtomicInteger();

    /**
     * A per-request file descriptor. A producer may supply the SHA-1 it already
     * calculated while publishing the file; routes without one derive it from
     * the same pinned file channel used for the response body.
     */
    public record FileRouteDescriptor(File file, String sha1Hex) {
        public FileRouteDescriptor {
            Objects.requireNonNull(file, "file");
        }
    }

    private final HttpServer server;
    private final String pathPrefix;
    private final ExecutorService executor;
    private final Set<String> registeredPaths = ConcurrentHashMap.newKeySet();

    private PackHttpServer(HttpServer server, String pathPrefix, ExecutorService executor) {
        this.server = server;
        this.pathPrefix = pathPrefix;
        this.executor = executor;
        registeredPaths.add(pathPrefix);
    }

    /** Compatibility overload for callers that publish one fixed file. */
    public static PackHttpServer start(File packFile,
                                       int port,
                                       String pathPrefix) throws IOException {
        return start(() -> packFile, port, pathPrefix);
    }

    /**
     * Bind and start. Pass port=0 to let the OS pick a free port (useful in tests).
     *
     * @param packFileSupplier supplies the file to serve at {@code pathPrefix}. The
     *                         route reads the file fresh on each request, so the URL
     *                         stays stable across re-mixes. May be null/absent at
     *                         start time — the route 404s until the file appears.
     * @param port             bind port; 0 = OS-picked.
     * @param pathPrefix       request path for the pack zip (e.g. {@code /rspm.zip}).
     */
    public static PackHttpServer start(Supplier<File> packFileSupplier,
                                       int port,
                                       String pathPrefix) throws IOException {
        Objects.requireNonNull(packFileSupplier, "packFileSupplier");
        return startWithDescriptor(
                () -> descriptorOrNull(packFileSupplier.get(), null), port, pathPrefix);
    }

    /**
     * Starts the primary pack route with a producer-owned digest descriptor.
     * This avoids re-hashing an already-digested published Java pack while still
     * letting all other routes fall back to a pinned-channel SHA-1.
     */
    public static PackHttpServer startWithDescriptor(
            Supplier<FileRouteDescriptor> packDescriptorSupplier,
            int port,
            String pathPrefix) throws IOException {
        Objects.requireNonNull(packDescriptorSupplier, "packDescriptorSupplier");
        validateRoutePath(pathPrefix);

        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        FileRoute packRoute = new FileRoute(packDescriptorSupplier, "application/zip");
        s.createContext(pathPrefix, exchange -> serveFile(exchange, pathPrefix, packRoute));
        ExecutorService exec = newHttpExecutor();
        s.setExecutor(exec);
        try {
            s.start();
        } catch (RuntimeException failure) {
            exec.shutdownNow();
            throw failure;
        }
        return new PackHttpServer(s, pathPrefix, exec);
    }

    /**
     * Register an additional route that serves a single file from disk. Reads the
     * current publication on every request so subsequent atomic replacements are
     * picked up immediately. Responses carry a strong SHA-1 ETag. IMS remains as
     * a compatibility fallback when a caller does not send If-None-Match.
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
        registerFileRoute(path, () -> descriptorOrNull(file, null), contentType);
    }

    /** Register a dynamic route whose publisher can provide its existing SHA-1. */
    public void registerFileRoute(String path,
                                  Supplier<FileRouteDescriptor> descriptorSupplier,
                                  String contentType) {
        validateRoutePath(path);
        Objects.requireNonNull(descriptorSupplier, "descriptorSupplier");
        Objects.requireNonNull(contentType, "contentType");
        FileRoute route = new FileRoute(descriptorSupplier, contentType);
        registerRouteIfAbsent(path,
                exchange -> serveFile(exchange, path, route));
    }

    /**
     * Registers a dynamically resolved executable JAR route protected by a
     * capability-specific bearer token derived from the RSPM network key.
     *
     * <p>The supplier is evaluated for every authorized GET/HEAD request, so a
     * newly downloaded update becomes visible without restarting the backend
     * HTTP listener. Missing/incorrect credentials receive 401. A missing
     * network key fails closed with 503; the raw key is never accepted as a
     * bearer token.</p>
     */
    public void registerProtectedExecutableRoute(String path,
                                                 Supplier<File> fileSupplier,
                                                 Supplier<String> networkKeySupplier) {
        validateRoutePath(path);
        Objects.requireNonNull(fileSupplier, "fileSupplier");
        Objects.requireNonNull(networkKeySupplier, "networkKeySupplier");
        FileRoute route = new FileRoute(
                () -> descriptorOrNull(fileSupplier.get(), null),
                "application/java-archive");
        registerRouteIfAbsent(path, exchange -> serveProtectedExecutable(
                exchange, path, route, networkKeySupplier));
    }

    /**
     * Registers a route once for this server instance. Reloads and unchanged-pack
     * reuse can revisit route wiring while the same HTTP listener remains live;
     * {@link HttpServer#createContext(String, com.sun.net.httpserver.HttpHandler)}
     * rejects that duplicate with "cannot add context to list".
     */
    private void registerRouteIfAbsent(String path,
                                       com.sun.net.httpserver.HttpHandler handler) {
        if (!registeredPaths.add(path)) return;
        try {
            server.createContext(path, handler);
        } catch (RuntimeException failure) {
            registeredPaths.remove(path);
            throw failure;
        }
    }

    private static void serveProtectedExecutable(HttpExchange exchange,
                                                  String routePath,
                                                  FileRoute route,
                                                  Supplier<String> networkKeySupplier)
            throws IOException {
        if (!routePath.equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        // Fast-reject credential-less requests BEFORE resolving the network key:
        // the key supplier may do real work per call (read + hash Floodgate's
        // key.pem, or on Floodgate-less backends fall through to a persistence
        // path), and none of that should be reachable from an unauthenticated
        // probe of this route.
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"rspm-network-update\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }

        final String networkKey;
        try {
            networkKey = networkKeySupplier.get();
        } catch (RuntimeException exception) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        if (networkKey == null || networkKey.isBlank()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }

        if (!NetworkAccessToken.matchesAuthorization(
                authorization, EXECUTABLE_UPDATE_TOKEN_DOMAIN, networkKey)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"rspm-network-update\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        serveFile(exchange, routePath, route);
    }

    private static void serveFile(HttpExchange exchange,
                                  String routePath,
                                  FileRoute route) throws IOException {
        try {
            if (!routePath.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String method = exchange.getRequestMethod();
            if (!isGet(method) && !isHead(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            FileRouteDescriptor descriptor = route.descriptorSupplier().get();
            if (descriptor == null || !descriptor.file().isFile()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            try (PinnedFile pinned = openPinned(descriptor.file().toPath())) {
                long length = pinned.identity().size();
                long lastModifiedSec = pinned.identity().lastModifiedMillis() / 1000L;
                String etag = route.etagFor(pinned, descriptor.sha1Hex());
                if (etag == null) {
                    // The mutable pathname no longer contains the bytes
                    // described by the committed authority snapshot. Never
                    // label replacement bytes with the old digest.
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.getResponseHeaders().set("Last-Modified",
                        HTTP_DATE.format(Instant.ofEpochSecond(lastModifiedSec)));

                String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                if (ifNoneMatch != null) {
                    // RFC 7232: If-None-Match takes precedence. A non-matching
                    // tag must not fall through to a same-second IMS result.
                    if (etagMatches(ifNoneMatch, etag)) {
                        exchange.sendResponseHeaders(304, -1);
                        return;
                    }
                } else {
                    String ifModifiedSince = exchange.getRequestHeaders()
                            .getFirst("If-Modified-Since");
                    Long sinceSec = ifModifiedSince == null
                            ? null
                            : parseHttpDateSeconds(ifModifiedSince);
                    if (sinceSec != null && lastModifiedSec <= sinceSec) {
                        exchange.sendResponseHeaders(304, -1);
                        return;
                    }
                }

                exchange.getResponseHeaders().set("Content-Type", route.contentType());
                if (isHead(method)) {
                    exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, length);
                try (var out = exchange.getResponseBody()) {
                    pinned.channel().position(0L);
                    byte[] buffer = new byte[FILE_BUFFER_SIZE];
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                    long remaining = length;
                    while (remaining > 0L) {
                        byteBuffer.clear();
                        byteBuffer.limit((int) Math.min(buffer.length, remaining));
                        int read = pinned.channel().read(byteBuffer);
                        if (read < 0) {
                            throw new IOException("Published file ended while it was being served");
                        }
                        if (read == 0) continue;
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        } catch (NoSuchFileException disappeared) {
            exchange.sendResponseHeaders(404, -1);
        } finally {
            exchange.close();
        }
    }

    private static PinnedFile openPinned(Path path) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            boolean accepted = false;
            try {
                // The descriptor is now pinned. Measure the open object rather
                // than asking the pathname for a size that could belong to a
                // concurrent atomic replacement.
                long pinnedSize = channel.size();
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
                FileIdentity beforeIdentity = FileIdentity.from(before, before.size());
                FileIdentity afterIdentity = FileIdentity.from(after, pinnedSize);
                if (pinnedSize == after.size() && beforeIdentity.equals(afterIdentity)) {
                    accepted = true;
                    return new PinnedFile(channel, afterIdentity);
                }
            } finally {
                if (!accepted) channel.close();
            }
        }
        throw new IOException("Published file changed repeatedly while opening it");
    }

    private static String sha1Etag(FileChannel channel, long length) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
        channel.position(0L);
        ByteBuffer buffer = ByteBuffer.allocate(FILE_BUFFER_SIZE);
        long remaining = length;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0) throw new IOException("Published file ended while hashing it");
            if (read == 0) continue;
            remaining -= read;
            buffer.flip();
            digest.update(buffer);
        }
        channel.position(0L);
        return etagFromSha1(HexFormat.of().formatHex(digest.digest()));
    }

    private static String etagFromSha1(String sha1Hex) {
        if (sha1Hex == null) return null;
        String normalized = sha1Hex.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != 40) return null;
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) return null;
        }
        return "\"sha1-" + normalized + "\"";
    }

    private static boolean etagMatches(String requestValue, String currentEtag) {
        for (String candidate : requestValue.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) return true;
            if (weakEtagValue(trimmed).equals(weakEtagValue(currentEtag))) return true;
        }
        return false;
    }

    private static String weakEtagValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "W/", 0, 2)
                ? trimmed.substring(2).trim()
                : trimmed;
    }

    private static FileRouteDescriptor descriptorOrNull(File file, String sha1Hex) {
        return file == null ? null : new FileRouteDescriptor(file, sha1Hex);
    }

    private static ExecutorService newHttpExecutor() {
        return new ThreadPoolExecutor(
                HTTP_WORKERS,
                HTTP_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "RSPM-pack-http-" + HTTP_THREAD_INDEX.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                // HttpServer closes the connection when dispatch throws. Rejecting
                // here gives overload prompt backpressure without unbounded threads.
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record FileIdentity(Object fileKey, long size, long lastModifiedMillis) {
        private static FileIdentity from(BasicFileAttributes attributes, long pinnedSize) {
            return new FileIdentity(
                    attributes.fileKey(), pinnedSize, attributes.lastModifiedTime().toMillis());
        }

        private boolean cacheable() {
            return fileKey != null;
        }
    }

    private record PinnedFile(FileChannel channel, FileIdentity identity) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record CachedEtag(FileIdentity identity, String etag) {
    }

    private static final class FileRoute {
        private final Supplier<FileRouteDescriptor> descriptorSupplier;
        private final String contentType;
        private volatile CachedEtag cachedEtag;

        private FileRoute(Supplier<FileRouteDescriptor> descriptorSupplier, String contentType) {
            this.descriptorSupplier = Objects.requireNonNull(
                    descriptorSupplier, "descriptorSupplier");
            this.contentType = Objects.requireNonNull(contentType, "contentType");
        }

        private Supplier<FileRouteDescriptor> descriptorSupplier() {
            return descriptorSupplier;
        }

        private String contentType() {
            return contentType;
        }

        private String etagFor(PinnedFile pinned, String publishedSha1) throws IOException {
            String published = etagFromSha1(publishedSha1);
            CachedEtag current = cachedEtag;
            if (pinned.identity().cacheable()
                    && current != null
                    && current.identity().equals(pinned.identity())) {
                return published == null || published.equals(current.etag())
                        ? current.etag()
                        : null;
            }
            synchronized (this) {
                current = cachedEtag;
                if (pinned.identity().cacheable()
                        && current != null
                        && current.identity().equals(pinned.identity())) {
                    return published == null || published.equals(current.etag())
                            ? current.etag()
                            : null;
                }
                String calculated = sha1Etag(pinned.channel(), pinned.identity().size());
                if (pinned.identity().cacheable()) {
                    cachedEtag = new CachedEtag(pinned.identity(), calculated);
                }
                return published == null || published.equals(calculated)
                        ? calculated
                        : null;
            }
        }
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

    private static void validateRoutePath(String path) {
        if (path == null || path.length() < 2 || !path.startsWith("/")) {
            throw new IllegalArgumentException("HTTP route must start with '/' and name a file");
        }
    }

    private static boolean isGet(String method) {
        return "GET".equalsIgnoreCase(method);
    }

    private static boolean isHead(String method) {
        return "HEAD".equalsIgnoreCase(method);
    }

    /** External URL for clients. Caller picks the public host. */
    public String urlOn(String publicHost) {
        String host = publicHost == null ? "" : publicHost.trim();
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + server.getAddress().getPort() + pathPrefix;
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
