package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownParserTest {

    final MarkdownParser parser = new MarkdownParser();

    private ByteArrayInputStream stream(String md) {
        return new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void supportedExtensionIsMd() {
        assertEquals(".md", parser.supportedExtension());
    }

    @Test
    void supportedMimeTypeIsTextMarkdown() {
        assertEquals("text/markdown", parser.supportedMimeType());
    }

    @Test
    void extractsTitleFromH1() throws Exception {
        String md = """
                # Getting Started

                This is the introduction.
                """;
        ParsedDocument doc = parser.parse(stream(md), "guide.md", "d1");
        assertEquals("Getting Started", doc.getTitle());
    }

    @Test
    void fallsBackToFilenameWhenNoH1() throws Exception {
        String md = """
                ## Section A

                Some content here.
                """;
        ParsedDocument doc = parser.parse(stream(md), "tutorial.md", "d1");
        assertEquals("tutorial", doc.getTitle());
    }

    @Test
    void parsesMultipleSections() throws Exception {
        String md = """
                # Introduction

                Intro content.

                ## Setup

                Setup steps here.

                ## Usage

                How to use it.
                """;
        ParsedDocument doc = parser.parse(stream(md), "readme.md", "d1");
        assertTrue(doc.getSections().size() >= 3);
    }

    @Test
    void sectionContainsText() throws Exception {
        String md = """
                # Chapter One

                The quick brown fox jumps over the lazy dog.
                """;
        ParsedDocument doc = parser.parse(stream(md), "book.md", "d1");
        boolean found = doc.getSections().stream()
                .anyMatch(s -> s.getContent().contains("quick brown fox"));
        assertTrue(found, "段落内容应包含正文文本");
    }

    @Test
    void fullTextIsNotEmpty() throws Exception {
        String md = """
                # Title

                Hello world content.
                """;
        ParsedDocument doc = parser.parse(stream(md), "f.md", "d1");
        assertFalse(doc.getText().isBlank());
        assertTrue(doc.getText().contains("Hello world"));
    }

    @Test
    void mimeTypeIsTextMarkdown() throws Exception {
        ParsedDocument doc = parser.parse(stream("# T\n\nContent."), "f.md", "d1");
        assertEquals("text/markdown", doc.getMimeType());
    }

    @Test
    void docIdIsPreserved() throws Exception {
        ParsedDocument doc = parser.parse(stream("# T\n\nContent."), "f.md", "myDocId");
        assertEquals("myDocId", doc.getDocId());
    }

    @Test
    void metadataContainsSource() throws Exception {
        ParsedDocument doc = parser.parse(stream("# T\n\nContent."), "notes.md", "d1");
        assertEquals("notes.md", doc.getMetadata().get("source"));
    }

    @Test
    void sectionLevelMatchesHeadingLevel() throws Exception {
        String md = """
                # Level 1

                Content A.

                ## Level 2

                Content B.
                """;
        ParsedDocument doc = parser.parse(stream(md), "f.md", "d1");
        List<ParsedDocument.Section> sections = doc.getSections();
        assertTrue(sections.stream().anyMatch(s -> s.getLevel() == 1));
        assertTrue(sections.stream().anyMatch(s -> s.getLevel() == 2));
    }

    @Test
    void parsesChineseMarkdown() throws Exception {
        String md = """
                # 人工智能简介

                人工智能是计算机科学的重要分支。

                ## 应用场景

                广泛应用于自然语言处理、计算机视觉等领域。
                """;
        ParsedDocument doc = parser.parse(stream(md), "ai.md", "d1");
        assertEquals("人工智能简介", doc.getTitle());
        assertTrue(doc.getSections().size() >= 2);
    }
}
