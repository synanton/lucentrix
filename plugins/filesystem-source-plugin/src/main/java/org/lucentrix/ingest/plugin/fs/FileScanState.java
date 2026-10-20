package org.lucentrix.ingest.plugin.fs;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.lucentrix.ingest.metadata.HasId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable scan state: the fingerprint of every file observed in the last
 * complete scan, keyed by connector-relative path.
 *
 * <p>Persisted as a single JSON file by {@code FileJsonPersistence}. The
 * write-amplification decision for this shape is recorded on
 * {@link FileSystemSourcePlugin}: checkpoint frequency is bounded by
 * {@code checkpointBatchSize}; a sharded sidecar is deferred until a real
 * library shows it is needed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FileScanState implements HasId {

    private String id;
    private Map<String, FileFingerprint> fingerprints = new LinkedHashMap<>();
    private long lastScanCompletedMillis;

    public Map<String, FileFingerprint> fingerprintsOrEmpty() {
        if (fingerprints == null) {
            fingerprints = new LinkedHashMap<>();
        }
        return fingerprints;
    }
}
