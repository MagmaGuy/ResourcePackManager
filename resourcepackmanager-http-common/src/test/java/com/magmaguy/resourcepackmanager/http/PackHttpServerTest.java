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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackHttpServerTest {

    @Test
    void servesPackWithCorrectHeaders(@TempDir Path tempDir) throws Exception {
        // 1. Create a tiny zip with a known entry.
        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hi".getBytes());
            zos.closeEntry();
        }
        byte[] expected = Files.readAllBytes(zipPath);
        long expectedLength = expected.length;

        // 2. Start server on OS-picked port.
        try (PackHttpServer server = PackHttpServer.start(zipPath.toFile(), 0, "/test.zip")) {
            String url = server.urlOn("127.0.0.1");

            // 3. GET.
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            // 4. Assert.
            assertEquals(200, response.statusCode());
            assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(null));
            assertEquals(String.valueOf(expectedLength),
                    response.headers().firstValue("Content-Length").orElse(null));
            assertArrayEquals(expected, response.body());

            HttpResponse<byte[]> headResponse = client.send(
                    HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, headResponse.statusCode());
            assertEquals("application/zip", headResponse.headers().firstValue("Content-Type").orElse(null));
            assertEquals(String.valueOf(expectedLength),
                    headResponse.headers().firstValue("Content-Length").orElse(null));
            assertEquals(0, headResponse.body().length);
        }
    }
}
