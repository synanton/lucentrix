package org.lucentrix.ingest.persistence.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lucentrix.ingest.metadata.Cursor;
import org.lucentrix.ingest.metadata.FieldObjectMap;
import org.lucentrix.ingest.metadata.field.Field;
import org.lucentrix.ingest.metadata.field.FieldType;
import org.lucentrix.ingest.runtime.persistence.FileJsonPersistence;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


public class FileJsonPersistenceTest {

    @TempDir
    File tempDir;

    @Test
    public void saveWritesAtomicallyAndLeavesNoTemporaryFile() throws Exception {
        FileJsonPersistence persistence = new FileJsonPersistence(tempDir.toPath());
        String cursorName = "atomic";
        Cursor original = Cursor.builder().id(cursorName)
                .fields(FieldObjectMap.builder()
                        .field(Field.CLASS_NAME, "Cursor")
                        .field(Field.CREATE_DATETIME, Instant.now())
                        .field(Field.MODIFY_DATETIME, Instant.now())
                        .field(Field.CONTENT, "first")
                        .build()).build();

        persistence.save(Cursor.SuffixCursor.toSuffixCursor(original));

        try (Stream<Path> files = Files.list(tempDir.toPath())) {
            assertEquals(1, files.count(), "save must not leave a temporary file behind");
        }
        Cursor restored = Cursor.SuffixCursor.toCursor(
                persistence.load(cursorName, Cursor.SuffixCursor.class));
        assertEquals(original, restored);
    }

    @Test
    public void testCursorPersistence() {
        FileJsonPersistence persistence = new FileJsonPersistence(tempDir.toPath());

        assertTrue(this.tempDir.isDirectory());

        String cursorName = "cursor";
        Cursor original = Cursor.builder().id(cursorName)
                .fields(FieldObjectMap.builder()
                        .field(Field.CLASS_NAME, "Cursor")
                        .field(Field.CREATE_DATETIME, Instant.now())
                        .field(Field.MODIFY_DATETIME, Instant.now())
                        .field(Field.of("offset", FieldType.LONG), 123L)
                        .field(Field.CONTENT, "234399%$(*#$767@!#@#")
                        .build()).build();
        Cursor.SuffixCursor suffixCursor = Cursor.SuffixCursor.toSuffixCursor(original);

        persistence.save(suffixCursor);

        File jsonFile = new File(tempDir.getAbsolutePath() + File.separator + cursorName + ".json");
        assertTrue(jsonFile.exists());

        suffixCursor = persistence.load(cursorName, Cursor.SuffixCursor.class);
        Cursor restored = Cursor.SuffixCursor.toCursor(suffixCursor);

        assertNotNull(restored);

        assertEquals(original, restored);
    }
}
