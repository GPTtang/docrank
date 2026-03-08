# PRD: 高级评分系统

> **状态**：已实现（Phase 1.2）
> **模块**：docrank-core（search/AdvancedScorer）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

纯 RRF + Reranker 分数只反映语义相关性，忽略了内容的时效性（旧文档可能已过时）、重要度差异（关键文档应优先），以及结果多样性（同文档多个 chunk 占据前排）。

### 1.2 目标

在 Reranker 输出后增加后处理评分阶段，综合时效性、重要度、多样性三个维度，输出更贴合实际使用场景的最终排序。

### 1.3 非目标

- 不修改 BM25/向量/RRF/Reranker 本身的评分逻辑
- 不实现个性化推荐（无用户行为数据）

---

## 2. 用户故事

```
作为 AI Agent，
我希望检索结果优先返回最近更新的文档，而非陈旧内容，
以便获取最新的知识。
```

```
作为知识库管理员，
我希望为重要文档设置更高的 importance 值，
以便这类文档在相关性相近时优先被召回。
```

```
作为用户，
我希望 top-5 结果来自不同文档，而非同一篇文章的 5 个段落，
以便获取多角度的信息。
```

---

## 3. 功能需求

### 3.1 时效性衰减（Recency Boost）

- **P0** 基于 chunk 的 `updatedAt` 计算距今天数
- **P0** 指数衰减：`recencyBoost = exp(-lambda × daysSince)`，lambda 默认 0.005
- **P0** 含义：约每 200 天衰减到 37%，鼓励新内容

### 3.2 重要度加权（Importance Boost）

- **P0** 写入时支持设置 `importance`（0.0~1.0，默认 1.0）
- **P0** 最终分数：`finalScore = baseScore × (0.7 + 0.3 × recencyBoost × importance)`
- **P0** 70% 保留原始语义分，30% 受时效+重要度调节

### 3.3 分数阈值过滤（Score Threshold）

- **P0** 低于 `minScore` 的结果直接丢弃
- **P0** 默认 minScore=0.0（不过滤）

### 3.4 MMR 多样性降权

- **P0** 对同一 doc_id 的后续 chunk 按出现次数指数级降权
- **P0** 降权公式：`penalty = mmrPenalty^sameDocCount`，mmrPenalty 默认 0.85
- **P0** 同文档第 2 个 chunk 乘 0.85，第 3 个乘 0.72，以此类推
- **P1** mmrEnabled 可通过配置关闭

### 3.5 可配置性

- **P0** 所有参数（recencyLambda/minScore/mmrEnabled/mmrPenalty）通过 `application.yml` 控制

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 顺序 | 在 Reranker 之后、最终截取 topK 之前执行 |
| 性能 | O(n log n) 排序，n = reranker 输出候选数（通常 ≤ topK×3） |
| 可关闭 | mmr 可单独关闭，评分逻辑不影响 BM25/向量/RRF |

---

## 5. 验收标准

- [ ] AC1：写入 importance=0.1 和 importance=1.0 的同内容文档，搜索时高 importance 排序更靠前
- [ ] AC2：写入 updatedAt=3 年前的文档，搜索时排序低于刚写入的相似内容
- [ ] AC3：同一文档有 10 个 chunk 时，topK=5 结果中不超过 2 个来自同一文档（mmr 效果）
- [ ] AC4：设置 minScore=0.5 时，低相关文档不出现在结果中

---

## 6. 参考资料

- ROADMAP.md Phase 1.2 Advanced Scoring Pipeline
- MMR（Maximal Marginal Relevance）论文
