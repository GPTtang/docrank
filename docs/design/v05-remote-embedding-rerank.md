# 系统设计: 远程 Embedding 与 Rerank API 支持

> **对应 PRD**：`docs/prd/v05-remote-embedding-rerank.md`
> **状态**：已批准
> **日期**：2026-03-10

---

## 1. 概述

新增 `RemoteEmbeddingProvider` 和 `RemoteReranker` 两个实现类，
通过 JDK HttpClient 调用外部 REST API。
分别由 `docrank.embedding.type: remote` 和 `docrank.reranker.type: remote` 激活，
与现有 `onnx` / `random` 模式完全并存。

---

## 2. 架构设计

### 2.1 接口实现树（新增部分）

```
EmbeddingProvider (interface)
  ├── OnnxEmbeddingProvider   (type: onnx)
  ├── RandomEmbeddingProvider (type: random)
  └── RemoteEmbeddingProvider (type: remote) ← 新增

Reranker (interface)
  ├── OnnxReranker            (type: onnx, enabled: true)
  ├── NoOpReranker            (enabled: false)
  └── RemoteReranker          (type: remote) ← 新增
       ├── provider: cohere
       └── provider: jina
```

### 2.2 HTTP 协议

**Embedding — OpenAI 兼容格式**
```
POST {base-url}/v1/embeddings
Headers: Authorization: Bearer {api-key}
Body:    {"model": "...", "input": ["text1", "text2"]}
Response:{"data": [{"embedding": [...], "index": 0}, ...]}
```

**Rerank — Cohere 格式**
```
POST {base-url}/v1/rerank
Headers: Authorization: Bearer {api-key}
Body:    {"model": "...", "query": "...", "documents": [...], "top_n": N}
Response:{"results": [{"index": 0, "relevance_score": 0.9}, ...]}
```

**Rerank — Jina 格式**（与 Cohere 相同，base-url 不同）

---

## 3. 核心接口与类设计

```java
// 新增实现
public class RemoteEmbeddingProvider implements EmbeddingProvider {
    // 字段：apiKey, model, baseUrl, dimension, httpClient, mapper
    // 方法：encode(List<String>) → POST /v1/embeddings
}

public class RemoteReranker implements Reranker {
    // 字段：provider(cohere|jina), apiKey, model, baseUrl, httpClient, mapper
    // 方法：rerank(query, candidates, topN) → POST /v1/rerank
}
```

---

## 4. 配置项

```yaml
docrank:
  embedding:
    type: remote             # onnx | random | remote
    dimension: 1536          # 必须与远程模型输出维度一致
    remote:
      base-url: https://api.openai.com   # 留空使用默认；可填 Ollama 等兼容地址
      api-key: ${OPENAI_API_KEY:}
      model: text-embedding-3-small

  reranker:
    enabled: true
    type: onnx               # onnx | remote（新增字段）
    top-n: 20
    remote:
      provider: cohere       # cohere | jina
      base-url:              # 留空使用默认
      api-key: ${COHERE_API_KEY:}
      model: rerank-multilingual-v3.0
```

---

## 5. 技术决策

| 方案 | 结论 |
|------|------|
| JDK HttpClient | ✅ 采用，零依赖 |
| 统一 RemoteReranker 处理 cohere/jina | ✅ 采用，两者格式相同，用 provider 区分 base-url 默认值 |
| Embedding 分批（超 API 限制） | ⏳ v0.6 处理，当前单次全量发送 |

---

## 6. 实现计划

| 阶段 | 任务 |
|------|------|
| Phase 1 | `RemoteEmbeddingProvider` + 配置项 |
| Phase 2 | `RemoteReranker`（cohere/jina）+ 配置项 |
| Phase 3 | `DocRankAutoConfiguration` 支持 remote 分支 |
| Phase 4 | `DocRankProperties` 新增 remote 嵌套属性 |
