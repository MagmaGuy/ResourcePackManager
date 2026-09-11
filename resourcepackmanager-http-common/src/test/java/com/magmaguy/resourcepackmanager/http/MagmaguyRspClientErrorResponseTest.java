package com.magmaguy.resourcepackmanager.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MagmaguyRspClientErrorResponseTest {

    @Test
    void htmlBadGatewayReturnsStructuredUnavailableErrorWithoutParserStackTrace() throws Exception {
        byte[] body = "<html><title>502 Bad Gateway</title></html>"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rsp/sha1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("rsp-client-error-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rsp/");
        try (AutoCloseable ignored = MagmaguyRspClient.useLoopbackBaseUrlForTests(baseUri);
             MagmaguyRspClient client = new MagmaguyRspClient(logger, 2, 2)) {
            MagmaguyRspClient.Sha1Result result = client.sha1Check(
                    "123e4567-e89b-42d3-a456-426614174000",
                    "0123456789abcdef0123456789abcdef01234567");

            assertFalse(result.matched());
            assertNotNull(result.errorOrNull());
            assertEquals("SERVER_UNAVAILABLE", result.errorOrNull().code());
            assertEquals(502, result.errorOrNull().httpStatus());
            assertEquals("Remote server returned HTTP 502", result.errorOrNull().message());
            records.forEach(record -> assertNull(record.getThrown()));
        } finally {
            server.stop(0);
        }
    }
}
