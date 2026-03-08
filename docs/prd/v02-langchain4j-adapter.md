# PRD: LangChain4j 适配器

> **状态**：已实现（Phase 2.2）
> **模块**：docrank-langchain4j
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

LangChain4j 是 Java 生态中最流行的 AI 应用框架，其 RAG 管道依赖标准的 `EmbeddingModel`、`EmbeddingStore`、`ContentRetriever` 接口。若 DocRank 不实现这些接口，使用 LangChain4j 的开发者无法集成。

### 1.2 目标

实现 LangChain4j 标准接口适配，使 DocRank 能作为 LangChain4j RAG 管道的 Embedding 模型和向量存储，零 API 成本接入完整混合检索能力。

### 1.3 非目标

- 不实现 LangChain4j 的 ChatModel 接口（DocRank 不是 LLM）
- 不依赖 LangChain4j 的具体版本特性（最低兼容 0.36.x）

---

## 2. 用户故事

```
作为 LangChain4j 开发者，
我希望用 DocRankEmbeddingStore 替换默认向量存储，
以便使用 DocRank 的本地 BGE-M3 向量化和混合检索，而无需重写 RAG 代码。
```

```
作为 LangChain4j 开发者，
我希望用 DocRankHybridRetriever 直接接入 LangChain4j RAG 链，
以便获得 BM25 + 向量 + Reranker 的完整检索能力。
```

---

## 3. 功能需求

### 3.1 DocRankEmbeddingModel

- **P0** 实现 `EmbeddingModel` 接口
- **P0** `embed(String)` / `embed(TextSegment)` → 调用本地 BGE-M3
- **P0** `embedAll(List<TextSegment>)` → 批量向量化
- **P0** `dimension()` → 返回 1024

### 3.2 DocRankEmbeddingStore

- **P0** 实现 `EmbeddingStore<TextSegment>` 接口
- **P0** `add(Embedding, TextSegment)` → 存入向量后端 + BM25 索引
- **P0** `addAll(List<Embedding>, List<TextSegment>)` → 批量写入
- **P0** `remove(String)` / `removeAll(Collection<String>)` → 级联删除
- **P0** `search(EmbeddingSearchRequest)` → 向量检索 + 相似度阈值过滤
- **P0** 支持 `defaultScope` 配置（默认 "global"）
- **P0** TextSegment metadata 与 Chunk 字段双向映射（doc_id/title/section/scope/importance）

### 3.3 DocRankHybridRetriever

- **P0** 实现 `ContentRetriever` 接口
- **P0** `retrieve(Query)` → 调用 `KnowledgeBaseService.search()`（完整混合检索管道）
- **P0** SearchResult → LangChain4j Content 转换，保留 score/title/section/language 元数据
- **P0** 支持 scope / extraFilters / topK 配置

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 依赖 | `langchain4j 0.36.x` 为 provided scope，不强制传递 |
| 兼容 | 不污染未使用 LangChain4j 的项目 |

---

## 5. 验收标准

- [ ] AC1：`DocRankEmbeddingModel` 可直接替换 LangChain4j 默认 EmbeddingModel
- [ ] AC2：`DocRankEmbeddingStore.add()` 后，`search()` 可检索到对应内容
- [ ] AC3：`DocRankHybridRetriever.retrieve()` 返回的 Content 包含正确的 score 元数据
- [ ] AC4：模块不引入 langchain4j 为 compile 依赖

---

## 6. 参考资料

- ROADMAP.md Phase 2.2 LangChain4j Adapter
- LangChain4j EmbeddingStore 接口文档
