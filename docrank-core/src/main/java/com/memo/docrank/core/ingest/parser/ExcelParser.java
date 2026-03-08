package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel (.xlsx) 文档解析器（基于 Apache POI XSSFWorkbook）
 *
 * 每个 Sheet 生成一个 Section，Sheet 名作为标题；
 * 每行单元格用 \t 拼接，跳过空行。
 */
@Slf4j
public class ExcelParser implements DocumentParser {

    @Override
    public String supportedMimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String supportedExtension() {
        return ".xlsx";
    }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            List<ParsedDocument.Section> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                StringBuilder sheetContent = new StringBuilder();

                for (Row row : sheet) {
                    StringBuilder rowText = new StringBuilder();
                    boolean hasContent = false;

                    for (Cell cell : row) {
                        String cellValue = getCellValue(cell);
                        if (!cellValue.isBlank()) hasContent = true;
                        if (rowText.length() > 0) rowText.append("\t");
                        rowText.append(cellValue);
                    }

                    if (hasContent) {
                        sheetContent.append(rowText).append("\n");
                    }
                }

                String content = sheetContent.toString().strip();
                if (!content.isBlank()) {
                    sections.add(ParsedDocument.Section.builder()
                            .heading(sheetName)
                            .path(sheetName)
                            .content(content)
                            .level(1)
                            .build());
                    fullText.append(sheetName).append("\n").append(content).append("\n");
                }
            }

            String title = sections.isEmpty() ? stripExtension(filename)
                    : sections.get(0).getHeading();

            log.debug("Excel 解析完成: docId={}, sheets={}, sections={}", docId,
                    workbook.getNumberOfSheets(), sections.size());

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

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> "";
        };
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
