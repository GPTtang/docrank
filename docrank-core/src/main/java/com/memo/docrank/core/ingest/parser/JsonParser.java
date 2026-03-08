package com.memo.docrank.core.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

/**
 * JSON 文档解析器
 *
 * 支持两种格式：
 * 1. {"title": "...", "content": "..."}                    — 标准格式
 * 2. [{"title": "...", "content": "..."}, ...]             — 数组格式，每个元素为一个 Section
 * 3. 任意 JSON — 递归提取所有字符串叶节点拼接为文本
 */
@Slf4j
public class JsonParser implements DocumentParser {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String supportedMimeType() { return "application/json"; }

    @Override
    public String supportedExtension() { return ".json"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        JsonNode root = mapper.readTree(input);

        String title;
        String fullText;
        List<ParsedDocument.Section> sections = new ArrayList<>();

        if (root.isArray()) {
            // 数组格式：每个元素作为一个 Section
            title = stripExtension(filename);
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (JsonNode item : root) {
                String heading = item.path("title").asText("Section " + (++idx));
                String content = item.has("content")
                        ? item.path("content").asText()
                        : extractText(item);
                sb.append(content).append("\n");
                sections.add(ParsedDocument.Section.builder()
                        .heading(heading).path(heading).content(content).level(1).build());
            }
            fullText = sb.toString();
        } else if (root.has("title") && root.has("content")) {
            // 标准格式
            title    = root.path("title").asText(stripExtension(filename));
            fullText = root.path("content").asText();
            sections.add(ParsedDocument.Section.builder()
                    .heading(title).path(title).content(fullText).level(1).build());
        } else {
            // 任意 JSON：递归提取文本
            title    = root.has("title") ? root.path("title").asText() : stripExtension(filename);
            fullText = extractText(root);
            sections.add(ParsedDocument.Section.builder()
                    .heading(title).path(title).content(fullText).level(1).build());
        }

        // 提取元数据
        Map<String, String> meta = new LinkedHashMap<>();
        if (root.has("author"))   meta.put("author",   root.path("author").asText());
        if (root.has("created"))  meta.put("created",  root.path("created").asText());
        if (root.has("language")) meta.put("language", root.path("language").asText());
        meta.put("source", filename);

        log.debug("JSON 解析完成: docId={}, sections={}", docId, sections.size());

        return ParsedDocument.builder()
                .docId(docId).title(title).text(fullText)
                .sections(sections).metadata(meta)
                .mimeType(supportedMimeType()).build();
    }

    /** 递归提取 JSON 中所有字符串叶节点 */
    private String extractText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        StringBuilder sb = new StringBuilder();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String val = extractText(e.getValue());
                if (!val.isBlank()) sb.append(val).append(" ");
            });
        } else if (node.isArray()) {
            node.forEach(child -> {
                String val = extractText(child);
                if (!val.isBlank()) sb.append(val).append(" ");
            });
        }
        return sb.toString().strip();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
