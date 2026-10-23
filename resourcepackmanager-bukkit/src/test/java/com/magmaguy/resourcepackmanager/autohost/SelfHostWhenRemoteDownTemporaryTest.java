package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Temporary ticket check (srhmike, SUPERSHOOT): while magmaguy.com is down, a server with working
 * self-hosting must still serve its pack. A dead loopback port stands in for magmaguy.com, and a
 * loopback proxy stands in for the server's public address, which this server can download from.
 */
class SelfHostWhenRemoteDownTemporaryTest {
    private static final String PUBLIC_HOST = "rspm-public.example";
    private static final String PUBLIC_URL = "http://" + PUBLIC_HOST + ":8080/rspm.zip";

    static final class TemporaryPlugin extends JavaPlugin {
        TemporaryPlugin(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
            super(loader, description, dataFolder, file);
        }
    }

    @Test void outageFallsBackToVerifiedLocalSelfHosting(@TempDir Path root) throws Exception {
        Path zip = pack(root);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(zip)));
        int deadPort = freePort();
        int backendPort = freePort();

        AtomicInteger publicRequests = new AtomicInteger();
        HttpServer publicAddress = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try (HttpClient upstream = HttpClient.newHttpClient()) {
            publicAddress.createContext("/", exchange -> {
                try (exchange) {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + backendPort
                            + exchange.getRequestURI().getPath())).GET();
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range != null) request.header("Range", range);
                    var response = upstream.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    response.headers().firstValue("Content-Range")
                            .ifPresent(value -> exchange.getResponseHeaders().set("Content-Range", value));
                    publicRequests.incrementAndGet();
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            });
            publicAddress.start();
            ProxySelector previousSelector = ProxySelector.getDefault();
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    return PUBLIC_HOST.equals(uri.getHost())
                            ? List.of(new Proxy(Proxy.Type.HTTP, publicAddress.getAddress())) : List.of(Proxy.NO_PROXY);
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            });

            List<String> log = new CopyOnWriteArrayList<>();
            Server server = server(log);
            Map<String, Object> defaults = Map.of("selfHostEnabled", true, "selfHostForce", false, "preferSelfHost", true,
                    "selfHostPort", backendPort, "selfHostExternalUrl", PUBLIC_URL, "verboseLogging", true);
            Map<String, Object> previousDefaults = new ConcurrentHashMap<>();
            for (String name : defaults.keySet()) previousDefaults.put(name, getStatic(DefaultConfig.class, name));
            MagmaguyRspClient client = new MagmaguyRspClient(server.getLogger(), 5, 5);
            AutoCloseable deadRemote = loopbackRemote(deadPort);
            try {
                setStatic(Bukkit.class, "server", server);
                ResourcePackManager.plugin = new TemporaryPlugin(new JavaPluginLoader(server),
                        new PluginDescriptionFile("ResourcePackManager", "temporary", "temporary.Main"),
                        root.resolve("data").toFile(), root.resolve("ResourcePackManager.jar").toFile());
                defaults.forEach((name, value) -> setStatic(DefaultConfig.class, name, value));
                setStatic(Mix.class, "finalResourcePack", zip.toFile());
                setStatic(Mix.class, "finalSHA1", sha1);
                setStatic(DataConfig.class, "instance", dataConfig());
                setStatic(AutoHost.class, "javaPackRouteDescriptor", new PackHttpServer.FileRouteDescriptor(zip.toFile(), sha1));
                setStatic(AutoHost.class, "client", client);

                Object run = lifecycleRun();
                Method checkFileExistence = AutoHost.class.getDeclaredMethod("checkFileExistence", run.getClass());
                checkFileExistence.setAccessible(true);
                checkFileExistence.invoke(null, run);

                String status = (String) getStatic(AutoHost.class, "lastSelfHostCheck");
                assertTrue((boolean) getStatic(AutoHost.class, "done"), "pack delivery committed; /rspm status: " + status
                        + "\nlog:\n" + String.join("\n", log));
                assertEquals(PUBLIC_URL, getStatic(AutoHost.class, "selfHostedUrl"));
                assertEquals("passed locally, self-hosting at " + PUBLIC_URL
                        + ". magmaguy.com was unreachable, so outside access is unverified.", status);
                assertTrue(log.stream().anyMatch(line -> line.startsWith("WARNING")
                        && line.contains("magmaguy.com can't be reached, so the resource pack is self-hosted at " + PUBLIC_URL)), String.join("\n", log));
                assertTrue(log.stream().anyMatch(line -> line.contains("Resource pack is live via self-hosting — " + PUBLIC_URL)),
                        String.join("\n", log));

                HttpURLConnection download = (HttpURLConnection) URI.create(PUBLIC_URL).toURL().openConnection();
                try (InputStream body = download.getInputStream()) {
                    assertEquals(200, download.getResponseCode());
                    assertArrayEquals(Files.readAllBytes(zip), body.readAllBytes());
                }
                assertTrue(publicRequests.get() >= 2, "the self-host check and the player download used the public address");
                System.out.println("PASS self-hosting at " + PUBLIC_URL + " while magmaguy.com (127.0.0.1:" + deadPort
                        + ") refused connections; /rspm status: " + status);
            } finally {
                Method tearDown = AutoHost.class.getDeclaredMethod("tearDownSelfHost");
                tearDown.setAccessible(true);
                tearDown.invoke(null);
                setStatic(AutoHost.class, "lifecycleRun", null);
                setStatic(AutoHost.class, "client", null);
                setStatic(AutoHost.class, "javaPackRouteDescriptor", null);
                setStatic(AutoHost.class, "announcedDelivery", false);
                setStatic(AutoHost.class, "lastSelfHostCheck", null);
                client.close();
                deadRemote.close();
                previousDefaults.forEach((name, value) -> setStatic(DefaultConfig.class, name, value));
                setStatic(Mix.class, "finalResourcePack", null);
                setStatic(Mix.class, "finalSHA1", null);
                setStatic(DataConfig.class, "instance", null);
                ResourcePackManager.plugin = null;
                setStatic(com.magmaguy.resourcepackmanager.network.NetworkMode.class, "cached", null);
                setStatic(Bukkit.class, "server", null);
                ProxySelector.setDefault(previousSelector);
            }
        } finally {
            publicAddress.stop(0);
        }
    }

    private static Object lifecycleRun() throws Exception {
        AtomicLong generations = (AtomicLong) getStatic(AutoHost.class, "LIFECYCLE_GENERATION");
        Class<?> type = Class.forName(AutoHost.class.getName() + "$LifecycleRun");
        var constructor = type.getDeclaredConstructor(long.class, BooleanSupplier.class, AutoHost.RetryWindow.class,
                AutoHost.RetryWindow.class, Map.class, AtomicBoolean.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(generations.incrementAndGet(), (BooleanSupplier) () -> false,
                new AutoHost.RetryWindow(), new AutoHost.RetryWindow(), new ConcurrentHashMap<>(), new AtomicBoolean());
        setStatic(AutoHost.class, "lifecycleRun", run);
        return run;
    }

    /** DataConfig normally loads data.yml through the plugin; an empty one has no hosting UUID. */
    private static DataConfig dataConfig() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        DataConfig config = (DataConfig) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, DataConfig.class);
        Field file = ConfigurationFile.class.getDeclaredField("fileConfiguration");
        file.setAccessible(true);
        file.set(config, new YamlConfiguration());
        return config;
    }

    private static AutoCloseable loopbackRemote(int port) throws Exception {
        Method method = MagmaguyRspClient.class.getDeclaredMethod("useLoopbackBaseUrlForTests", URI.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(null, URI.create("http://127.0.0.1:" + port + "/rsp/"));
    }

    private static Server server(List<String> log) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("rspm-temporary-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { log.add(record.getLevel() + " " + record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        BukkitScheduler scheduler = (BukkitScheduler) java.lang.reflect.Proxy.newProxyInstance(
                BukkitScheduler.class.getClassLoader(), new Class<?>[]{BukkitScheduler.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        // No Geyser or floodgate installed, so NetworkMode stays standalone.
        PluginManager plugins = (PluginManager) java.lang.reflect.Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        return (Server) java.lang.reflect.Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getLogger" -> logger;
                    case "getName", "getVersion", "getBukkitVersion" -> "temporary";
                    case "getPort" -> 25565;
                    case "getScheduler" -> scheduler;
                    case "getPluginManager" -> plugins;
                    case "getOnlinePlayers" -> List.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "temporary server";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Object getStatic(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    private static void setStatic(Class<?> owner, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    private static Path pack(Path root) throws IOException {
        Path file = root.resolve("pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":75,\"description\":\"remote down check\"}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
