# 系统设计: 写入去重

> **对应 PRD**：`docs/prd/v03-deduplication.md`
> **状态**：待实现（Phase 1.4）

---

## 1. 概述

在 `KnowledgeBaseService.indexDocument()` 的写入流程中，对每个 chunk 向量化后先做 top-1 向量检索，若最近邻相似度 ≥ 阈值则跳过该 chunk，否则正常写入。默认关闭，配置开启。

---

## 2. 在写入流程中的位置

```
ParsedDocument → chunks → 向量化（批量）
    ↓
[新增] 去重检测（每个 chunk）：
    top-1 向量检索 → 相似度 ≥ threshold → 跳过
    相似度 < threshold → 正常写入
    ↓
双写：vectorBackend.upsertChunks() + bm25Index.addChunks()
    ↓
IngestResult（含 skippedChunks 字段）
```

---

## 3. 核心实现

### 3.1 配置

```yaml
docrank:
  ingest:
    dedup-enabled: false          # 默认关闭
    dedup-threshold: 0.95         # 余弦相似度阈值
```

```java
// DocRankProperties.IngestProps 新增字段
private boolean dedupEnabled = false;
private double dedupThreshold = 0.95;
```

### 3.2 IngestResult 新增字段

```java
public class IngestResult {
    // 原有字段
    private String docId;
    private String title;
    private int chunkCount;        // 实际写入的 chunk 数
    private boolean success;
    private String error;

    // 新增
    private int skippedChunks;     // 被去重跳过的 chunk 数
}
```

### 3.3 去重逻辑（KnowledgeBaseService）

```java
private boolean isDuplicate(float[] vec) {
    if (!props.getIngest().isDedupEnabled()) return false;

    List<RecallCandidate> nearest = vectorBackend.vectorSearch(vec, 1, Map.of());
    if (nearest.isEmpty()) return false;

    double similarity = nearest.get(0).getScore();  // 余弦相似度 [0, 1]
    return similarity >= props.getIngest().getDedupThreshold();
}

// 在 indexDocument 中调用
private IngestResult indexDocument(ParsedDocument parsed, List<String> tags) {
    List<Chunk> chunks = chunker.chunk(...);
    List<float[]> vecs = embedder.encode(texts);

    List<ChunkWithVectors> toWrite = new ArrayList<>();
    int skipped = 0;

    for (int i = 0; i < chunks.size(); i++) {
        if (isDuplicate(vecs.get(i))) {
            log.debug("跳过重复 chunk: {} (index={})", chunks.get(i).getTitle(), i);
            skipped++;
        } else {
            toWrite.add(ChunkWithVectors.builder()
                .chunk(chunks.get(i))
                .vecChunk(toFloatList(vecs.get(i)))
                .build());
        }
    }

    if (!toWrite.isEmpty()) {
        vectorBackend.upsertChunks(toWrite);
        bm25Index.addChunks(toWrite.stream().map(ChunkWithVectors::getChunk).collect(toList()));
    }

    return IngestResult.builder()
        .docId(parsed.getDocId())
        .title(parsed.getTitle())
        .chunkCount(toWrite.size())
        .skippedChunks(skipped)
        .success(true)
        .build();
}
```

---

## 4. MCP 响应变更

`kb_ingest` / `kb_ingest_file` 响应新增 `skipped_chunks` 字段：

```json
{
  "success": true,
  "data": {
    "doc_id": "uuid",
    "title": "文档标题",
    "chunk_count": 3,
    "skipped_chunks": 2
  }
}
```

---

## 5. 技术决策

### 5.1 为什么在向量化之后做去重而非之前？

需要向量才能做相似度比较，无法提前。且向量化是批量的（batchSize=32），成本已平摊，额外的 top-1 检索成本相对较低。

### 5.2 为什么默认关闭？

去重会增加每个 chunk 一次 top-1 检索的延迟，对于首次大批量导入影响明显。用户需要权衡质量与速度，显式开启更合理。

### 5.3 阈值 0.95 的依据

BGE-M3 的余弦相似度在 0.95 以上通常意味着内容几乎完全相同（例如同一段话的轻微改写）。0.9 以下可能误判语义相似但内容不同的 chunk。
