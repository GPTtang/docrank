# 系统设计: LangChain4j 适配器

> **对应 PRD**：`docs/prd/v02-langchain4j-adapter.md`
> **状态**：已实现（Phase 2.2）
> **模块**：docrank-langchain4j

---

## 1. 概述

`docrank-langchain4j` 提供三个适配类，将 DocRank 核心能力适配到 LangChain4j 标准接口。依赖声明为 `provided` scope，不影响未使用 LangChain4j 的项目。

---

## 2. 类结构

```
docrank-langchain4j
  ├── DocRankEmbeddingModel   → EmbeddingModel（接口适配）
  ├── DocRankEmbeddingStore   → EmbeddingStore<TextSegment>（接口适配）
  └── DocRankHybridRetriever  → ContentRetriever（接口适配）
```

---

## 3. DocRankEmbeddingModel

### 接口实现

```java
public class DocRankEmbeddingModel implements EmbeddingModel {
    private final EmbeddingProvider provider;  // OnnxEmbeddingProvider
    private final int dimension;               // 1024

    @Override
    public Response<Embedding> embed(String text) {
        float[] vec = provider.encodeSingle(text);
        return Response.from(Embedding.from(toFloatList(vec)));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return embed(segment.text());
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<String> texts = segments.stream()
            .map(TextSegment::text).collect(toList());
        List<float[]> vecs = provider.encode(texts);
        List<Embedding> embeddings = vecs.stream()
            .map(v -> Embedding.from(toFloatList(v)))
            .collect(toList());
        return Response.from(embeddings);
    }

    @Override
    public int dimension() { return dimension; }
}
```

---

## 4. DocRankEmbeddingStore

### 接口实现

```java
public class DocRankEmbeddingStore implements EmbeddingStore<TextSegment> {
    private final IndexBackend vectorBackend;
    private final BM25Index bm25Index;         // 可选，null 时不维护 BM25
    private final String defaultScope;          // 默认 "global"

    // TextSegment metadata → Chunk 字段映射
    private Chunk toChunk(String id, TextSegment segment) {
        Metadata meta = segment.metadata();
        return Chunk.builder()
            .chunkId(id)
            .docId(meta.getOrDefault("doc_id", id))
            .title(meta.getOrDefault("title", ""))
            .sectionPath(meta.getOrDefault("section", ""))
            .chunkText(segment.text())
            .scope(meta.getOrDefault("scope", defaultScope))
            .importance(Double.parseDouble(meta.getOrDefault("importance", "1.0")))
            .updatedAt(Instant.now())
            .build();
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        String id = UUID.randomUUID().toString();
        Chunk chunk = toChunk(id, segment);
        ChunkWithVectors cwv = ChunkWithVectors.builder()
            .chunk(chunk)
            .vecChunk(toFloatList(embedding.vectorAsList()))
            .build();
        vectorBackend.upsertChunks(List.of(cwv));
        if (bm25Index != null) bm25Index.addChunks(List.of(chunk));
        return id;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        float[] queryVec = toFloatArray(request.queryEmbedding().vectorAsList());
        List<RecallCandidate> candidates = vectorBackend.vectorSearch(
            queryVec, request.maxResults(), Map.of());

        List<EmbeddingMatch<TextSegment>> matches = candidates.stream()
            .filter(c -> c.getScore() >= request.minScore())
            .map(c -> {
                TextSegment segment = TextSegment.from(
                    c.getChunk().getChunkText(),
                    buildMetadata(c.getChunk()));
                return EmbeddingMatch.from(c.getScore(), c.getChunk().getChunkId(),
                    null, segment);
            }).collect(toList());

        return new EmbeddingSearchResult<>(matches);
    }
}
```

---

## 5. DocRankHybridRetriever

### 接口实现

```java
public class DocRankHybridRetriever implements ContentRetriever {
    private final KnowledgeBaseService kb;
    private final int topK;                    // 默认 5
    private final String scope;                // 可选
    private final Map<String, Object> extraFilters;

    @Override
    public List<Content> retrieve(Query query) {
        List<SearchResult> results = kb.search(
            query.text(), topK, scope, extraFilters);

        return results.stream().map(r -> {
            String text = r.getChunk().getChunkText();
            Metadata meta = Metadata.from(Map.of(
                "doc_id",   r.getChunk().getDocId(),
                "title",    r.getChunk().getTitle(),
                "section",  r.getChunk().getSectionPath(),
                "scope",    r.getChunk().getScope(),
                "score",    String.valueOf(r.getScore()),
                "language", r.getChunk().getLanguage().name()
            ));
            return Content.from(TextSegment.from(text, meta));
        }).collect(toList());
    }
}
```

---

## 6. 典型使用方式

```java
// RAG 链集成（LangChain4j）
ContentRetriever retriever = DocRankHybridRetriever.builder()
    .knowledgeBase(knowledgeBaseService)
    .topK(5)
    .scope("project-x")
    .build();

RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
    .contentRetriever(retriever)
    .build();

AiServices.builder(MyAssistant.class)
    .chatLanguageModel(chatModel)
    .retrievalAugmentor(augmentor)
    .build();
```

---

## 7. 技术决策

### 7.1 三个类的分工

- `EmbeddingModel`：负责向量化（与存储解耦）
- `EmbeddingStore`：负责向量存储 + 检索（直接操作后端）
- `HybridRetriever`：负责完整混合检索（调用 KnowledgeBaseService）

推荐使用 `HybridRetriever` 以获取 BM25 + 向量 + Reranker 的完整能力，`EmbeddingStore` 主要用于与 LangChain4j 内置 RAG 管道兼容。

### 7.2 metadata 双向映射

LangChain4j TextSegment 的 metadata 是 `Map<String, String>`，所有值均转为字符串存储；读取时需类型转换。映射关系在 `toChunk()` 和 `buildMetadata()` 中集中维护。
