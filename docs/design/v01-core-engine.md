# 系统设计: 核心检索引擎

> **对应 PRD**：`docs/prd/v01-core-engine.md`
> **状态**：已实现（v0.1）
> **模块**：docrank-core, docrank-memory

---

## 1. 概述

核心引擎分两条链路：**写入链路**（文档解析 → 分块 → 向量化 → 双写索引）和**检索链路**（并行两路召回 → RRF 融合 → ONNX 精排 → 高级评分）。所有 AI 推理（Embedding、Reranker）均通过 ONNX Runtime 本地运行。

---

## 2. 架构设计

### 2.1 整体架构

```
写入链路:
  InputStream / String
      ↓ ParserRegistry（按扩展名/MIME路由）
      ↓ DocumentParser（PDF/MD/HTML/DOCX/JSON/TXT）
      ↓ ParsedDocument（title + sections[]）
      ↓ ChunkingService（语言感知滑窗分块）
      ↓ List<Chunk>
      ↓ OnnxEmbeddingProvider（BGE-M3 批量推理）
      ↓ List<ChunkWithVectors>
      ├─→ LanceDBBackend.upsertChunks()   (向量索引)
      └─→ LuceneBM25Index.addChunks()     (倒排索引)

检索链路:
  query (String)
      ├─ CompletableFuture: LuceneBM25Index.search()     → List<RecallCandidate>(KEYWORD)
      └─ CompletableFuture: EmbeddingProvider.encodeSingle()
                            + IndexBackend.vectorSearch() → List<RecallCandidate>(VECTOR)
      ↓ 过滤过期 (TTL)
      ↓ reciprocalRankFusion() (K=60)    → List<FusedCandidate>
      ↓ OnnxReranker.rerank()            → List<SearchResult>
      ↓ AdvancedScorer.score()           → List<SearchResult> (topK)
```

### 2.2 数据流（写入）

```
原始文档 → ParsedDocument(sections) → Chunk(含scope/importance/TTL)
        → ChunkWithVectors(float[1024]) → 双写
```

### 2.3 数据流（检索）

```
query → [BM25召回(topK*5)] + [向量召回(topK*5)] → RRF融合
      → Reranker精排(topK*3) → AdvancedScorer后处理 → topK结果
```

---

## 3. 模块设计

### 3.1 核心类职责

| 类 | 职责 |
|----|------|
| `ParserRegistry` | 按扩展名/MIME 路由到对应 Parser |
| `DocumentParser` (接口) | 将 InputStream 解析为 ParsedDocument |
| `ChunkingService` | 语言感知滑窗分块 |
| `LanguageDetector` | 中/日/英自动检测（Lingua） |
| `OnnxEmbeddingProvider` | BGE-M3 本地 ONNX 推理，批量向量化 |
| `LuceneBM25Index` | Lucene 多字段 BM25 索引（写/读/删）|
| `IndexBackend` (接口) | 向量存储抽象（LanceDB/Qdrant/InMemory）|
| `HybridSearcher` | 并行召回 + RRF + Reranker + AdvancedScorer 编排 |
| `OnnxReranker` | bge-reranker-v2-m3 本地 ONNX 推理 |
| `AdvancedScorer` | 时效性/重要度/MMR 后处理评分 |
| `KnowledgeBaseService` | 对外高层 API（ingest/search/delete/stats）|

---

## 4. 数据模型

### 4.1 核心数据类

```java
// 原始分块
Chunk {
    String chunkId;          // UUID
    String docId;            // 文档 ID
    String title;            // 文档标题
    String sectionPath;      // 章节路径（如 "第1章 > 1.2"）
    String chunkText;        // 块内容
    int chunkIndex;          // 块在文档中的序号
    List<String> tags;
    Language language;       // CHINESE / JAPANESE / ENGLISH / UNKNOWN
    Instant updatedAt;
    String scope;            // 默认 "global"
    double importance;       // 默认 1.0，范围 [0, 1]
    Instant expiresAt;       // null 表示永不过期
}

// 块 + 向量
ChunkWithVectors {
    Chunk chunk;
    List<Float> vecChunk;    // 1024 维，L2 归一化
}

// 召回候选（单路）
RecallCandidate {
    Chunk chunk;
    double score;
    RecallSource source;     // KEYWORD | VECTOR
}

// RRF 融合候选
FusedCandidate {
    Chunk chunk;
    double sparseScore;      // Lucene BM25 原始分
    double vectorScore;      // 向量余弦相似度
    double fusedScore;       // RRF 融合分
    int sparseRank;
    int vectorRank;
}

// 最终搜索结果
SearchResult {
    Chunk chunk;
    double score;            // 最终分（AdvancedScorer 输出）
    double sparseScore;
    double vectorScore;
    double rerankScore;
}
```

### 4.2 Lucene 字段定义

| 字段名 | 类型 | 分词 | 用途 |
|--------|------|------|------|
| `chunk_id` | StringField | 否 | 主键 |
| `doc_id` | StringField | 否 | 文档标识 |
| `title` | TextField | 是 | BM25，boost=3.0 |
| `chunk_text` | TextField | 是 | BM25，boost=1.0 |
| `tags` | StringField | 否 | 多值，过滤用 |
| `language` | StringField | 否 | 存储，过滤用 |
| `section_path` | StringField | 否 | 存储 |
| `scope` | StringField | 否 | 隔离过滤 |
| `importance` | StoredField | 否 | 存储，评分用 |
| `expires_at` | StringField | 否 | TTL 过滤 |
| `updated_at` | StringField | 否 | 时效性衰减用 |

### 4.3 LanceDB 表结构

```json
{
  "chunk_id":    "utf8",
  "doc_id":      "utf8",
  "title":       "utf8",
  "section_path":"utf8",
  "chunk_text":  "utf8",
  "vec_chunk":   "fixed_size_list<float32>[1024]",
  "language":    "utf8",
  "updated_at":  "utf8",
  "scope":       "utf8",
  "importance":  "float64",
  "expires_at":  "utf8 (nullable)"
}
```

---

## 5. 关键算法

### 5.1 语言感知分块

```
1. LanguageDetector.detect(text) → Language
2. 选择分句规则：
   - CHINESE/JAPANESE: (?<=[。！？!?])
   - ENGLISH: (?<=[.!?])\s+
3. 滑窗合并句子：
   - 按 Language 计算 textLength（CJK=字符数，EN=词数）
   - 累积到超过 chunkSize → 输出块，保留末 overlap 字符重叠
4. 返回 List<Chunk>
```

### 5.2 RRF 融合（K=60）

```
score(candidate) = sum(1 / (K + rank_in_source))
                   对其出现的所有召回源累加

示例：BM25 排名 3，向量排名 7
  fusedScore = 1/(60+3) + 1/(60+7) = 0.0159 + 0.0149 = 0.0308
```

### 5.3 Reranker 推理

```
对每个 FusedCandidate:
  pair = tokenize(query, chunkText)
  logit = onnx_predict(pair)[0]
  rerankScore = sigmoid(logit)  ∈ [0, 1]
  finalScore = 0.6 * rerankScore + 0.4 * fusedScore
按 finalScore 排序，取 topK*3 → 进入 AdvancedScorer
```

### 5.4 BGE-M3 向量化

```
1. HuggingFaceTokenizer.batchEncode(texts)
2. 构造 input_ids / attention_mask / token_type_ids 张量
3. ONNX 推理 → hidden_states[batch, seq_len, 1024]
4. 取 [CLS] token (index 0) → float[1024]
5. L2 归一化（防止余弦相似度退化）
```

---

## 6. API 设计

### KnowledgeBaseService 公开方法

```java
// 写入
IngestResult ingestText(String title, String content, List<String> tags,
                        Map<String, String> metadata)
IngestResult ingestText(String title, String content, List<String> tags,
                        Map<String, String> metadata,
                        String scope, double importance, Instant expiresAt)

IngestResult ingestFile(InputStream input, String filename,
                        List<String> tags, Map<String, String> metadata)
IngestResult ingestFile(InputStream input, String filename,
                        List<String> tags, Map<String, String> metadata,
                        String scope, double importance, Instant expiresAt)

// 检索
List<SearchResult> search(String query, int topK, Map<String, Object> filters)
List<SearchResult> search(String query, int topK, String scope,
                          Map<String, Object> extraFilters)

// 删除
void delete(String docId)
void deleteByScope(String scope)

// 统计
long vectorCount()
long bm25Count()
boolean isHealthy()
```

---

## 7. 技术决策

### 7.1 为什么用 ONNX Runtime 而非调用云 API？

- 零网络延迟、零成本、零数据泄露
- BGE-M3 官方提供 ONNX 格式，直接可用

### 7.2 为什么 BM25 + 向量双索引而非只用向量？

- BM25 对精确关键词匹配更好（产品型号、专有名词）
- 向量对语义相似更好（同义词、上下位词）
- RRF 融合充分利用两者优势，NDCG 提升明显

### 7.3 为什么分块保留 Section 结构？

- 按文档章节分块（而非固定滑窗）保留了语义完整性
- SectionPath 在结果中提供位置上下文，方便 Agent 定位原文

### 7.4 双写一致性策略

- 向量后端和 Lucene 在同一个线程顺序写入
- 无事务保护：若向量写入成功但 Lucene 写入失败，下次重新写入同 chunk_id 时 Lucene upsert 覆盖
- 这种弱一致性在搜索场景可接受

---

## 8. 测试策略

### 8.1 单元测试（已有）

- `LanguageDetectorTest`：中/日/英检测准确性
- `ChunkingServiceTest`：分块边界、overlap、语言切换
- `LuceneBM25IndexTest`：写入/检索/删除
- `InMemoryBackendTest`：向量 upsert/search/delete
- `HybridSearcherTest`：RRF 融合逻辑
- `AdvancedScorerTest`：时效性/MMR 计算
- `JsonParserTest`/`MarkdownParserTest`/`TextParserTest`：各格式解析

### 8.2 需补充的测试

- [ ] PDF/HTML/DOCX 解析（需测试文件）
- [ ] KnowledgeBaseService 集成测试（需启动 LanceDB）
- [ ] OnnxEmbeddingProvider 推理测试（需模型文件）
