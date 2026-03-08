# PRD: Spring AI 适配器

> **状态**：已实现（Phase 2.3）
> **模块**：docrank-spring-ai
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

Spring AI 是 Spring 官方的 AI 框架，正在快速成为 Spring Boot 生态中集成 AI 功能的标准方式。其 `VectorStore` 和 `EmbeddingModel` 接口是 RAG 管道的核心抽象。若 DocRank 不实现这些接口，Spring AI 用户无法使用。

### 1.2 目标

实现 Spring AI 标准接口适配，使 DocRank 的本地混合检索能力可直接接入 Spring AI RAG 管道。

### 1.3 非目标

- 不实现 Spring AI 的 ChatModel 接口
- 不依赖 Spring AI 的具体 RAG 编排实现

---

## 2. 用户故事

```
作为 Spring AI 开发者，
我希望用 DocRankVectorStore 替换 Spring AI 默认向量存储，
以便使用本地离线检索而无需修改 RAG 管道代码。
```

---

## 3. 功能需求

### 3.1 DocRankEmbeddingModel

- **P0** 实现 Spring AI `EmbeddingModel` 接口
- **P0** `call(EmbeddingRequest)` → 批量向量化，返回 `EmbeddingResponse`
- **P0** `embed(String)` / `embed(Document)` → 单文本向量化，返回 `List<Double>`
- **P0** `embed(List<String>)` → 批量向量化
- **P0** `dimensions()` → 返回 1024
- **P0** float[] → List<Double> 类型转换

### 3.2 DocRankVectorStore

- **P0** 实现 Spring AI `VectorStore` 接口
- **P0** `add(List<Document>)` → 向量化 + 写入向量后端 + BM25 索引
- **P0** `delete(List<String>)` → 级联删除向量和 BM25，返回 `Optional<Boolean>`
- **P0** `similaritySearch(SearchRequest)` → 向量检索 + 相似度阈值过滤
- **P0** 支持简单 FilterExpression 解析（`scope == 'value'` 格式）
- **P0** 检索结果带 score 元数据
- **P0** Document metadata 与 Chunk 字段双向映射

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 依赖 | `spring-ai 1.0.x` 为 provided scope |
| 兼容 | 不污染未使用 Spring AI 的项目 |

---

## 5. 验收标准

- [ ] AC1：`DocRankVectorStore` 可直接注入 Spring AI RAG 管道
- [ ] AC2：`add(documents)` 后 `similaritySearch()` 可召回
- [ ] AC3：`delete()` 后该文档不再出现在检索结果中
- [ ] AC4：`SearchRequest` 的 `similarityThreshold` 参数生效

---

## 6. 参考资料

- ROADMAP.md Phase 2.3 Spring AI Adapter
- Spring AI VectorStore 接口文档
