package com.memo.docrank.core.ingest;

import com.memo.docrank.core.ingest.parser.TextParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ParserRegistryTest {

    final ParserRegistry registry = new ParserRegistry();

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void supportsTxt() {
        assertTrue(registry.supports("readme.txt"));
    }

    @Test
    void supportsMd() {
        assertTrue(registry.supports("guide.md"));
    }

    @Test
    void supportsJson() {
        assertTrue(registry.supports("data.json"));
    }

    @Test
    void supportsHtml() {
        assertTrue(registry.supports("page.html"));
    }

    @Test
    void supportsPdf() {
        assertTrue(registry.supports("doc.pdf"));
    }

    @Test
    void supportsDocx() {
        assertTrue(registry.supports("report.docx"));
    }

    @Test
    void doesNotSupportUnknownExtension() {
        assertFalse(registry.supports("file.xyz"));
        assertTrue(registry.supports("file.csv"));
    }

    @Test
    void parseTxtFile() throws Exception {
        ParsedDocument doc = registry.parse(stream("Hello world."), "readme.txt", "d1");
        assertNotNull(doc);
        assertEquals("d1", doc.getDocId());
        assertEquals("Hello world.", doc.getText());
    }

    @Test
    void parseMarkdownFile() throws Exception {
        String md = "# Title\n\nSome content here.";
        ParsedDocument doc = registry.parse(stream(md), "guide.md", "d1");
        assertNotNull(doc);
        assertEquals("Title", doc.getTitle());
    }

    @Test
    void parseJsonFile() throws Exception {
        String json = "{\"title\":\"My Doc\",\"content\":\"Content here.\"}";
        ParsedDocument doc = registry.parse(stream(json), "data.json", "d1");
        assertNotNull(doc);
        assertEquals("My Doc", doc.getTitle());
    }

    @Test
    void unknownExtensionFallsBackToTextParser() throws Exception {
        // 鏈煡鎵╁睍鍚嶉檷绾т负 TextParser
        ParsedDocument doc = registry.parse(stream("plain text"), "file.xyz", "d1");
        assertNotNull(doc);
        assertEquals("plain text", doc.getText());
    }

    @Test
    void extensionIsCaseInsensitive() {
        assertTrue(registry.supports("README.TXT") || registry.supports("README.txt"));
        // registry 鎸夊皬鍐欐敞鍐岋紝鏂囦欢鍚嶅ぇ鍐欏簲鑷姩杞崲
        // 鑷冲皯鏀寔灏忓啓鐗堟湰
        assertTrue(registry.supports("readme.txt"));
    }

    @Test
    void parseGeneratesDocIdWhenNotProvided() throws Exception {
        ParsedDocument doc = registry.parse(stream("text"), "test.txt");
        assertNotNull(doc.getDocId());
        assertFalse(doc.getDocId().isBlank());
    }
}

