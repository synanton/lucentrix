package org.lucentrix.ingest.plugin.synanton;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynantonClientTest {

    @Test
    void existingSource409DoesNotAbortTheUpload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sources", exchange -> {
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
        });
        server.createContext("/v1/ingestion-runs", exchange -> {
            byte[] body = "{\"run_id\":\"run-1\",\"operation_id\":\"op-1\",\"status\":\"SCHEDULED\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SynantonClient client = new SynantonClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "src", "upload", "upload://src", "token");

            SynantonClient.UploadResult result = client.upload(
                    "hello".getBytes(StandardCharsets.UTF_8), "doc.txt", "text/plain");

            assertEquals("run-1", result.runId());
            assertEquals("op-1", result.operationId());
            assertEquals("SCHEDULED", result.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deleteHttp500PropagatesAsAFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sources", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/sources/src/resources", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            SynantonClient client = new SynantonClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "src", "upload", "upload://src", "token");

            assertThrows(RuntimeException.class, () -> client.delete("a.pdf"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deleteHttp404IsIdempotentAlreadyAbsent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sources", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/sources/src/resources", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            SynantonClient client = new SynantonClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "src", "upload", "upload://src", "token");

            SynantonClient.UploadResult result = client.delete("a.pdf");

            assertEquals("NOT_FOUND", result.status());
            assertTrue(result.runId().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deleteUsesThePerResourceTombstoneEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sources", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/sources/src/resources", exchange -> {
            method.set(exchange.getRequestMethod());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"run_id\":\"run-d\",\"operation_id\":\"op-d\",\"status\":\"SCHEDULED\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SynantonClient client = new SynantonClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "src", "upload", "upload://src", "token");

            SynantonClient.UploadResult result = client.delete("a/b c.pdf");

            assertEquals("DELETE", method.get());
            assertEquals("resource_id=a/b c.pdf",
                    URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8));
            assertEquals("run-d", result.runId());
            assertEquals("op-d", result.operationId());
            assertTrue("SCHEDULED".equals(result.status()));
        } finally {
            server.stop(0);
        }
    }
}
