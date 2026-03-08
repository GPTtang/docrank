# PRD: 更多文档解析器（Excel/PPT/EPUB/CSV）

> **状态**：待实现（Phase 1.7）
> **模块**：docrank-core（ingest/parser 包）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

企业知识库中大量内容以 Excel、PowerPoint、EPUB、CSV 格式存储，目前 DocRank 不支持这些格式，用户需要手动转换后才能写入。

### 1.2 目标

新增 4 种文档解析器（Excel/PPT/EPUB/CSV），与现有解析器无缝集成，注册到 `ParserRegistry` 后即可通过 `kb_ingest_file` 直接上传。

### 1.3 非目标

- 不支持旧格式（.xls/.ppt），只支持 Office Open XML（.xlsx/.pptx）
- 不提取图表/图片内容（只提取文本）
- 不处理加密文件

---

## 2. 用户故事

```
作为企业用户，
我希望直接上传 Excel 表格和 PPT 幻灯片到知识库，
以便不手动转换格式就能检索其中的内容。
```

---

## 3. 功能需求

### 3.1 Excel 解析器（.xlsx）

- **P0** 实现 `DocumentParser` 接口，MIME `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- **P0** 每个 Sheet 作为一个 Section，Sheet 名称作为标题
- **P0** 每行单元格用制表符拼接，行间换行
- **P0** 跳过空行
- **P0** 工具：Apache POI（`poi-ooxml`，已有依赖）

### 3.2 PowerPoint 解析器（.pptx）

- **P0** 实现 `DocumentParser` 接口，MIME `application/vnd.openxmlformats-officedocument.presentationml.presentation`
- **P0** 每张幻灯片作为一个 Section，幻灯片标题（若有）作为 Section heading
- **P0** 提取幻灯片中所有文本框内容，按顺序拼接
- **P0** 跳过空幻灯片
- **P0** 工具：Apache POI（`poi-ooxml`，已有依赖）

### 3.3 EPUB 解析器（.epub）

- **P0** 实现 `DocumentParser` 接口，MIME `application/epub+zip`
- **P0** EPUB 是 ZIP 包，解析其中的 HTML 章节文件
- **P0** 每个 HTML 章节作为一个 Section，章节标题作为 heading
- **P0** 工具：Jsoup（已有依赖，用于 HTML 解析）+ Java ZipInputStream
- **P1** 读取 `content.opf` 确定章节顺序

### 3.4 CSV 解析器（.csv）

- **P0** 实现 `DocumentParser` 接口，MIME `text/csv`
- **P0** 第一行作为列标题
- **P0** 每行按「列名: 值」格式拼接为文本，作为一个 Section
- **P0** 工具：OpenCSV 或 Java 原生解析
- **P1** 支持自定义分隔符（默认逗号）

### 3.5 MCP 端点更新

- **P0** `kb_ingest_file` 支持 `.xlsx / .pptx / .epub / .csv` 文件扩展名
- **P0** `GET /mcp/tools` 中文件格式描述更新

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 统一接口 | 全部实现 `DocumentParser`，自动注册到 `ParserRegistry` |
| 无新重依赖 | Excel/PPT 复用现有 Apache POI；EPUB 复用 Jsoup；CSV 尽量零依赖 |

---

## 5. 验收标准

- [ ] AC1：上传 `.xlsx` 文件，每个 Sheet 作为独立 Section 写入知识库
- [ ] AC2：上传 `.pptx` 文件，每张幻灯片的文本可被检索
- [ ] AC3：上传 `.epub` 文件，各章节内容可被检索
- [ ] AC4：上传 `.csv` 文件，每行内容以「列名: 值」形式可被检索
- [ ] AC5：不支持的格式（如 `.xls`）返回 `success: false` 而非 500

---

## 6. 依赖

| 格式 | 依赖 | 是否已有 |
|------|------|---------|
| Excel | Apache POI poi-ooxml | ✅ 已有（DOCX 解析用） |
| PPT | Apache POI poi-ooxml | ✅ 已有 |
| EPUB | Jsoup + Java ZIP | ✅ 已有 |
| CSV | OpenCSV 或原生 | ❌ 需新增（可用原生避免） |

---

## 7. 参考资料

- ROADMAP.md Phase 1.7 Additional Parsers
