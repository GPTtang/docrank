package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Word (.docx) 文档解析器（基于 Apache POI）
 */
@Slf4j
public class WordParser implements DocumentParser {

    @Override
    public String supportedMimeType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public String supportedExtension() { return ".docx"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        try (XWPFDocument docx = new XWPFDocument(input)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(docx);
            String fullText = extractor.getText();

            // 按段落切分，以 Heading 样式识别标题
            List<ParsedDocument.Section> sections = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            String currentHeading = stripExtension(filename);
            int currentLevel = 1;

            for (XWPFParagraph para : docx.getParagraphs()) {
                String style = para.getStyle();
                String text  = para.getText().strip();
                if (text.isBlank()) continue;

                if (style != null && style.toLowerCase().startsWith("heading")) {
                    // 保存上一段
                    if (!current.isEmpty()) {
                        sections.add(ParsedDocument.Section.builder()
                                .heading(currentHeading).path(currentHeading)
                                .content(current.toString().strip()).level(currentLevel).build());
                        current.setLength(0);
                    }
                    currentHeading = text;
                    currentLevel   = extractHeadingLevel(style);
                } else {
                    current.append(text).append("\n");
                }
            }
            if (!current.isEmpty()) {
                sections.add(ParsedDocument.Section.builder()
                        .heading(currentHeading).path(currentHeading)
                        .content(current.toString().strip()).level(currentLevel).build());
            }

            String title = sections.stream()
                    .filter(s -> s.getLevel() == 1)
                    .map(ParsedDocument.Section::getHeading)
                    .findFirst().orElse(stripExtension(filename));

            log.debug("Word 解析完成: docId={}, sections={}", docId, sections.size());

            return ParsedDocument.builder()
                    .docId(docId).title(title).text(fullText)
                    .sections(sections).metadata(Map.of("source", filename))
                    .mimeType(supportedMimeType()).build();
        }
    }

    private int extractHeadingLevel(String style) {
        try {
            // "Heading1" → 1, "heading 2" → 2
            String digits = style.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
