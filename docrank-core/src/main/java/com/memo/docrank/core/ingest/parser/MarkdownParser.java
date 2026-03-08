package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.html.HtmlRenderer;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class MarkdownParser implements DocumentParser {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownParser() {
        MutableDataSet opts = new MutableDataSet();
        this.parser   = Parser.builder(opts).build();
        this.renderer = HtmlRenderer.builder(opts).build();
    }

    @Override
    public String supportedMimeType() { return "text/markdown"; }

    @Override
    public String supportedExtension() { return ".md"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        Document doc = parser.parse(raw);

        // 按标题切分段落
        List<ParsedDocument.Section> sections = new ArrayList<>();
        List<String> pathStack = new ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        String currentHeading = "";
        int currentLevel = 0;

        for (Node node : doc.getChildren()) {
            if (node instanceof Heading h) {
                // 保存上一段
                if (!currentContent.isEmpty()) {
                    sections.add(buildSection(currentHeading,
                            buildPath(pathStack, currentLevel), currentContent.toString(), currentLevel));
                    currentContent.setLength(0);
                }
                currentHeading = h.getText().toString();
                currentLevel   = h.getLevel();
                updatePathStack(pathStack, currentHeading, currentLevel);
            } else {
                // 将节点渲染为 HTML 再转纯文本
                String html = renderer.render(node);
                String text = Jsoup.parse(html).text();
                if (!text.isBlank()) {
                    currentContent.append(text).append("\n");
                }
            }
        }
        // 最后一段
        if (!currentContent.isEmpty()) {
            sections.add(buildSection(currentHeading,
                    buildPath(pathStack, currentLevel), currentContent.toString(), currentLevel));
        }

        // 全文纯文本（HTML → text）
        String html = renderer.render(doc);
        String fullText = Jsoup.parse(html).text();

        // 推断标题：取第一个 H1，或文件名
        String title = sections.stream()
                .filter(s -> s.getLevel() == 1)
                .map(ParsedDocument.Section::getHeading)
                .findFirst()
                .orElse(stripExtension(filename));

        log.debug("Markdown 解析完成: docId={}, sections={}", docId, sections.size());

        return ParsedDocument.builder()
                .docId(docId)
                .title(title)
                .text(fullText)
                .sections(sections)
                .metadata(Map.of("source", filename))
                .mimeType(supportedMimeType())
                .build();
    }

    private ParsedDocument.Section buildSection(String heading, String path,
                                                 String content, int level) {
        return ParsedDocument.Section.builder()
                .heading(heading).path(path).content(content.strip()).level(level).build();
    }

    private String buildPath(List<String> stack, int level) {
        return String.join(" > ", stack.subList(0, Math.min(level, stack.size())));
    }

    private void updatePathStack(List<String> stack, String heading, int level) {
        while (stack.size() >= level) stack.remove(stack.size() - 1);
        stack.add(heading);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
