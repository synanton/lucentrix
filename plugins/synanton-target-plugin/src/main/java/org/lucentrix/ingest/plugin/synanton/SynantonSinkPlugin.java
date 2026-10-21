package org.lucentrix.ingest.plugin.synanton;

import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.runtime.SinkPlugin;
import org.lucentrix.ingest.runtime.plugin.AbstractPlugin;
import org.lucentrix.ingest.runtime.plugin.SinkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SynantonSinkPlugin extends AbstractPlugin<SynantonSinkConfig, SinkContext> implements SinkPlugin {

    private static final Logger log = LoggerFactory.getLogger(SynantonSinkPlugin.class);
    private final SynantonClient client;

    public SynantonSinkPlugin(SynantonSinkConfig config, SinkContext context) {
        super(config, context);
        SynantonSinkConfig.Settings settings = config.getSettings();
        this.client = new SynantonClient(
                settings.getBaseUrl(),
                settings.getSourceId(),
                settings.getConnectorId(),
                settings.getSourceEndpoint(),
                settings.getApiKey());
    }

    SynantonSinkPlugin(SynantonSinkConfig config, SinkContext context, SynantonClient client) {
        super(config, context);
        this.client = client;
    }

    @Override
    public void push(List<ContentChange> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (ContentChange change : documents) {
            if (change == null || change.getDocument() == null) {
                continue;
            }
            if (SynantonPayload.isDelete(change)) {
                log.warn("DELETE not implemented for Synanton v1; skipping id={}", change.getId());
                continue;
            }
            SourceDocument document = change.getDocument();
            String resourceId = SynantonPayload.resourceId(document);
            SynantonClient.UploadResult result = client.upload(
                    SynantonPayload.bytes(document),
                    resourceId,
                    SynantonPayload.mimeType(document));
            log.info("Queued {} as run {} (operation {}, status {})",
                    resourceId, result.runId(), result.operationId(), result.status());
        }
    }
}
