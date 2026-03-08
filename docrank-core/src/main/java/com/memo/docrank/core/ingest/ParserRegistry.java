package com.memo.docrank.core.ingest;

import com.memo.docrank.core.ingest.parser.*;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 解析器注册中心：根据文件扩展名自动路由到对应的 DocumentParser
 *
 * 支持格式：
 *   .pdf   — PDFBox
 *   .md    — Flexmark
 *   .html  — Jsoup
 *   .docx  — Apache POI
 *   .json  — Jackson
 *   .txt   — Plain text
 *   .xlsx  — Apache POI XSSFWorkbook
 *   .pptx  — Apache POI XMLSlideShow
 *   .epub  — ZipInputStream + Jsoup
 *   .csv   — 原生 Java
 */
@Slf4j
public class ParserRegistry {

    /** 扩展名 → 解析器 */
    private final Map<String, DocumentParser> byExtension = new LinkedHashMap<>();
    /** MIME 类型 → 解析器 */
    private final Map<String, DocumentParser> byMime = new LinkedHashMap<>();

    public ParserRegistry() {
        register(new PdfParser());
        register(new MarkdownParser());
        register(new HtmlParser());
        register(new WordParser());
        register(new JsonParser());
        register(new TextParser());
        register(new ExcelParser());
        register(new PowerPointParser());
        register(new EpubParser());
        register(new CsvParser());
    }

    private void register(DocumentParser parser) {
        byExtension.put(parser.supportedExtension().toLowerCase(), parser);
        byMime.put(parser.supportedMimeType().toLowerCase(), parser);
    }

    /**
     * 按文件名解析
     */
    public ParsedDocument parse(InputStream input, String filename) throws Exception {
        String docId = UUID.randomUUID().toString();
        return parse(input, filename, docId);
    }

    /**
     * 按文件名 + docId 解析
     */
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        DocumentParser parser = resolveByFilename(filename);
        log.info("解析文件: {} → 使用 {}", filename, parser.getClass().getSimpleName());
        return parser.parse(input, filename, docId);
    }

    /**
     * 按 MIME 类型解析
     */
    public ParsedDocument parseByMime(InputStream input, String filename,
                                       String mimeType, String docId) throws Exception {
        DocumentParser parser = byMime.getOrDefault(
                mimeType.toLowerCase(),
                byExtension.getOrDefault(".txt", new TextParser()));
        log.info("解析文件: {} (mime={}) → 使用 {}", filename, mimeType, parser.getClass().getSimpleName());
        return parser.parse(input, filename, docId);
    }

    public boolean supports(String filename) {
        String ext = extractExtension(filename);
        return byExtension.containsKey(ext);
    }

    private DocumentParser resolveByFilename(String filename) {
        String ext = extractExtension(filename);
        return byExtension.getOrDefault(ext,
                byExtension.getOrDefault(".txt", new TextParser()));
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
