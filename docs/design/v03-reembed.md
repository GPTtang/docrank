# 系统设计: 重新向量化端点（kb_reembed）

> **对应 PRD**：`docs/prd/v03-reembed.md`
> **状态**：待实现（Phase 1.4）

---

## 1. 概述

新增 `POST /mcp/kb_reembed` 端点，从向量后端拉取所有 chunk 文本，用当前 `EmbeddingProvider` 批量重新生成向量并 upsert 回去。不改变 chunk 文本，不触及 Lucene 索引。

---

## 2. 流程

```
POST /mcp/kb_reembed
    ↓
KnowledgeBaseService.reembed(batchSize)
    ↓
循环分批：
    IndexBackend.listAllChunks(offset, batchSize)   ← 新增接口方法
        → List<Chunk>（含 chunkText）
    EmbeddingProvider.encode(texts)
        → List<float[]>
    IndexBackend.upsertChunks(ChunkWithVectors[])   ← 只更新向量，其余字段不变
    offset += batchSize
直到无更多数据
    ↓
返回 { chunk_count, elapsed_ms }
```

---

## 3. IndexBackend 接口新增方法

```java
// 分页拉取所有 chunk（用于 reembed）
default List<Chunk> listAllChunks(int offset, int limit) {
    throw new UnsupportedOperationException("该后端不支持 listAllChunks");
}
```

各后端实现：

**LanceDB**：
```
GET /v1/table/{name}/query/
body: { "query_type": "scan", "limit": limit, "offset": offset }
```

**Qdrant**：
```
POST /collections/{name}/points/scroll
body: { "limit": limit, "offset": offset, "with_payload": true, "with_vector": false }
```

**InMemory**：直接对内存 Map 做 skip/limit。

**pgvector**：
```sql
SELECT * FROM docrank_chunks ORDER BY chunk_id LIMIT ? OFFSET ?;
```

---

## 4. KnowledgeBaseService 新增方法

```java
public ReembedResult reembed(int batchSize) {
    long start = System.currentTimeMillis();
    int total = 0;
    int offset = 0;

    while (true) {
        List<Chunk> batch = vectorBackend.listAllChunks(offset, batchSize);
        if (batch.isEmpty()) break;

        List<String> texts = batch.stream()
            .map(Chunk::getChunkText).collect(toList());
        List<float[]> vecs = embedder.encode(texts);

        List<ChunkWithVectors> updated = IntStream.range(0, batch.size())
            .mapToObj(i -> ChunkWithVectors.builder()
                .chunk(batch.get(i))
                .vecChunk(toFloatList(vecs.get(i)))
                .build())
            .collect(toList());

        vectorBackend.upsertChunks(updated);

        total += batch.size();
        offset += batchSize;
        log.info("reembed 进度: {}/{} chunks", total, "...");
    }

    return ReembedResult.builder()
        .chunkCount(total)
        .elapsedMs(System.currentTimeMillis() - start)
        .build();
}
```

---

## 5. 新增数据模型

```java
@Data @Builder
public class ReembedResult {
    private int chunkCount;
    private long elapsedMs;
}
```

---

## 6. MCP 端点

```java
@PostMapping("/kb_reembed")
public ResponseEntity<McpToolResult> reembed(@RequestBody(required = false) Map<String, Object> params) {
    try {
        int batchSize = params != null
            ? (int) params.getOrDefault("batch_size", 100)
            : 100;
        ReembedResult result = kb.reembed(batchSize);
        return ResponseEntity.ok(McpToolResult.ok(Map.of(
            "chunk_count", result.getChunkCount(),
            "elapsed_ms",  result.getElapsedMs()
        )));
    } catch (Exception e) {
        return ResponseEntity.ok(McpToolResult.fail(e.getMessage()));
    }
}
```

### 更新 /mcp/tools 工具列表

新增工具描述：
```json
{
  "name": "kb_reembed",
  "description": "用当前 Embedding 模型重新生成所有向量（升级模型后使用）",
  "parameters": {
    "batch_size": "int (optional, default: 100)"
  }
}
```

---

## 7. 技术决策

### 7.1 同步响应 vs 异步任务

当前实现为同步，大知识库（万级 chunk）会超时。Phase 1 先实现同步，后续可改为异步任务 + 进度轮询。

### 7.2 为什么不重建 Lucene 索引？

Lucene 索引存储的是原始 chunk 文本（BM25 用），与向量模型无关。重新向量化不需要改 Lucene，只更新向量后端。

### 7.3 batchSize 默认 100

兼顾 Embedding 批处理效率（32~128 最佳）和内存占用（每批约 100×1024×4 = 400KB 向量数据）。
