package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CSV 文档解析器（原生 Java，无额外依赖）
 *
 * 第一行作为列标题；每行数据生成一个 Section，
 * 格式为"列名: 值\n列名: 值\n..."。
 * 跳过全空行。
 */
@Slf4j
public class CsvParser implements DocumentParser {

    @Override
    public String supportedMimeType() {
        return "text/csv";
    }

    @Override
    public String supportedExtension() {
        return ".csv";
    }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                // 空文件
                return ParsedDocument.builder()
                        .docId(docId)
                        .title(stripExtension(filename))
                        .text("")
                        .sections(sections)
                        .metadata(Map.of("source", filename))
                        .mimeType(supportedMimeType())
                        .build();
            }

            String[] headers = parseCsvLine(headerLine);

            String line;
            int rowIndex = 1;
            while ((line = reader.readLine()) != null) {
                rowIndex++;
                if (line.isBlank()) continue;

                String[] values = parseCsvLine(line);
                StringBuilder rowContent = new StringBuilder();
                boolean hasContent = false;

                for (int i = 0; i < headers.length; i++) {
                    String value = i < values.length ? values[i].strip() : "";
                    if (!value.isBlank()) hasContent = true;
                    rowContent.append(headers[i].strip()).append(": ").append(value).append("\n");
                }

                if (!hasContent) continue;

                // Section 标题用"第 N 行"标识
                String heading = "行 " + rowIndex;
                String content = rowContent.toString().strip();

                sections.add(ParsedDocument.Section.builder()
                        .heading(heading)
                        .path(heading)
                        .content(content)
                        .level(1)
                        .build());
                fullText.append(content).append("\n");
            }
        }

        String title = stripExtension(filename);

        log.debug("CSV 解析完成: docId={}, rows(sections)={}", docId, sections.size());

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
     * 简单 CSV 行解析：按逗号分隔，保留 -1 以保证末尾空字段。
     * 不处理引号内的换行，适合常规单行 CSV。
     */
    private String[] parseCsvLine(String line) {
        return line.split(",", -1);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
