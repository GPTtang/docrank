package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
public class TextParser implements DocumentParser {

    @Override
    public String supportedMimeType() { return "text/plain"; }

    @Override
    public String supportedExtension() { return ".txt"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        String title = stripExtension(filename);

        log.debug("Text 解析完成: docId={}, chars={}", docId, text.length());

        return ParsedDocument.builder()
                .docId(docId)
                .title(title)
                .text(text)
                .sections(List.of(ParsedDocument.Section.builder()
                        .heading(title).path(title).content(text).level(1).build()))
                .metadata(Map.of("source", filename))
                .mimeType(supportedMimeType())
                .build();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
