package org.lucentrix.ingest.plugin.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lucentrix.ingest.ChangePage;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.encrypt.PasswordEncryptor;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;
import org.lucentrix.ingest.runtime.plugin.SourceContext;
import org.lucentrix.ingest.runtime.persistence.FileJsonPersistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        FileSystemSourceConfig.Settings settings = FileSystemSourceConfig.Settings.builder()
                .name("fs-test")
                .root(root.toString())
                .rootId("lib")
                .include(include)
                .computeSha256(true)
                .checkpointBatchSize(1)
                .maxDepth(64)
                .followSymlinks(false)
                .build();

        ConfigEnv configEnv = ConfigEnv.builder()
                .encryptor(new PasswordEncryptor())
                .build();

        FileSystemSourceConfig config = new FileSystemSourceConfig(settings, configEnv);

        SourceContext context = SourceContext.builder()
                .pluginId("filesystem-source-plugin")
                .persistence(new FileJsonPersistence(tempDir.resolve("storage")))
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
