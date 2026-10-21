package org.lucentrix.ingest.plugin.synanton;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
