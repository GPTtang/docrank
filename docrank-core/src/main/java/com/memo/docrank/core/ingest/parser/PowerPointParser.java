package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PowerPoint (.pptx) 文档解析器（基于 Apache POI XMLSlideShow）
 *
 * 每张幻灯片生成一个 Section；
 * 第一个 Title 形状的文本作为标题，其余文本合并为正文。
 */
@Slf4j
public class PowerPointParser implements DocumentParser {

    @Override
    public String supportedMimeType() {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    @Override
    public String supportedExtension() {
        return ".pptx";
    }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(input)) {
            List<XSLFSlide> slides = ppt.getSlides();
            List<ParsedDocument.Section> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                String heading = null;
                StringBuilder slideContent = new StringBuilder();

                for (XSLFShape shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFTextShape textShape)) continue;

                    String text = textShape.getText();
                    if (text == null || text.isBlank()) continue;
                    text = text.strip();

                    // 第一个 Title 形状作为 heading
                    if (heading == null && isTitle(textShape)) {
                        heading = text;
                    } else {
                        if (slideContent.length() > 0) slideContent.append("\n");
                        slideContent.append(text);
                    }
                }

                String content = slideContent.toString().strip();
                // 跳过空幻灯片（heading 和内容都为空）
                if (heading == null && content.isBlank()) continue;

                if (heading == null) heading = "幻灯片 " + (i + 1);

                sections.add(ParsedDocument.Section.builder()
                        .heading(heading)
                        .path(heading)
                        .content(content)
                        .level(1)
                        .build());
                fullText.append(heading).append("\n").append(content).append("\n");
            }

            String title = sections.isEmpty() ? stripExtension(filename)
                    : sections.get(0).getHeading();

            log.debug("PPT 解析完成: docId={}, slides={}, sections={}", docId,
                    slides.size(), sections.size());

            return ParsedDocument.builder()
                    .docId(docId)
                    .title(title)
                    .text(fullText.toString().strip())
                    .sections(sections)
                    .metadata(Map.of("source", filename))
                    .mimeType(supportedMimeType())
                    .build();
        }
    }

    /**
     * 判断文本形状是否为标题占位符。
     * POI 中标题占位符的类名包含 "Title"。
     */
    private boolean isTitle(XSLFTextShape shape) {
        String className = shape.getClass().getSimpleName();
        return className.contains("Title");
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
