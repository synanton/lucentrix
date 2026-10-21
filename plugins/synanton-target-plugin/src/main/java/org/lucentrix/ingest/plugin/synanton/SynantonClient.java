package org.lucentrix.ingest.plugin.synanton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caller-pushed ingestion over the platform's {@code /v1} contract.
 *
 * <p>Slice 4: the legacy pair ({@code POST /content/{tenant}} plus
 * {@code POST /ingest}) is gone. Each file is one multipart
 * {@code POST /v1/ingestion-runs}; the platform writes the bytes to the
 * content cache, publishes a SourceVersion, and schedules extraction. The
 * tenant is derived from the bearer token — Lucentrix never sends a tenant
 * header.
 */
class SynantonClient {

    private static final Logger log = LoggerFactory.getLogger(SynantonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOUNDARY_PREFIX = "lucentrix-";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;
    private final String sourceId;
    private final String connectorId;
    private final String sourceEndpoint;
    private final String apiKey;
    private final AtomicBoolean sourceEnsured = new AtomicBoolean(false);

    SynantonClient(String baseUrl, String sourceId, String connectorId, String sourceEndpoint, String apiKey) {
        this.baseUrl = trimSlash(baseUrl);
        this.sourceId = sourceId;
        this.connectorId = connectorId;
        this.sourceEndpoint = sourceEndpoint;
        this.apiKey = apiKey;
    }

    UploadResult upload(byte[] bytes, String resourceId, String mimeType) {
        ensureSource();
        String boundary = BOUNDARY_PREFIX + UUID.randomUUID();
        byte[] body;
        try {
            body = multipart(boundary, resourceId, mimeType, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build multipart upload for " + resourceId, ex);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/ingestion-runs"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        authorize(builder);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("ingestion upload failed: " + response.statusCode() + " " + response.body());
            }
            JsonNode node = MAPPER.readTree(response.body());
            return new UploadResult(
                    node.path("run_id").asText(),
                    node.path("operation_id").asText(),
                    node.path("status").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted uploading " + resourceId, ex);
        } catch (Exception ex) {
            throw new RuntimeException("Failed uploading " + resourceId + " to " + baseUrl, ex);
        }
    }

    private void ensureSource() {
        if (!sourceEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("source_id", sourceId);
            request.put("connector_id", connectorId);
            request.put("connector_type", "upload");
            request.put("source_endpoint", sourceEndpoint);
            request.put("capability_flags", new String[0]);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/sources"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(request)));
            authorize(builder);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            // Re-crawl idempotency: the platform returns 409 when the source
            // already exists. That is a successful precondition, not a failure.
            if (status >= 300 && status != 409) {
                throw new IllegalStateException("source registration failed: " + status + " " + response.body());
            }
            log.info(status == 409
                            ? "Upload source {} already exists (connector={}, endpoint={})"
                            : "Registered upload source {} (connector={}, endpoint={})",
                    sourceId, connectorId, sourceEndpoint);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sourceEnsured.set(false);
            throw new RuntimeException("Interrupted registering " + sourceId, ex);
        } catch (Exception ex) {
            sourceEnsured.set(false);
            throw new RuntimeException("Failed registering upload source " + sourceId, ex);
        }
    }

    private void authorize(HttpRequest.Builder builder) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private byte[] multipart(String boundary, String resourceId, String mimeType, byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 1024);
        writeField(out, boundary, "connector_id", connectorId);
        writeField(out, boundary, "source_id", sourceId);
        writeField(out, boundary, "resource_id", resourceId);
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename(resourceId) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String filename(String resourceId) {
        int slash = resourceId.lastIndexOf('/');
        String name = slash >= 0 ? resourceId.substring(slash + 1) : resourceId;
        return name.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    record UploadResult(String runId, String operationId, String status) {
    }
}
