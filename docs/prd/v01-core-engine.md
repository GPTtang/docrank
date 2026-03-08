# PRD: 核心检索引擎

> **状态**：已实现（v0.1）
> **模块**：docrank-core, docrank-memory
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

AI Agent 需要从大量本地文档中快速准确地召回相关内容。传统方案要么依赖云端 API（有隐私和成本问题），要么只支持单一检索方式（纯关键词或纯向量），召回质量不足。

### 1.2 目标

提供一套完全离线的混合检索引擎，将 BM25 关键词检索与向量语义检索融合，支持中/日/英三语，开箱即用，零 API 依赖。

### 1.3 非目标

- 不提供分布式部署（单机设计）
- 不提供实时索引同步（单进程写入）
- 不提供文档版本管理

---

## 2. 用户故事

```
作为 AI Agent 开发者，
我希望能将本地 PDF/Markdown/Word 等文档写入知识库，并用自然语言查询，
以便 Agent 能准确召回相关内容作为上下文。
```

```
作为 Java 后端开发者，
我希望通过简单的 API 调用完成文档写入和检索，
以便快速集成到现有项目中，不依赖外部 AI 服务。
```

---

## 3. 功能需求

### 3.1 文档写入（Ingest）

- **P0** 支持从文本字符串写入（title + content）
- **P0** 支持从文件流写入（PDF/MD/HTML/DOCX/JSON/TXT）
- **P0** 自动语言检测（中/日/英，auto 模式）
- **P0** 语言感知分块（CJK 按字符数，英文按词数，句边界切分）
- **P0** 本地 ONNX 向量化（BGE-M3，1024 维，L2 归一化）
- **P0** 双写索引：向量存储（LanceDB）+ BM25（Lucene）
- **P0** 支持自定义 tags / scope / importance / expiresAt

### 3.2 混合检索（Search）

- **P0** BM25 全文检索（Lucene，多字段加权：title^3 / chunk_text^1 / tags^2）
- **P0** 向量语义检索（余弦相似度）
- **P0** RRF 融合（K=60）合并两路召回
- **P0** ONNX 精排（bge-reranker-v2-m3，组合分 = 0.6×rerank + 0.4×fused）
- **P0** 高级评分后处理（时效性衰减 + 重要度 + MMR 多样性）
- **P0** 按 scope / tags 过滤
- **P0** TTL 过期过滤

### 3.3 文档解析

| 格式 | 工具 | 结构保留 |
|------|------|---------|
| PDF | Apache PDFBox | 按页切分为 Section |
| Markdown | Flexmark | 按标题层级切分 |
| HTML | Jsoup | 按 H1-H4 切分，去广告标签 |
| DOCX | Apache POI | 按 Heading 样式切分 |
| JSON | Jackson | 支持标准/数组/任意三种格式 |
| TXT | 原生 | 整体作为一个 Section |

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 离线 | 零外部 API 调用，所有模型本地 ONNX 推理 |
| 语言 | 中/日/英自动检测，分块和分析策略各语言独立 |
| 性能 | 向量化批处理（batchSize=32）；BM25 与向量并行召回 |
| 可扩展 | DocumentParser 接口可注册自定义解析器；IndexBackend 接口可替换向量后端 |

---

## 5. 验收标准

- [ ] AC1：写入一篇 PDF，返回 docId 和 chunkCount > 0
- [ ] AC2：搜索相关关键词，结果中包含该文档的 chunk
- [ ] AC3：中文文档按标点句边界分块，不截断句子中间
- [ ] AC4：删除文档后，搜索结果中不再出现该 doc 的 chunk
- [ ] AC5：写入 expiresAt=过去时间的文档，搜索结果中不返回该 chunk
- [ ] AC6：两路召回并行执行（CompletableFuture）

---

## 6. 依赖

| 依赖 | 说明 |
|------|------|
| ONNX Runtime 1.19.2 | BGE-M3 / reranker 推理 |
| DJL 0.28.0 | ONNX 模型加载 |
| Apache Lucene 9.11.0 | BM25 + 多语言分析器 |
| HanLP | 中文分词（Lucene 分析器） |
| Kuromoji | 日文分词（Lucene 内置） |
| Lingua | 语言检测 |
| Apache PDFBox | PDF 解析 |
| Flexmark | Markdown 解析 |
| Jsoup | HTML 解析 |
| Apache POI | DOCX 解析 |
| LanceDB（HTTP） | 向量存储（默认后端） |

---

## 7. 参考资料

- ROADMAP.md v0.1 Current State
- BGE-M3: BAAI/bge-m3（HuggingFace）
- bge-reranker-v2-m3: BAAI/bge-reranker-v2-m3（HuggingFace）
