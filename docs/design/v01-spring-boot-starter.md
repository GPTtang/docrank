# 系统设计: Spring Boot Starter & 自动配置

> **对应 PRD**：`docs/prd/v01-spring-boot-starter.md`
> **状态**：已实现（v0.1）
> **模块**：docrank-spring-boot-starter

---

## 1. 概述

`docrank-spring-boot-starter` 通过 `DocRankAutoConfiguration` 实现所有核心 Bean 的条件装配，通过 `DocRankProperties` 将所有可调参数映射到 `application.yml`。符合 Spring Boot Starter 标准约定。

---

## 2. Bean 装配顺序

```
DocRankProperties (配置绑定)
    ↓
LanguageDetector         (无依赖)
MultiLingualAnalyzerFactory (无依赖)
ParserRegistry           (无依赖)
    ↓
IndexBackend             (依赖 props.backend.*)
    → LanceDBBackend | QdrantBackend | InMemoryBackend
BM25Index (LuceneBM25Index) (依赖 props.lucene.*)
EmbeddingProvider (OnnxEmbeddingProvider) (依赖 props.embedding.*)
Reranker (OnnxReranker)  (依赖 props.reranker.*)
    ↓
ChunkingService          (依赖 LanguageDetector, props.chunk.*)
AdvancedScorer           (依赖 props.scoring.*)
    ↓
HybridSearcher           (依赖 BM25Index, IndexBackend, EmbeddingProvider, Reranker, AdvancedScorer)
    ↓
KnowledgeBaseService     (依赖 所有上层 Bean)
    ↓
DocRankMcpServer         (依赖 KnowledgeBaseService)
```

---

## 3. 配置项完整清单

```yaml
docrank:
  backend:
    type: lancedb                       # lancedb | qdrant | memory
    lancedb:
      host: localhost
      port: 8181
      table-name: docrank_memories
    qdrant:
      host: localhost
      port: 6333
      collection-name: docrank_memories

  embedding:
    dimension: 1024                     # BGE-M3 输出维度
    batch-size: 32                      # 批量向量化大小
    onnx:
      model-path: /opt/docrank/models/bge-m3

  reranker:
    enabled: true
    top-n: 20                           # 送入精排的候选数
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3

  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64

  chunk:
    size: 512                           # CJK=字符数，EN=词数
    overlap: 64

  language:
    default-lang: auto                  # auto | zh | ja | en

  scoring:
    recency-lambda: 0.005              # 时间衰减系数
    min-score: 0.0                     # 分数阈值
    mmr-enabled: true
    mmr-penalty: 0.85

  ingest:
    default-scope: global
    batch-threads: 4
```

---

## 4. 核心实现

### 4.1 后端条件装配

```java
@Bean
@ConditionalOnMissingBean(IndexBackend.class)
public IndexBackend indexBackend(DocRankProperties props) {
    return switch (props.getBackend().getType().toLowerCase()) {
        case "qdrant" -> new QdrantBackend(
            props.getBackend().getQdrant().getHost(),
            props.getBackend().getQdrant().getPort(),
            props.getBackend().getQdrant().getCollectionName(),
            props.getEmbedding().getDimension());
        case "memory" -> new InMemoryBackend();
        default -> new LanceDBBackend(
            props.getBackend().getLancedb().getHost(),
            props.getBackend().getLancedb().getPort(),
            props.getBackend().getLancedb().getTableName(),
            props.getEmbedding().getDimension());
    };
}
```

### 4.2 Spring Boot 标准注册

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
```
com.memo.docrank.autoconfigure.DocRankAutoConfiguration
```

---

## 5. 技术决策

### 5.1 @ConditionalOnMissingBean

所有 Bean 均加 `@ConditionalOnMissingBean`，允许用户覆盖任意组件（如自定义 Parser、自定义评分器）而不修改 Starter 代码。

### 5.2 配置前缀统一为 `docrank`

避免与其他框架配置冲突，所有参数有合理默认值，最简配置只需提供模型路径和 Lucene 索引路径。

### 5.3 不强制 LanceDB

`backend.type=memory` 时无任何外部依赖，适合单元测试和 CI 环境。
