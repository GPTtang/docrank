# 系统设计: 多向量存储后端

> **对应 PRD**：`docs/prd/v01-multi-vector-backend.md`
> **状态**：已实现（v0.1/Phase 1.1）
> **模块**：docrank-core（store 包）

---

## 1. 概述

通过 `IndexBackend` 接口统一抽象向量存储操作，三种实现（InMemory/LanceDB/Qdrant）对上层透明，切换只需修改一行 YAML。

---

## 2. 接口定义

```java
public interface IndexBackend {
    void createIndex();
    void deleteIndex();

    void upsertChunks(List<ChunkWithVectors> chunks);
    void deleteByDocId(String docId);
    void deleteByScope(String scope);

    List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters);
    List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters);

    boolean isHealthy();
    long countChunks();
}
```

---

## 3. 各后端实现

### 3.1 InMemoryBackend（开发/测试）

**数据结构**：
```java
private final Map<String, ChunkWithVectors> store = new ConcurrentHashMap<>();
```

**向量检索**：暴力余弦相似度 O(n)
```java
double cosineSimilarity(float[] a, List<Float> bList) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
        dot  += a[i] * bList.get(i);
        normA += a[i] * a[i];
        normB += bList.get(i) * bList.get(i);
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

**关键词检索**：`chunkText.contains(query)` 简单匹配

**适用场景**：单元测试、小型演示（< 1000 chunks）

---

### 3.2 LanceDBBackend（默认生产）

**依赖**：LanceDB HTTP API（`pip install lancedb && lancedb --host 0.0.0.0 --port 8181`）

**HTTP 调用映射**：

| 操作 | HTTP 方法 | 路径 | 说明 |
|------|-----------|------|------|
| upsert | POST | `/v1/table/{name}/insert/` | body: `{data: rows, mode: "overwrite"}` |
| vectorSearch | POST | `/v1/table/{name}/query/` | body: `{vector: [], query_type: "vector", metric: "cosine", limit: N}` |
| keywordSearch | POST | `/v1/table/{name}/query/` | body: `{query: "text", query_type: "fts", limit: N}` |
| deleteByDocId | POST | `/v1/table/{name}/delete/` | body: `{where: "doc_id = 'xxx'"}` |
| deleteByScope | POST | `/v1/table/{name}/delete/` | body: `{where: "scope = 'xxx'"}` |
| isHealthy | GET | `/v1/table/` | HTTP 200 = 健康 |
| countChunks | GET | `/v1/table/{name}/` | 返回 row_count |

**filter 支持**：在 query body 中追加 `filter: "scope = 'xxx'"` 字符串

---

### 3.3 QdrantBackend（生产可选）

**依赖**：Qdrant REST API（`docker run -p 6333:6333 qdrant/qdrant`）

**Point ID 生成**：`UUID.nameUUIDFromBytes(chunkId.getBytes())` → 保证同 chunkId 幂等

**HTTP 调用映射**：

| 操作 | HTTP 方法 | 路径 | 说明 |
|------|-----------|------|------|
| upsert | PUT | `/collections/{name}/points` | body: `{points: [{id, vector, payload}]}` |
| vectorSearch | POST | `/collections/{name}/points/search` | body: `{vector: [], limit: N, with_payload: true}` |
| keywordSearch | — | — | **不支持**，返回空列表（由 Lucene 负责） |
| deleteByDocId | POST | `/collections/{name}/points/delete` | filter: `{must: [{key: "doc_id", match: {value: "xxx"}}]}` |
| deleteByScope | POST | `/collections/{name}/points/delete` | filter: scope 条件 |
| isHealthy | GET | `/collections/{name}` | HTTP 200 = 健康 |
| countChunks | GET | `/collections/{name}` | 返回 vectors_count |

**Payload 结构**：`{chunk_id, doc_id, title, chunk_text, scope, importance, expires_at, updated_at, language}`

**注意**：Qdrant 不支持全文检索，`keywordSearch` 返回空列表，全文检索由 Lucene 独立负责。

---

## 4. filter 统一约定

上层传入 `Map<String, Object> filters`，各后端独立解析：

| filter key | 说明 | InMemory | LanceDB | Qdrant |
|------------|------|----------|---------|--------|
| `scope` | scope 隔离 | Java stream filter | SQL where | must match |
| `tags` | 标签过滤 | contains check | where contains | must match |

---

## 5. 技术决策

### 5.1 Qdrant 不支持 FTS 的处理

Qdrant 缺乏全文检索能力，`keywordSearch` 返回空列表而非抛异常。这样混合检索中 BM25 部分完全由 Lucene 承担，向量部分由 Qdrant 承担，两路都能正常参与 RRF 融合。

### 5.2 LanceDB vs Qdrant 选型建议

| 维度 | LanceDB | Qdrant |
|------|---------|--------|
| FTS 支持 | ✅（结合 Lucene） | ❌（纯向量） |
| 部署复杂度 | pip install | Docker |
| 生产成熟度 | 较新 | 成熟 |
| 推荐场景 | 默认选择 | 已有 Qdrant 集群 |

### 5.3 upsert 语义

所有后端均实现 upsert（写入已存在的 chunk_id 时覆盖），保证重复写入同一文档的幂等性。
