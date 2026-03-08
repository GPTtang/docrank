# 系统设计: 更多文档解析器（Excel/PPT/EPUB/CSV）

> **对应 PRD**：`docs/prd/v03-more-parsers.md`
> **状态**：待实现（Phase 1.7）

---

## 1. 概述

新增 4 个 `DocumentParser` 实现，注册到 `ParserRegistry`，`kb_ingest_file` 的格式校验白名单同步扩展。Excel/PPT 复用现有 Apache POI 依赖，EPUB 复用 Jsoup + Java ZIP，CSV 使用原生解析。

---

## 2. 公共约定

所有解析器遵循现有 `DocumentParser` 接口：
```java
public interface DocumentParser {
    String supportedMimeType();
    String supportedExtension();
    ParsedDocument parse(InputStream input, String filename, String docId) throws Exception;
}
```

---

## 3. ExcelParser（.xlsx）

```java
public class ExcelParser implements DocumentParser {
    public String supportedExtension() { return ".xlsx"; }
    public String supportedMimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            List<ParsedDocument.Section> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            String title = filename.replace(".xlsx", "");

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                StringBuilder content = new StringBuilder();

                for (Row row : sheet) {
                    // 跳过空行（所有单元格为空）
                    boolean isEmpty = true;
                    StringBuilder rowText = new StringBuilder();

                    for (Cell cell : row) {
                        String val = getCellValueAsString(cell);
                        if (!val.isBlank()) isEmpty = false;
                        rowText.append(val).append("\t");
                    }
                    if (!isEmpty) {
                        content.append(rowText.toString().stripTrailing()).append("\n");
                    }
                }

                if (!content.isEmpty()) {
                    sections.add(ParsedDocument.Section.builder()
                        .heading(sheetName)
                        .path(sheetName)
                        .content(content.toString().trim())
                        .level(1)
                        .build());
                    fullText.append(content);
                }
            }

            return ParsedDocument.builder()
                .docId(docId).title(title)
                .text(fullText.toString())
                .sections(sections)
                .mimeType(supportedMimeType())
                .build();
        }
    }

    private String getCellValueAsString(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toString()
                : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING
                ? cell.getStringCellValue()
                : String.valueOf(cell.getNumericCellValue());
            default -> "";
        };
    }
}
```

---

## 4. PowerPointParser（.pptx）

```java
public class PowerPointParser implements DocumentParser {
    public String supportedExtension() { return ".pptx"; }
    public String supportedMimeType() {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(input)) {
            List<ParsedDocument.Section> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            String title = filename.replace(".pptx", "");

            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                StringBuilder content = new StringBuilder();
                String heading = "幻灯片 " + (i + 1);

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text == null || text.isBlank()) continue;

                        // 第一个文本框通常是标题
                        if (content.isEmpty() && textShape.getShapeName().contains("Title")) {
                            heading = text.trim();
                        } else {
                            content.append(text.trim()).append("\n");
                        }
                    }
                }

                if (!content.isEmpty()) {
                    sections.add(ParsedDocument.Section.builder()
                        .heading(heading).path(heading)
                        .content(content.toString().trim())
                        .level(1).build());
                    fullText.append(content);
                }
            }

            return ParsedDocument.builder()
                .docId(docId).title(title)
                .text(fullText.toString())
                .sections(sections)
                .mimeType(supportedMimeType())
                .build();
        }
    }
}
```

---

## 5. EpubParser（.epub）

```java
public class EpubParser implements DocumentParser {
    public String supportedExtension() { return ".epub"; }
    public String supportedMimeType() { return "application/epub+zip"; }

    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        // EPUB 是 ZIP 包
        byte[] bytes = input.readAllBytes();
        List<ParsedDocument.Section> sections = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        String title = filename.replace(".epub", "");

        // 读取章节顺序（content.opf）
        List<String> spineOrder = readSpineOrder(bytes);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            Map<String, String> htmlContents = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".html") || name.endsWith(".xhtml")) {
                    htmlContents.put(name, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }

            // 按 spine 顺序处理章节
            List<String> orderedKeys = spineOrder.isEmpty()
                ? new ArrayList<>(htmlContents.keySet())
                : spineOrder.stream().filter(htmlContents::containsKey).collect(toList());

            for (String key : orderedKeys) {
                String html = htmlContents.get(key);
                Document doc = Jsoup.parse(html);
                // 复用 HtmlParser 的段落提取逻辑
                String heading = doc.select("h1, h2, title").first() != null
                    ? doc.select("h1, h2, title").first().text() : key;
                String content = doc.body() != null ? doc.body().text() : "";
                if (!content.isBlank()) {
                    sections.add(ParsedDocument.Section.builder()
                        .heading(heading).path(heading)
                        .content(content).level(1).build());
                    fullText.append(content).append("\n");
                }
            }
        }

        return ParsedDocument.builder()
            .docId(docId).title(title)
            .text(fullText.toString())
            .sections(sections)
            .mimeType(supportedMimeType())
            .build();
    }

    private List<String> readSpineOrder(byte[] epubBytes) {
        // 解析 content.opf 中的 <spine> 元素获取章节顺序
        // 返回文件路径列表，失败时返回空列表（降级为目录顺序）
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epubBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".opf")) {
                    // 解析 <spine> 和 <manifest> 获取有序文件列表
                }
            }
        } catch (Exception ignored) {}
        return List.of();
    }
}
```

---

## 6. CsvParser（.csv）

```java
public class CsvParser implements DocumentParser {
    public String supportedExtension() { return ".csv"; }
    public String supportedMimeType() { return "text/csv"; }

    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        List<String> lines = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))
            .lines().collect(toList());

        if (lines.isEmpty()) return emptyDoc(docId, filename);

        // 第一行为列标题
        String[] headers = lines.get(0).split(",", -1);
        List<ParsedDocument.Section> sections = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        String title = filename.replace(".csv", "");

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;

            String[] values = line.split(",", -1);
            StringBuilder content = new StringBuilder();

            for (int j = 0; j < headers.length; j++) {
                String val = j < values.length ? values[j].trim() : "";
                if (!val.isBlank()) {
                    content.append(headers[j].trim()).append(": ").append(val).append("\n");
                }
            }

            if (!content.isEmpty()) {
                String rowLabel = "第 " + i + " 行";
                sections.add(ParsedDocument.Section.builder()
                    .heading(rowLabel).path(rowLabel)
                    .content(content.toString().trim())
                    .level(1).build());
                fullText.append(content);
            }
        }

        return ParsedDocument.builder()
            .docId(docId).title(title)
            .text(fullText.toString())
            .sections(sections)
            .mimeType(supportedMimeType())
            .build();
    }
}
```

---

## 7. ParserRegistry 注册

```java
// ParserRegistry 构造器中新增注册
public ParserRegistry() {
    register(new PdfParser());
    register(new MarkdownParser());
    register(new HtmlParser());
    register(new WordParser());
    register(new JsonParser());
    register(new TextParser());
    // 新增
    register(new ExcelParser());
    register(new PowerPointParser());
    register(new EpubParser());
    register(new CsvParser());
}
```

## 8. MCP Server 扩展格式白名单

```java
// DocRankMcpServer 中的格式检查
private boolean isSupported(String filename) {
    String lower = filename.toLowerCase();
    return lower.endsWith(".pdf")  || lower.endsWith(".md")
        || lower.endsWith(".html") || lower.endsWith(".docx")
        || lower.endsWith(".txt")  || lower.endsWith(".json")
        // 新增
        || lower.endsWith(".xlsx") || lower.endsWith(".pptx")
        || lower.endsWith(".epub") || lower.endsWith(".csv");
}
```

---

## 9. 技术决策

### 9.1 CSV 不引入 OpenCSV

原生 `split(",", -1)` 对简单 CSV（无引号内逗号）足够。复杂 CSV（字段含逗号、换行）需 OpenCSV，作为 P1 升级项，当前保持零依赖。

### 9.2 EPUB 章节顺序

EPUB 的章节顺序定义在 `content.opf` 的 `<spine>` 中，解析失败时降级为目录文件名字母顺序，保证健壮性。

### 9.3 数值单元格格式

Excel 数值单元格用 `(long)` 转换，避免浮点数格式（如 `1.0` 而非 `1`）干扰文本匹配。日期格式使用 `LocalDateTime.toString()`。
