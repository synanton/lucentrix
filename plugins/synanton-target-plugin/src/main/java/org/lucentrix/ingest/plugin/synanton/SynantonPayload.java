package org.lucentrix.ingest.plugin.synanton;

import org.lucentrix.ingest.ChangeOp;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.serde.json.JacksonConfig;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SynantonPayload {

    private SynantonPayload() {
    }

    static byte[] bytes(SourceDocument document) {
        byte[] raw = document.get(Field.CONTENT_BYTES);
        if (raw != null && raw.length > 0) {
            return raw;
        }
        String text = document.get(Field.CONTENT);
        if (text != null) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return JacksonConfig.configureMapper().writeValueAsBytes(document);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize source document " + document.getId(), ex);
        }
    }

    static String mimeType(SourceDocument document) {
        String mime = document.getMimeType();
        if (mime != null && !mime.isBlank()) {
            return mime;
        }
        if (document.get(Field.CONTENT_BYTES) != null) {
            return "application/octet-stream";
        }
        if (document.get(Field.CONTENT) != null) {
            return "text/plain";
        }
        return "application/json";
    }

    static String sourceUri(SourceDocument document) {
        String uri = document.get(Field.SOURCE_URI);
        if (uri != null && !uri.isBlank()) {
            return uri;
        }
        String sourceId = document.get(Field.SOURCE_ID);
        if (sourceId != null && !sourceId.isBlank()) {
            return sourceId;
        }
        return "lucentrix://" + document.getId();
    }

    /**
     * Connector-relative path for the platform SourceIdentity. The filesystem
     * source carries it as {@code vsId = rootId:relativePath}; older/other
     * sources are read back from the Lucentrix source URI.
     */
    static String resourceId(SourceDocument document) {
        String versionSeries = document.getVersionSeriesId();
        if (versionSeries != null && !versionSeries.isBlank()) {
            int separator = versionSeries.indexOf(':');
            if (separator >= 0 && separator < versionSeries.length() - 1) {
                return normalize(versionSeries.substring(separator + 1));
            }
            return normalize(versionSeries);
        }
        String uri = sourceUri(document);
        String marker = "://filesystem/";
        int start = uri.indexOf(marker);
        if (start >= 0) {
            String remainder = uri.substring(start + marker.length());
            int slash = remainder.indexOf('/');
            if (slash >= 0 && slash < remainder.length() - 1) {
                return normalize(decodePath(remainder.substring(slash + 1)));
            }
        }
        throw new IllegalArgumentException("Cannot derive a resource_id for document " + document.getId());
    }

    private static String decodePath(String encoded) {
        StringBuilder decoded = new StringBuilder(encoded.length());
        String[] segments = encoded.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                decoded.append('/');
            }
            decoded.append(URLDecoder.decode(segments[i], StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    private static String normalize(String value) {
        List<String> kept = new ArrayList<>();
        for (String part : value.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("resource_id must be a normalized connector-relative path");
            }
            kept.add(part);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("resource_id must not be empty");
        }
        return String.join("/", kept);
    }

    static boolean isDelete(ContentChange change) {
        return change != null && change.getAction() == ChangeOp.DELETE;
    }
}
