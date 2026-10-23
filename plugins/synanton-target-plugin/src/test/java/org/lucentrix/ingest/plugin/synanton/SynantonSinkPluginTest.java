package org.lucentrix.ingest.plugin.synanton;

import org.junit.jupiter.api.Test;
import org.lucentrix.ingest.ChangeOp;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.encrypt.PasswordEncryptor;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;
import org.lucentrix.ingest.runtime.plugin.SinkContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynantonSinkPluginTest {

    @Test
    void pushDeleteCallsTheTombstonePathInsteadOfUpload() {
        RecordingClient client = new RecordingClient();
        SynantonSinkPlugin plugin = plugin(client);

        plugin.push(List.of(delete("src:a/b.pdf")));

        assertEquals(List.of("a/b.pdf"), client.deletes);
        assertTrue(client.uploads.isEmpty());
    }

    @Test
    void pushReplaceStillUploads() {
        RecordingClient client = new RecordingClient();
        SynantonSinkPlugin plugin = plugin(client);
        SourceDocument document = SourceDocument.builder()
                .field(Field.SOURCE_ID, "src")
                .field(Field.CONTENT_BYTES, "bytes".getBytes(StandardCharsets.UTF_8))
                .vsId("src:a/b.pdf")
                .build();

        plugin.push(List.of(ContentChange.builder()
                .action(ChangeOp.REPLACE)
                .document(document)
                .build()));

        assertEquals(List.of("a/b.pdf"), client.uploads);
        assertTrue(client.deletes.isEmpty());
    }

    private static ContentChange delete(String versionSeriesId) {
        return ContentChange.builder()
                .action(ChangeOp.DELETE)
                .document(SourceDocument.builder()
                        .field(Field.SOURCE_ID, "src")
                        .vsId(versionSeriesId)
                        .build())
                .build();
    }

    private static SynantonSinkPlugin plugin(RecordingClient client) {
        SynantonSinkConfig.Settings settings = SynantonSinkConfig.Settings.builder()
                .name("synanton")
                .baseUrl("http://localhost")
                .apiKey("token")
                .sourceId("src")
                .connectorId("upload")
                .sourceEndpoint("upload://src")
                .build();
        SynantonSinkConfig config = new SynantonSinkConfig(settings, ConfigEnv.builder()
                .encryptor(new PasswordEncryptor())
                .build());
        SinkContext context = SinkContext.builder()
                .pluginId("synanton-target-plugin")
                .build();
        return new SynantonSinkPlugin(config, context, client);
    }

    private static final class RecordingClient extends SynantonClient {

        private final List<String> uploads = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();

        private RecordingClient() {
            super("http://localhost", "src", "upload", "upload://src", "token");
        }

        @Override
        UploadResult upload(byte[] bytes, String resourceId, String mimeType) {
            uploads.add(resourceId);
            return new UploadResult("run-u", "op-u", "SCHEDULED");
        }

        @Override
        UploadResult delete(String resourceId) {
            deletes.add(resourceId);
            return new UploadResult("run-d", "op-d", "SCHEDULED");
        }
    }
}
