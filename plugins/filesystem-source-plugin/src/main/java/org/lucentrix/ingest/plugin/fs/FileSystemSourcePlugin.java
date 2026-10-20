package org.lucentrix.ingest.plugin.fs;

import org.apache.commons.lang3.StringUtils;
import org.lucentrix.ingest.ChangeOp;
import org.lucentrix.ingest.ChangePage;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.metadata.Cursor;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.runtime.SourcePlugin;
import org.lucentrix.ingest.runtime.plugin.AbstractPlugin;
import org.lucentrix.ingest.runtime.plugin.SourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Incremental filesystem source connector for PDF and EPUB libraries.
 *
 * <p>Walks {@code root} recursively, emits a {@link ChangeOp#REPLACE} for every
 * new or changed file matching {@code include}, and persists a per-path
 * fingerprint so a repeated scan of an unchanged tree emits nothing.
 *
 * <p><strong>Persistence decision (slice 1):</strong>
 * {@code FileJsonPersistence.save} rewrites the whole JSON file, and
 * {@code IngestRunner} calls {@code save()} after each non-empty page (default
 * page size 1000). A 1K-book state is roughly 200 KB, so it is written at most
 * once per scan; for much larger libraries {@code checkpointBatchSize} throttles
 * writes. Re-walking is idempotent because the platform deduplicates by content
 * digest, so an unsaved checkpoint is safe. Sharding the state into a sidecar is
 * deferred until a real library demonstrates write amplification.
 *
 * <p>Deletion reconciliation (only after a complete scan) is slice 3 and is not
 * emitted here.
 */
public class FileSystemSourcePlugin extends AbstractPlugin<FileSystemSourceConfig, SourceContext> implements SourcePlugin {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSourcePlugin.class);

    private static final UUID ID_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final String URI_SCHEME = "lucentrix://filesystem/";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final Deque<Path> directories = new ArrayDeque<>();
    private final Set<String> seenThisScan = new HashSet<>();
    private final Set<Path> visitedRealDirectories = new HashSet<>();
    private final List<PathMatcher> includeMatchers = new ArrayList<>();
    private final List<PathMatcher> rootMatchers = new ArrayList<>();

    private Cursor cursor;
    private FileScanState state;
    private Path root;
    private String rootId;
    private Iterator<Path> fileIterator;
    private boolean walkComplete;
    private boolean done;
    private boolean computeSha256;
    private int checkpointBatchSize;
    private int maxDepth;
    private boolean followSymlinks;
    private int dirtyFingerprints;
    private int persistedFingerprints;

    public FileSystemSourcePlugin(FileSystemSourceConfig config, SourceContext context) {
        super(config, context);
        if (getPersistence() == null) {
            throw new IllegalArgumentException("Cursor persistence is null!");
        }
    }

    @Override
    public void start() {
        super.start();

        FileSystemSourceConfig.Settings settings = config.getSettings();
        String name = config.getName();
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("FileSystemSourceConfig name field is blank or null!");
        }
        if (StringUtils.isBlank(settings.getRoot())) {
            throw new IllegalArgumentException("FileSystemSourceConfig root field is blank or null!");
        }

        this.root = Paths.get(settings.getRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Filesystem source root is not a directory: " + root);
        }

        this.rootId = StringUtils.defaultIfBlank(settings.getRootId(), name);
        this.computeSha256 = settings.isComputeSha256();
        this.checkpointBatchSize = Math.max(1, settings.getCheckpointBatchSize());
        this.maxDepth = settings.getMaxDepth() > 0 ? settings.getMaxDepth() : Integer.MAX_VALUE;
        this.followSymlinks = settings.isFollowSymlinks();
        compileIncludes(settings.getInclude());

        // Cycle guard: only tracked when symlinks are followed, so the common
        // (followSymlinks=false) path pays no toRealPath() cost.
        this.visitedRealDirectories.clear();
        if (followSymlinks) {
            try {
                this.visitedRealDirectories.add(root.toRealPath());
            } catch (IOException e) {
                logger.warn("Cannot resolve filesystem root {}: {}", root, e.getMessage());
            }
        }

        this.cursor = loadCursor(name);
        this.state = loadState(name);

        this.walkComplete = false;
        this.done = false;
        this.fileIterator = null;
        this.dirtyFingerprints = 0;
        this.persistedFingerprints = 0;
        this.directories.clear();
        this.directories.add(root);
        this.seenThisScan.clear();

        logger.info("Filesystem source started: root={}, rootId={}, include={}",
                root, rootId, settings.getInclude());
    }

    @Override
    public boolean hasNext() {
        return !done;
    }

    @Override
    public ChangePage next() {
        if (done) {
            return ChangePage.builder().items(Collections.emptyList()).cursor(cursor).hasNext(false).build();
        }

        int pageSize = context.getPageSize() > 0 ? context.getPageSize() : 1000;
        List<ContentChange> events = new ArrayList<>();

        while (events.size() < pageSize) {
            Path file = nextFile();
            if (file == null) {
                walkComplete = true;
                break;
            }

            String relativePath = relativePath(file);
            if (!seenThisScan.add(relativePath)) {
                continue;
            }

            try {
                ContentChange change = inspect(file, relativePath);
                if (change != null) {
                    events.add(change);
                }
            } catch (IOException | SecurityException e) {
                logger.warn("Skipping unreadable file {}: {}", file, e.getMessage());
            }
        }

        if (walkComplete) {
            // Slice 3 will reconcile deletions here: every state key not in
            // seenThisScan is a deletion candidate, emitted only after a
            // complete walk. Deliberately not emitted yet.
            done = true;
            state.setLastScanCompletedMillis(System.currentTimeMillis());
        }

        return ChangePage.builder().items(events).cursor(cursor).hasNext(!done).build();
    }

    @Override
    public void save() {
        getPersistence().save(Cursor.SuffixCursor.toSuffixCursor(cursor));

        if (dirtyFingerprints == 0) {
            return;
        }

        boolean checkpointDue = dirtyFingerprints - persistedFingerprints >= checkpointBatchSize;
        if (!done && !checkpointDue) {
            return;
        }

        getPersistence().save(state);
        persistedFingerprints = dirtyFingerprints;
        logger.debug("Persisted filesystem scan state: {} fingerprints", state.fingerprintsOrEmpty().size());
    }

    private ContentChange inspect(Path file, String relativePath) throws IOException {
        long size = Files.size(file);
        long modifiedMillis = Files.getLastModifiedTime(file).toMillis();
        FileFingerprint previous = state.fingerprintsOrEmpty().get(relativePath);

        // Fast path: mtime + size unchanged means "not changed" without reading
        // or hashing the file. This is what makes incremental scans O(stat).
        if (previous != null && previous.sameMetadata(size, modifiedMillis)) {
            return null;
        }

        byte[] bytes = Files.readAllBytes(file);
        String sha256 = computeSha256 ? sha256Hex(bytes) : null;
        FileFingerprint current = new FileFingerprint(size, modifiedMillis, sha256);

        boolean contentUnchanged = previous != null && sha256 != null && previous.sameContent(sha256);
        remember(relativePath, current);

        if (contentUnchanged) {
            return null;
        }
        return toChange(file, relativePath, bytes, size, modifiedMillis);
    }

    private void remember(String relativePath, FileFingerprint fingerprint) {
        state.fingerprintsOrEmpty().put(relativePath, fingerprint);
        dirtyFingerprints++;
    }

    private ContentChange toChange(Path file, String relativePath, byte[] bytes, long size, long modifiedMillis) {
        String fileName = file.getFileName().toString();
        String extension = extension(fileName);

        SourceDocument document = SourceDocument.builder()
                .id(stableId(relativePath))
                .field(Field.SOURCE_ID, rootId)
                .field(Field.SOURCE_URI, sourceUri(relativePath))
                .field(Field.MIME_TYPE, mimeType(extension))
                .field(Field.FILE_EXTENSION, extension)
                .field(Field.CONTENT_BYTES, bytes)
                .field(Field.CONTENT_SIZE, size)
                .field(Field.MODIFY_DATETIME, Instant.ofEpochMilli(modifiedMillis))
                .field(Field.TITLE, title(fileName))
                // Local-only provenance: the current sink does not transmit
                // VERSION_SERIES_ID; slice 4 derives the series from SourceIdentity.
                .vsId(rootId + ":" + relativePath)
                .field(Field.IS_CURRENT_VERSION, true)
                .build();

        return ContentChange.builder()
                .action(ChangeOp.REPLACE)
                .document(document)
                .build();
    }

    private Path nextFile() {
        while (true) {
            if (fileIterator != null && fileIterator.hasNext()) {
                return fileIterator.next();
            }
            fileIterator = null;

            Path directory = directories.poll();
            if (directory == null) {
                return null;
            }
            fileIterator = listFiles(directory).iterator();
        }
    }

    private List<Path> listFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));

            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) && !followSymlinks) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    if (shouldDescend(entry)) {
                        directories.add(entry);
                    }
                } else if (Files.isRegularFile(entry) && isIncluded(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException | SecurityException e) {
            logger.warn("Cannot list directory {}: {}", directory, e.getMessage());
        }
        return files;
    }

    private boolean shouldDescend(Path directory) {
        if (!withinDepth(directory)) {
            return false;
        }
        if (!followSymlinks) {
            return !Files.isSymbolicLink(directory);
        }
        try {
            return visitedRealDirectories.add(directory.toRealPath());
        } catch (IOException e) {
            logger.warn("Cannot resolve directory {}: {}", directory, e.getMessage());
            return false;
        }
    }

    private boolean withinDepth(Path directory) {
        return maxDepth == Integer.MAX_VALUE
                || root.relativize(directory).getNameCount() < maxDepth;
    }

    private boolean isIncluded(Path file) {
        Path relative = root.relativize(file);
        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        for (PathMatcher matcher : rootMatchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    private void compileIncludes(List<String> include) {
        includeMatchers.clear();
        rootMatchers.clear();
        if (include == null || include.isEmpty()) {
            return;
        }
        FileSystem fileSystem = FileSystems.getDefault();
        for (String pattern : include) {
            if (StringUtils.isBlank(pattern)) {
                continue;
            }
            includeMatchers.add(fileSystem.getPathMatcher("glob:" + pattern));
            // Java globs require the separator in "**/*.pdf"; also compile the
            // stripped form so root-level files match the same pattern.
            if (pattern.startsWith("**/")) {
                rootMatchers.add(fileSystem.getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
    }

    private String relativePath(Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private String sourceUri(String relativePath) {
        String encodedPath = Arrays.stream(relativePath.split("/"))
                .map(FileSystemSourcePlugin::encodeSegment)
                .collect(Collectors.joining("/"));
        return URI_SCHEME + encodeSegment(rootId) + "/" + encodedPath;
    }

    private String stableId(String relativePath) {
        return uuidV5(ID_NAMESPACE, "lucentrix:filesystem:" + rootId + ":" + relativePath).toString();
    }

    private Cursor loadCursor(String name) {
        Cursor.SuffixCursor stored = getPersistence().load(name, Cursor.SuffixCursor.class);
        if (stored != null) {
            return Cursor.SuffixCursor.toCursor(stored);
        }
        Cursor created = new Cursor(name);
        getPersistence().save(Cursor.SuffixCursor.toSuffixCursor(created));
        return created;
    }

    private FileScanState loadState(String name) {
        String stateId = stateId(name);
        FileScanState loaded = getPersistence().load(stateId, FileScanState.class);
        if (loaded == null) {
            loaded = new FileScanState();
            loaded.setId(stateId);
        }
        loaded.fingerprintsOrEmpty();
        return loaded;
    }

    private static String stateId(String name) {
        return name + "-fs-state";
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }

    private static String title(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String mimeType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "epub" -> "application/epub+zip";
            default -> DEFAULT_MIME_TYPE;
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute SHA-256", e);
        }
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] namespaceBytes = new byte[16];
            long msb = namespace.getMostSignificantBits();
            long lsb = namespace.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) {
                namespaceBytes[i] = (byte) (msb & 0xff);
                msb >>= 8;
            }
            for (int i = 15; i >= 8; i--) {
                namespaceBytes[i] = (byte) (lsb & 0xff);
                lsb >>= 8;
            }

            byte[] combined = new byte[namespaceBytes.length + nameBytes.length];
            System.arraycopy(namespaceBytes, 0, combined, 0, namespaceBytes.length);
            System.arraycopy(nameBytes, 0, combined, namespaceBytes.length, nameBytes.length);

            byte[] hash = MessageDigest.getInstance("SHA-1").digest(combined);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);

            long msbOut = 0;
            long lsbOut = 0;
            for (int i = 0; i < 8; i++) {
                msbOut = (msbOut << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsbOut = (lsbOut << 8) | (hash[i] & 0xff);
            }
            return new UUID(msbOut, lsbOut);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate UUIDv5", e);
        }
    }
}
