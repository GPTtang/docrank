# PRD: 远程 Embedding 与 Rerank API 支持

> **状态**：已批准
> **版本**：v0.5
> **作者**：DocRank Team
> **日期**：2026-03-10

---

## 1. 背景与目标

### 1.1 问题陈述

DocRank 目前的 Embedding 和 Reranker 都依赖本地 ONNX 模型文件（BGE-M3 ~1.2GB、bge-reranker-v2-m3 ~1.1GB）。
这带来以下痛点：
- 首次部署需要下载大量模型文件，门槛高
- 资源受限环境（低内存服务器、云函数）无法运行 ONNX 推理
- 用户可能已订阅 OpenAI / Cohere / Jina 等服务，希望直接复用

### 1.2 目标

新增远程 API 调用模式：
- `docrank.embedding.type: remote` — 调用 OpenAI 兼容的 Embedding API
- `docrank.reranker.type: remote` — 调用 Cohere / Jina 兼容的 Rerank API
- 使用 JDK 原生 HttpClient，无额外依赖
- 与现有 `onnx` / `random` 模式并存，通过配置切换

### 1.3 非目标

- 不做流式 Embedding
- 不做 Embedding 结果缓存（v0.6+ 考虑）
- 不支持 batch 并发（顺序调用，简单可靠）

---

## 2. 用户故事

```
作为开发者，
我希望将 docrank.embedding.type 设置为 remote 并填入 OpenAI API Key，
以便无需下载 ONNX 模型即可使用语义检索。

作为使用 Cohere/Jina 的用户，
我希望将 reranker 切换为远程 API，
以便获得更好的重排序效果而无需本地 GPU。
```

---

## 3. 功能需求

### 3.1 核心功能（P0）

- [x] `RemoteEmbeddingProvider` — 调用 OpenAI 兼容 `/v1/embeddings` 接口
- [x] `RemoteReranker` — 调用 Cohere/Jina 兼容 `/v1/rerank` 接口
- [x] `docrank.embedding.type: remote` 配置项
- [x] `docrank.reranker.type: remote` 配置项（与 `enabled` 独立）
- [x] API Key 支持从配置或环境变量读取

### 3.2 扩展功能（P1）

- [ ] 支持 Azure OpenAI Embedding（P1）
- [ ] Embedding 请求自动 batch 分割（超出 API 限制时）（P1）

---

## 4. 验收标准

- [ ] AC1：设置 `embedding.type: remote` + OpenAI Key，ingest 文档后 search 返回结果
- [ ] AC2：设置 `reranker.type: remote` + Cohere Key，search 返回经过重排序的结果
- [ ] AC3：API Key 缺失时启动输出 WARN，调用时返回明确错误
- [ ] AC4：`base-url` 留空时使用各服务默认地址；填入自定义地址时使用自定义地址

---

## 5. 依赖与风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| 外部 API 限流 | 中 | 中 | 明确错误信息，不自动重试 |
| 向量维度不匹配 | 低 | 高 | 配置 `dimension` 必须与模型输出一致，启动时 WARN |
| Rerank API 格式差异 | 低 | 中 | 抽象 RemoteReranker，provider 字段区分 cohere/jina |
