# 系统设计: Spring AI 适配器

> **对应 PRD**：`docs/prd/v02-spring-ai-adapter.md`
> **状态**：已实现（Phase 2.3）
> **模块**：docrank-spring-ai

---

## 1. 概述

`docrank-spring-ai` 提供两个适配类，将 DocRank 核心能力适配到 Spring AI 标准接口。依赖声明为 `provided` scope。

---

## 2. 类结构

```
docrank-spring-ai
  ├── DocRankEmbeddingModel   → Spring AI EmbeddingModel（接口适配）
  └── DocRankVectorStore      → Spring AI VectorStore（接口适配）
```

---

## 3. DocRankEmbeddingModel

```java
public class DocRankEmbeddingModel implements EmbeddingModel {
    private final EmbeddingProvider provider;
    private final int dimension;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> vecs = provider.encode(texts);
        List<Embedding> embeddings = IntStream.range(0, vecs.size())
            .mapToObj(i -> new Embedding(toDoubleList(vecs.get(i)), i))
            .collect(toList());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public List<Double> embed(String text) {
        return toDoubleList(provider.encodeSingle(text));
    }

    @Override
    public List<Double> embed(Document document) {
        return embed(document.getContent());
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        return provider.encode(texts).stream()
            .map(this::toDoubleList)
            .collect(toList());
    }

    @Override
    public int dimensions() { return dimension; }

    private List<Double> toDoubleList(float[] arr) {
        // float[] → List<Double> 转换
    }
}
```

---

## 4. DocRankVectorStore

```java
public class DocRankVectorStore implements VectorStore {
    private final EmbeddingModel embeddingModel;   // DocRankEmbeddingModel
    private final IndexBackend vectorBackend;
    private final BM25Index bm25Index;             // 可选
    private final String defaultScope;

    @Override
    public void add(List<Document> documents) {
        // 1. 批量向量化
        List<String> contents = documents.stream()
            .map(Document::getContent).collect(toList());
        List<List<Double>> vecs = embeddingModel.embed(contents);

        // 2. 转换为 ChunkWithVectors
        List<ChunkWithVectors> chunks = IntStream.range(0, documents.size())
            .mapToObj(i -> {
                Document doc = documents.get(i);
                Chunk chunk = toChunk(doc);
                return ChunkWithVectors.builder()
                    .chunk(chunk)
                    .vecChunk(toFloatList(vecs.get(i)))
                    .build();
            }).collect(toList());

        // 3. 双写
        vectorBackend.upsertChunks(chunks);
        if (bm25Index != null) {
            bm25Index.addChunks(chunks.stream()
                .map(ChunkWithVectors::getChunk).collect(toList()));
        }
    }

    @Override
    public Optional<Boolean> delete(List<String> idList) {
        idList.forEach(id -> {
            vectorBackend.deleteByDocId(id);
            if (bm25Index != null) bm25Index.deleteByDocId(id);
        });
        return Optional.of(true);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // 1. 向量化查询
        float[] queryVec = toFloatArray(embeddingModel.embed(request.getQuery()));

        // 2. 解析 filter（简单格式：scope == 'value'）
        Map<String, Object> filters = parseFilterExpression(request.getFilterExpression());

        // 3. 向量检索
        List<RecallCandidate> candidates = vectorBackend.vectorSearch(
            queryVec, request.getTopK(), filters);

        // 4. 相似度阈值过滤 + 转换为 Document
        return candidates.stream()
            .filter(c -> c.getScore() >= request.getSimilarityThreshold())
            .map(c -> {
                Map<String, Object> meta = buildMetadata(c.getChunk());
                meta.put("score", c.getScore());
                return new Document(c.getChunk().getChunkText(), meta);
            }).collect(toList());
    }

    // 简单 FilterExpression 解析（支持 "scope == 'value'" 格式）
    private Map<String, Object> parseFilterExpression(FilterExpressionTextParser.Expression expr) {
        Map<String, Object> filters = new HashMap<>();
        if (expr != null) {
            // 解析 EQ 表达式，提取 key=value
        }
        return filters;
    }
}
```

---

## 5. Document metadata 映射

Spring AI `Document` 的 metadata 是 `Map<String, Object>`：

| Chunk 字段 | Document metadata key |
|-----------|----------------------|
| `docId` | `doc_id` |
| `title` | `title` |
| `sectionPath` | `section` |
| `scope` | `scope` |
| `importance` | `importance` |
| `language` | `language` |
| score（SearchResult） | `score` |

---

## 6. 典型使用方式

```java
@Bean
VectorStore vectorStore(EmbeddingProvider provider,
                        IndexBackend vectorBackend,
                        BM25Index bm25Index) {
    EmbeddingModel embeddingModel = DocRankEmbeddingModel.builder()
        .provider(provider).dimension(1024).build();

    return DocRankVectorStore.builder()
        .embeddingModel(embeddingModel)
        .vectorBackend(vectorBackend)
        .bm25Index(bm25Index)
        .defaultScope("my-project")
        .build();
}
```

---

## 7. 技术决策

### 7.1 float[] vs List<Double>

Spring AI 接口使用 `List<Double>`，内部存储用 `float[]`（节省 50% 内存）。转换在适配层统一处理，不泄漏到核心模块。

### 7.2 FilterExpression 支持范围

Spring AI `FilterExpression` 语法复杂（支持 AND/OR/NOT/IN 等）。当前只支持简单的 `scope == 'value'` 格式，足以覆盖最常见场景。复杂表达式支持可在后续迭代实现。
