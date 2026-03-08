package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class HtmlParser implements DocumentParser {

    @Override
    public String supportedMimeType() { return "text/html"; }

    @Override
    public String supportedExtension() { return ".html"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(raw);

        // 移除无用标签
        doc.select("script, style, nav, footer, header, aside, form, iframe").remove();

        String title = doc.title().isBlank()
                ? stripExtension(filename) : doc.title();

        // 按标题（h1~h4）切分段落
        List<ParsedDocument.Section> sections = new ArrayList<>();
        Elements headings = doc.select("h1, h2, h3, h4");

        if (headings.isEmpty()) {
            // 无标题结构，整体作为一个段落
            sections.add(ParsedDocument.Section.builder()
                    .heading(title).path(title)
                    .content(doc.body().text()).level(1).build());
        } else {
            for (Element h : headings) {
                int level = Integer.parseInt(h.tagName().substring(1));
                String heading = h.text();
                // 收集该标题后直到下一个同级标题的内容
                StringBuilder content = new StringBuilder();
                for (Element sib = h.nextElementSibling();
                     sib != null && !sib.tagName().matches("h[1-4]");
                     sib = sib.nextElementSibling()) {
                    String text = sib.text().strip();
                    if (!text.isBlank()) content.append(text).append("\n");
                }
                if (!content.isEmpty()) {
                    sections.add(ParsedDocument.Section.builder()
                            .heading(heading).path(heading)
                            .content(content.toString().strip()).level(level).build());
                }
            }
        }

        // meta 标签提取
        Map<String, String> meta = new LinkedHashMap<>();
        doc.select("meta[name][content]").forEach(m ->
                meta.put(m.attr("name"), m.attr("content")));

        String fullText = doc.body().text();

        log.debug("HTML 解析完成: docId={}, sections={}, chars={}", docId, sections.size(), fullText.length());

        return ParsedDocument.builder()
                .docId(docId).title(title).text(fullText)
                .sections(sections).metadata(meta)
                .mimeType(supportedMimeType()).build();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
