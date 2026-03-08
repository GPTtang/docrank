package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JsonParserTest {

    final JsonParser parser = new JsonParser();

    private ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void supportedExtensionIsJson() {
        assertEquals(".json", parser.supportedExtension());
    }

    @Test
    void standardFormatExtractsTitleAndContent() throws Exception {
        String json = """
                {"title": "Spring Boot Guide", "content": "Spring Boot simplifies Java development."}
                """;
        ParsedDocument doc = parser.parse(stream(json), "guide.json", "d1");

        assertEquals("Spring Boot Guide", doc.getTitle());
        assertEquals("Spring Boot simplifies Java development.", doc.getText());
    }

    @Test
    void standardFormatProducesOneSection() throws Exception {
        String json = """
                {"title": "My Title", "content": "My content here."}
                """;
        ParsedDocument doc = parser.parse(stream(json), "f.json", "d1");
        assertEquals(1, doc.getSections().size());
        assertEquals("My Title", doc.getSections().get(0).getHeading());
    }

    @Test
    void arrayFormatProducesMultipleSections() throws Exception {
        String json = """
                [
                  {"title": "Chapter 1", "content": "First chapter content."},
                  {"title": "Chapter 2", "content": "Second chapter content."}
                ]
                """;
        ParsedDocument doc = parser.parse(stream(json), "book.json", "d1");
        assertEquals(2, doc.getSections().size());
        assertEquals("Chapter 1", doc.getSections().get(0).getHeading());
        assertEquals("Chapter 2", doc.getSections().get(1).getHeading());
    }

    @Test
    void arrayFormatTitleIsFilename() throws Exception {
        String json = """
                [{"title": "S1", "content": "c1"}]
                """;
        ParsedDocument doc = parser.parse(stream(json), "chapters.json", "d1");
        assertEquals("chapters", doc.getTitle());
    }

    @Test
    void arbitraryJsonExtractsStringLeaves() throws Exception {
        String json = """
                {"name": "Alice", "description": "A developer", "score": 42}
                """;
        ParsedDocument doc = parser.parse(stream(json), "data.json", "d1");
        assertNotNull(doc.getText());
        assertTrue(doc.getText().contains("Alice"));
        assertTrue(doc.getText().contains("A developer"));
    }

    @Test
    void metadataContainsAuthorIfPresent() throws Exception {
        String json = """
                {"title": "T", "content": "C", "author": "Bob"}
                """;
        ParsedDocument doc = parser.parse(stream(json), "f.json", "d1");
        assertEquals("Bob", doc.getMetadata().get("author"));
    }

    @Test
    void metadataAlwaysContainsSource() throws Exception {
        String json = """
                {"title": "T", "content": "C"}
                """;
        ParsedDocument doc = parser.parse(stream(json), "test.json", "d1");
        assertEquals("test.json", doc.getMetadata().get("source"));
    }

    @Test
    void mimeTypeIsApplicationJson() throws Exception {
        ParsedDocument doc = parser.parse(stream("{\"title\":\"T\",\"content\":\"C\"}"), "f.json", "d1");
        assertEquals("application/json", doc.getMimeType());
    }

    @Test
    void docIdIsPreserved() throws Exception {
        ParsedDocument doc = parser.parse(stream("{\"title\":\"T\",\"content\":\"C\"}"), "f.json", "myId");
        assertEquals("myId", doc.getDocId());
    }
}
