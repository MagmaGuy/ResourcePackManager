package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary public tickets #15/#16: real sockets, separate TLS termination, exact pack bytes. */
public class SelfHostTicketTest {
    @Test
    public void configuredIpv6LiteralDownloadsExactPack(@TempDir Path root) throws Exception {
        Path zip = pack(root);
        try (PackHttpServer backend = PackHttpServer.start(zip.toFile(), 0, "/rspm.zip");
             HttpClient client = HttpClient.newHttpClient()) {
            String address = backend.urlOn("::1");
            assertEquals("http://[::1]:" + backend.port() + "/rspm.zip", address);
            var response = client.send(request(address), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(Files.readAllBytes(zip), response.body());
            System.out.println("PASS IPv6 literal endpoint " + address);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TICKET_PACKS", matches = ".+")
    public void externalHttpsUrlForwardsToIndependentInternalHttpPort(@TempDir Path root) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        char[] password = "temporary-test-only".toCharArray();
        try (var input = Files.newInputStream(Path.of(System.getenv("NIGHTBREAK_TICKET_PACKS"), "loopback-test.p12"))) {
            keys.load(input, password);
        }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password);
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        Path zip = pack(root);
        AtomicInteger forwarded = new AtomicInteger();
        HttpsServer proxy = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setHttpsConfigurator(new HttpsConfigurator(tls));
        try (PackHttpServer backend = PackHttpServer.start(zip.toFile(), 0, "/rspm.zip");
             HttpClient upstream = HttpClient.newHttpClient();
             HttpClient client = HttpClient.newBuilder().sslContext(tls).build()) {
            proxy.createContext("/custom/public.zip", exchange -> {
                try (exchange) {
                    var response = upstream.send(request(backend.urlOn("127.0.0.1")), HttpResponse.BodyHandlers.ofByteArray());
                    forwarded.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
            });
            proxy.start();
            String configured = "https://localhost:" + proxy.getAddress().getPort() + "/custom/public.zip";
            var method = java.util.Arrays.stream(AutoHost.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("selfHostUrl")).findFirst().orElseThrow();
            method.setAccessible(true);
            String advertised = (String) method.invoke(null, null, backend, SelfHostPublicUrl.parse(configured));
            assertEquals(configured, advertised);
            assertNotEquals(backend.port(), URI.create(advertised).getPort());
            var response = client.send(request(advertised), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertTrue(response.sslSession().isPresent(), "The client actually negotiated TLS");
            assertArrayEquals(Files.readAllBytes(zip), response.body());
            assertEquals(1, forwarded.get());
            System.out.println("PASS advertised " + advertised + " -> " + backend.urlOn("127.0.0.1"));
        } finally { proxy.stop(0); }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
    }
    private static Path pack(Path root) throws Exception {
        Path file = root.resolve("pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":75,\"description\":\"ticket test\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
