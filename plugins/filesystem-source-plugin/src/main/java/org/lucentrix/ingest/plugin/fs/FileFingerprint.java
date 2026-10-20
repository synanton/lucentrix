package org.lucentrix.ingest.plugin.fs;

/**
 * Per-file change-detection record.
 *
 * <p>{@code sha256} is {@code null} when hashing is disabled
 * ({@code computeSha256=false}), which is intended for benchmarking only.
 */
public record FileFingerprint(long size, long modifiedMillis, String sha256) {

    public boolean sameMetadata(long otherSize, long otherModifiedMillis) {
        return size == otherSize && modifiedMillis == otherModifiedMillis;
    }

    public boolean sameContent(String otherSha256) {
        return sha256 != null && sha256.equals(otherSha256);
    }
}
