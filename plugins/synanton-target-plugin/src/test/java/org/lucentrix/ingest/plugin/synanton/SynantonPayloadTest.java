package org.lucentrix.ingest.plugin.synanton;

import org.junit.jupiter.api.Test;
import org.lucentrix.ingest.ChangeOp;
import org.lucentrix.ingest.ContentChange;
import org.lucentrix.ingest.SourceDocument;
import org.lucentrix.ingest.metadata.field.Field;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynantonPayloadTest {

    @Test
    void prefersContentBytes() {
        SourceDocument document = SourceDocument.builder()
                .field(Field.ID, "doc-1")
                .field(Field.CONTENT_BYTES, "hello".getBytes(StandardCharsets.UTF_8))
                .field(Field.CONTENT, "ignored")
                .build();
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), SynantonPayload.bytes(document));
        assertEquals("application/octet-stream", SynantonPayload.mimeType(document));
    }

    @Test
    void usesSourceUriWhenPresent() {
        SourceDocument document = SourceDocument.builder()
                .field(Field.ID, "doc-1")
                .field(Field.SOURCE_URI, "https://example.com/a")
                .build();
        assertEquals("https://example.com/a", SynantonPayload.sourceUri(document));
    }

    @Test
    void flagsDeletes() {
        ContentChange change = ContentChange.builder()
                .action(ChangeOp.DELETE)
                .document(SourceDocument.builder().field(Field.ID, "x").build())
                .build();
        assertTrue(SynantonPayload.isDelete(change));
    }
    @Test
    void derivesResourceIdFromVersionSeries() {
        SourceDocument document = SourceDocument.builder()
                .field(Field.ID, "doc-1")
                .field(Field.SOURCE_ID, "books")
                .vsId("books:a/b c.pdf")
                .build();
        assertEquals("a/b c.pdf", SynantonPayload.resourceId(document));
    }

    @Test
    void derivesResourceIdFromEncodedSourceUri() {
        SourceDocument document = SourceDocument.builder()
                .field(Field.ID, "doc-1")
                .field(Field.SOURCE_URI, "lucentrix://filesystem/books/a/b%20c.pdf")
                .build();
        assertEquals("a/b c.pdf", SynantonPayload.resourceId(document));
    }

    @Test
    void rejectsTraversalInResourceId() {
        SourceDocument document = SourceDocument.builder()
                .field(Field.ID, "doc-1")
                .field(Field.SOURCE_ID, "books")
                .vsId("books:../etc/passwd")
                .build();
        assertThrows(IllegalArgumentException.class, () -> SynantonPayload.resourceId(document));
    }
}
