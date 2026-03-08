package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB 文档解析器
 *
 * EPUB 本质是 ZIP 包；遍历其中的 .html/.xhtml 条目，
 * 每个文件生成一个 Section，用 Jsoup 提取正文文本。
 * 章节标题取自 h1、h2 或 title 标签。
 */
@Slf4j
public class EpubParser implements DocumentParser {

    @Override
    public String supportedMimeType() {
        return "application/epub+zip";
    }

    @Override
    public String supportedExtension() {
        return ".epub";
    }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();

        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().toLowerCase();
                if (!entryName.endsWith(".html") && !entryName.endsWith(".xhtml")) {
                    zip.closeEntry();
                    continue;
                }

                byte[] bytes = readEntry(zip);
                String html = new String(bytes, StandardCharsets.UTF_8);
                Document doc = Jsoup.parse(html);

                // 移除无关标签
                doc.select("script, style, nav").remove();

                // 提取章节标题：优先 h1，其次 h2，最后 title
                String heading = doc.select("h1").text();
                if (heading.isBlank()) heading = doc.select("h2").text();
                if (heading.isBlank()) heading = doc.title();
                if (heading.isBlank()) heading = entry.getName();

                String bodyText = doc.body() != null ? doc.body().text().strip() : "";
                if (bodyText.isBlank()) {
                    zip.closeEntry();
                    continue;
                }

                sections.add(ParsedDocument.Section.builder()
                        .heading(heading)
                        .path(heading)
                        .content(bodyText)
                        .level(1)
                        .build());
                fullText.append(heading).append("\n").append(bodyText).append("\n");

                zip.closeEntry();
            }
        }

        String title = sections.isEmpty() ? stripExtension(filename)
                : sections.get(0).getHeading();

        log.debug("EPUB 解析完成: docId={}, sections={}", docId, sections.size());

        return ParsedDocument.builder()
                .docId(docId)
                .title(title)
                .text(fullText.toString().strip())
                .sections(sections)
                .metadata(Map.of("source", filename))
                .mimeType(supportedMimeType())
                .build();
    }

    /**
     * 将当前 ZipEntry 内容读取到字节数组（不关闭 ZipInputStream）。
     */
    private byte[] readEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = zip.read(chunk)) != -1) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toByteArray();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
