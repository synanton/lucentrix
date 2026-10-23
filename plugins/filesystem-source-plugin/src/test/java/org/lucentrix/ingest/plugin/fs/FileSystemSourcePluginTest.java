package org.lucentrix.ingest.plugin.fs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lucentrix.ingest.ChangeOp;
import org.lucentrix.ingest.ChangePage;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.encrypt.PasswordEncryptor;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;
import org.lucentrix.ingest.runtime.plugin.SourceContext;
import org.lucentrix.ingest.runtime.persistence.FileJsonPersistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileSystemSourcePluginTest {

    @TempDir
    Path tempDir;

    @Test
    public void discoversPdfAndEpubRecursivelyAndSkipsOtherFiles() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("programming/effective-java/effective-java.pdf"), "pdf-content");
        write(root.resolve("programming/clean-architecture.epub"), "epub-content");
        write(root.resolve("programming/notes.txt"), "not included");
        write(root.resolve("README.md"), "not included");

        List<ContentChange> events = drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub")));

        assertEquals(2, events.size());
        Map<String, SourceDocument> byTitle = events.stream()
                .map(ContentChange::getDocument)
                .collect(Collectors.toMap(SourceDocument::getTitle, Function.identity()));

        SourceDocument pdf = byTitle.get("effective-java");
        assertEquals("application/pdf", pdf.getMimeType());
        assertEquals("pdf", pdf.get(Field.FILE_EXTENSION));
        assertEquals("lib", pdf.get(Field.SOURCE_ID));
        assertEquals("lucentrix://filesystem/lib/programming/effective-java/effective-java.pdf",
                pdf.get(Field.SOURCE_URI));
        assertEquals(11L, pdf.get(Field.CONTENT_SIZE));
        assertArrayEquals("pdf-content".getBytes(StandardCharsets.UTF_8), pdf.get(Field.CONTENT_BYTES));

        SourceDocument epub = byTitle.get("clean-architecture");
        assertEquals("application/epub+zip", epub.getMimeType());
        assertEquals("epub", epub.get(Field.FILE_EXTENSION));
        assertArrayEquals("epub-content".getBytes(StandardCharsets.UTF_8), epub.get(Field.CONTENT_BYTES));
    }

    @Test
    public void rootLevelFilesMatchDoubleStarPatterns() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("My Book.pdf"), "content");

        List<ContentChange> events = drain(newPlugin(root, 1000, List.of("**/*.pdf")));

        assertEquals(1, events.size());
        assertEquals("lucentrix://filesystem/lib/My%20Book.pdf",
                events.get(0).getDocument().get(Field.SOURCE_URI));
    }

    @Test
    public void secondScanOfUnchangedTreeEmitsNothing() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("a.pdf"), "content-a");
        write(root.resolve("nested/b.epub"), "content-b");

        drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub")));
        List<ContentChange> secondScan = drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub")));

        assertTrue(secondScan.isEmpty(), "unchanged tree must not emit again");
    }

    @Test
    public void changedFileEmitsNewVersion() throws Exception {
        Path root = libraryRoot();
        Path book = root.resolve("a.pdf");
        write(book, "short");

        assertEquals(1, drain(newPlugin(root, 1000, List.of("**/*.pdf"))).size());

        write(book, "much longer content");
        Files.setLastModifiedTime(book, FileTime.fromMillis(System.currentTimeMillis() + 5_000L));

        List<ContentChange> changed = drain(newPlugin(root, 1000, List.of("**/*.pdf")));

        assertEquals(1, changed.size());
        assertEquals("a", changed.get(0).getDocument().getTitle());
        assertEquals(19L, changed.get(0).getDocument().get(Field.CONTENT_SIZE));
    }

    @Test
    public void pageSizeBoundsEachPage() throws Exception {
        Path root = libraryRoot();
        for (int i = 0; i < 5; i++) {
            write(root.resolve("book-" + i + ".pdf"), "content-" + i);
        }

        FileSystemSourcePlugin plugin = newPlugin(root, 2, List.of("**/*.pdf"));
        plugin.start();
        List<Integer> pageSizes = new ArrayList<>();
        int total = 0;

        while (plugin.hasNext()) {
            ChangePage page = plugin.next();
            pageSizes.add(page.getItems().size());
            total += page.getItems().size();
            plugin.save();
        }

        assertEquals(5, total);
        assertTrue(pageSizes.size() >= 3);
        assertTrue(pageSizes.stream().allMatch(size -> size <= 2), "page size exceeded: " + pageSizes);
    }

    @Test
    public void missingRootFailsFast() {
        FileSystemSourcePlugin plugin = newPlugin(tempDir.resolve("does-not-exist"), 1000, List.of("**/*.pdf"));

        assertThrows(IllegalArgumentException.class, plugin::start);
    }

    @Test
    public void metadataOnlyChangeWithIdenticalContentEmitsNothing() throws Exception {
        Path root = libraryRoot();
        Path book = root.resolve("a.pdf");
        write(book, "same-content");

        drain(newPlugin(root, 1000, List.of("**/*.pdf")));

        // Same bytes, new mtime: the mtime+size fast path no longer applies, so
        // the digest path must recognize the content as unchanged.
        Files.setLastModifiedTime(book, FileTime.fromMillis(System.currentTimeMillis() + 5_000L));

        assertTrue(drain(newPlugin(root, 1000, List.of("**/*.pdf"))).isEmpty());
    }

    @Test
    public void traversalOrderIsDeterministicAcrossFreshScans() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("z.pdf"), "z");
        write(root.resolve("a/inner.pdf"), "inner");
        write(root.resolve("m.pdf"), "m");

        List<String> first = drain(newPlugin(root, 1000, List.of("**/*.pdf"), false, "order-a"))
                .stream().map(change -> change.getDocument().get(Field.SOURCE_URI)).toList();
        List<String> second = drain(newPlugin(root, 1000, List.of("**/*.pdf"), false, "order-b"))
                .stream().map(change -> change.getDocument().get(Field.SOURCE_URI)).toList();

        assertEquals(first, second);
    }

    @Test
    public void symlinkCycleTerminatesWhenFollowSymlinksEnabled() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("a.pdf"), "content");
        try {
            Files.createSymbolicLink(root.resolve("loop"), root);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlinks are not supported here: " + e.getMessage());
        }

        List<ContentChange> events = drain(newPlugin(root, 1000, List.of("**/*.pdf"), true, "symlink"));

        assertEquals(1, events.size());
        assertEquals("a", events.get(0).getDocument().getTitle());
    }

    @Test
    public void completeScanEmitsDeleteForMissingFileAndClearsItsFingerprint() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("a.pdf"), "content-a");
        write(root.resolve("nested/b.epub"), "content-b");

        assertEquals(2, drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub"))).size());

        Files.delete(root.resolve("nested/b.epub"));
        List<ContentChange> changes = drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub")));

        assertEquals(1, changes.size());
        assertEquals(ChangeOp.DELETE, changes.get(0).getAction());
        assertEquals("lucentrix://filesystem/lib/nested/b.epub",
                changes.get(0).getDocument().get(Field.SOURCE_URI));
        assertEquals("lib:nested/b.epub", changes.get(0).getDocument().getVersionSeriesId());

        assertTrue(drain(newPlugin(root, 1000, List.of("**/*.pdf", "**/*.epub"))).isEmpty());
    }

    @Test
    public void incompleteScanDoesNotReconcileDeletes() throws Exception {
        Path root = libraryRoot();
        Path locked = root.resolve("locked");
        write(locked.resolve("a.pdf"), "content-a");
        write(locked.resolve("b.pdf"), "content-b");
        write(root.resolve("outside.pdf"), "content-outside");

        assertEquals(3, drain(newPlugin(root, 1000, List.of("**/*.pdf"))).size());

        Files.delete(locked.resolve("a.pdf"));
        Files.delete(root.resolve("outside.pdf"));
        Assumptions.assumeTrue(
                Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX permissions are required for this test");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(locked), "permission enforcement unavailable");
        try {
            assertTrue(drain(newPlugin(root, 1000, List.of("**/*.pdf"))).isEmpty(),
                    "an incomplete scan must not emit deletions");
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }

        List<ContentChange> recovered = drain(newPlugin(root, 1000, List.of("**/*.pdf")));
        assertEquals(2, recovered.size());
        assertEquals(Set.of(
                        "lucentrix://filesystem/lib/locked/a.pdf",
                        "lucentrix://filesystem/lib/outside.pdf"),
                recovered.stream()
                        .map(change -> change.getDocument().get(Field.SOURCE_URI))
                        .collect(Collectors.toSet()));
        assertTrue(recovered.stream().allMatch(change -> change.getAction() == ChangeOp.DELETE));
    }

    @Test
    public void reappearedFileIsNotDeletedWhenEmissionResumes() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("a.pdf"), "content-a");
        write(root.resolve("b.pdf"), "content-b");
        drain(newPlugin(root, 1000, List.of("**/*.pdf")));

        Files.delete(root.resolve("a.pdf"));
        Files.delete(root.resolve("b.pdf"));

        FileSystemSourcePlugin plugin = newPlugin(root, 1, List.of("**/*.pdf"));
        plugin.start();
        ChangePage firstPage = plugin.next();
        plugin.save();
        assertEquals(1, firstPage.getItems().size());
        assertEquals(ChangeOp.DELETE, firstPage.getItems().get(0).getAction());

        String emitted = firstPage.getItems().get(0).getDocument().get(Field.SOURCE_URI);
        String pending = emitted.endsWith("/a.pdf") ? "b.pdf" : "a.pdf";
        write(root.resolve(pending), "content-" + pending.charAt(0));

        List<ContentChange> remaining = new ArrayList<>();
        while (plugin.hasNext()) {
            ChangePage page = plugin.next();
            remaining.addAll(page.getItems());
            plugin.save();
        }

        assertTrue(remaining.isEmpty(), "a file that reappeared must not be deleted");
    }

    @Test
    public void interruptedAfterDeleteEmissionBeforeSave_reemitsWithoutOrphanedFingerprint() throws Exception {
        Path root = libraryRoot();
        write(root.resolve("a.pdf"), "content-a");
        drain(newPlugin(root, 1000, List.of("**/*.pdf")));

        Files.delete(root.resolve("a.pdf"));

        FileSystemSourcePlugin interrupted = newPlugin(root, 1, List.of("**/*.pdf"));
        interrupted.start();
        ChangePage emitted = interrupted.next();
        assertEquals(1, emitted.getItems().size());
        assertEquals(ChangeOp.DELETE, emitted.getItems().get(0).getAction());
        // Simulates process death after the sink push and before source.save():
        // the emitted deletion must not be persisted as complete.

        List<ContentChange> resumed = drain(newPlugin(root, 1, List.of("**/*.pdf")));

        assertEquals(1, resumed.size());
        assertEquals(ChangeOp.DELETE, resumed.get(0).getAction());
        FileScanState state = new FileJsonPersistence(tempDir.resolve("storage"))
                .load("fs-test-fs-state", FileScanState.class);
        assertTrue(state.fingerprintsOrEmpty().isEmpty(), "recovery must not leave orphaned fingerprints");
        assertTrue(drain(newPlugin(root, 1, List.of("**/*.pdf"))).isEmpty());
    }

    private Path libraryRoot() throws Exception {
        Path root = tempDir.resolve("library");
        Files.createDirectories(root);
        return root;
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private FileSystemSourcePlugin newPlugin(Path root, int pageSize, List<String> include) {
        return newPlugin(root, pageSize, include, false, "storage");
    }

    private FileSystemSourcePlugin newPlugin(Path root, int pageSize, List<String> include,
                                             boolean followSymlinks, String storageName) {
        FileSystemSourceConfig.Settings settings = FileSystemSourceConfig.Settings.builder()
                .name("fs-test")
                .root(root.toString())
                .rootId("lib")
                .include(include)
                .computeSha256(true)
                .checkpointBatchSize(1)
                .maxDepth(64)
                .followSymlinks(followSymlinks)
                .build();

        ConfigEnv configEnv = ConfigEnv.builder()
                .encryptor(new PasswordEncryptor())
                .build();

        FileSystemSourceConfig config = new FileSystemSourceConfig(settings, configEnv);

        SourceContext context = SourceContext.builder()
                .pluginId("filesystem-source-plugin")
                .persistence(new FileJsonPersistence(tempDir.resolve(storageName)))
                .pageSize(pageSize)
                .build();

        return new FileSystemSourcePlugin(config, context);
    }

    private List<ContentChange> drain(FileSystemSourcePlugin plugin) {
        plugin.start();
        List<ContentChange> events = new ArrayList<>();
        while (plugin.hasNext()) {
            ChangePage page = plugin.next();
            events.addAll(page.getItems());
            plugin.save();
        }
        return events;
    }
}
