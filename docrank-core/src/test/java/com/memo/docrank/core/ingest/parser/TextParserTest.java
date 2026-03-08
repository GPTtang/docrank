package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TextParserTest {

    final TextParser parser = new TextParser();

    private ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void supportedExtensionIsTxt() {
        assertEquals(".txt", parser.supportedExtension());
    }

    @Test
    void supportedMimeTypeIsTextPlain() {
        assertEquals("text/plain", parser.supportedMimeType());
    }

    @Test
    void parsesPlainText() throws Exception {
        String content = "Hello world. This is a test document.";
        ParsedDocument doc = parser.parse(stream(content), "readme.txt", "doc1");

        assertEquals("doc1", doc.getDocId());
        assertEquals("readme", doc.getTitle());
        assertEquals(content, doc.getText());
        assertEquals("text/plain", doc.getMimeType());
    }

    @Test
    void titleStripsExtension() throws Exception {
        ParsedDocument doc = parser.parse(stream("content"), "my.document.txt", "d1");
        assertEquals("my.document", doc.getTitle());
    }

    @Test
    void producesOneSectionWithFullContent() throws Exception {
        String content = "Line one.\nLine two.\nLine three.";
        ParsedDocument doc = parser.parse(stream(content), "test.txt", "d1");

        assertEquals(1, doc.getSections().size());
        assertEquals(content, doc.getSections().get(0).getContent());
    }

    @Test
    void metadataContainsSourceFilename() throws Exception {
        ParsedDocument doc = parser.parse(stream("text"), "notes.txt", "d1");
        assertEquals("notes.txt", doc.getMetadata().get("source"));
    }

    @Test
    void parsesChineseText() throws Exception {
        String content = "这是一段中文文本内容，用于测试文本解析器。";
        ParsedDocument doc = parser.parse(stream(content), "中文.txt", "d1");
        assertEquals(content, doc.getText());
    }

    @Test
    void sectionHeadingMatchesTitle() throws Exception {
        ParsedDocument doc = parser.parse(stream("text"), "myfile.txt", "d1");
        assertEquals(doc.getTitle(), doc.getSections().get(0).getHeading());
    }
}
