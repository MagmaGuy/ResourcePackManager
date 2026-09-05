package com.magmaguy.resourcepackmanager.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackHttpServerTest {

    @Test
    void servesPackWithCacheValidationAndRejectsInvalidRequests(@TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hi".getBytes());
            zos.closeEntry();
        }
        byte[] expected = Files.readAllBytes(zipPath);
        long expectedLength = expected.length;

        try (PackHttpServer server = PackHttpServer.start(zipPath.toFile(), 0, "/test.zip");
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String url = server.urlOn("127.0.0.1");
            assertEquals("http://127.0.0.1:" + server.port() + "/test.zip", url);

            HttpResponse<byte[]> response = client.send(
                    request(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(null));
            assertEquals(String.valueOf(expectedLength),
                    response.headers().firstValue("Content-Length").orElse(null));
            assertArrayEquals(expected, response.body());
            String etag = "\"sha1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(expected)) + "\"";
            assertEquals(etag, response.headers().firstValue("ETag").orElseThrow());
            String modified = response.headers().firstValue("Last-Modified").orElseThrow();
            assertFalse(modified.isBlank());

            HttpResponse<byte[]> headResponse = client.send(
                    request(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, headResponse.statusCode());
            assertEquals("application/zip", headResponse.headers().firstValue("Content-Type").orElse(null));
            assertEquals(String.valueOf(expectedLength),
                    headResponse.headers().firstValue("Content-Length").orElse(null));
            assertEquals(0, headResponse.body().length);
            assertEquals(etag, headResponse.headers().firstValue("ETag").orElseThrow());
            assertEquals(modified, headResponse.headers().firstValue("Last-Modified").orElseThrow());

            for (String validator : new String[]{"If-None-Match", "If-Modified-Since"}) {
                var cached = client.send(request(url).header(validator,
                                validator.equals("If-None-Match") ? etag : modified).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(304, cached.statusCode(), validator);
                assertEquals(0, cached.body().length);
            }

            var mismatched = client.send(request(url).header("If-None-Match", "\"different\"")
                            .header("If-Modified-Since", modified).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, mismatched.statusCode(), "A different ETag must take precedence over the date validator");
            assertArrayEquals(expected, mismatched.body());

            var missing = client.send(request(url + "/missing").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, missing.statusCode());
            assertEquals(0, missing.body().length, "A prefix match must not leak pack bytes");

            var unsupported = client.send(request(url).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, unsupported.statusCode());
            assertEquals("GET, HEAD", unsupported.headers().firstValue("Allow").orElseThrow());
            assertEquals(0, unsupported.body().length);
            assertArrayEquals(expected, Files.readAllBytes(zipPath), "Read requests must not modify the publication");

            Files.delete(zipPath);
            var unpublished = client.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, unpublished.statusCode());
            assertEquals(0, unpublished.body().length);
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
    }
}
