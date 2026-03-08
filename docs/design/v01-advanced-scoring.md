# 系统设计: 高级评分系统

> **对应 PRD**：`docs/prd/v01-advanced-scoring.md`
> **状态**：已实现（Phase 1.2）
> **模块**：docrank-core（`search/AdvancedScorer`）

---

## 1. 概述

`AdvancedScorer` 是 Reranker 之后的最后一个评分阶段，对 SearchResult 列表做三步处理：（1）时效性+重要度加权计算最终分，（2）低分过滤，（3）MMR 多样性降权，最终截取 topK。

---

## 2. 在检索链路中的位置

```
HybridSearcher
  Stage 4: OnnxReranker.rerank() → List<SearchResult>(topK*3)
  Stage 5: AdvancedScorer.score() → List<SearchResult>(topK)  ← 此处
```

---

## 3. 核心实现

### 3.1 类结构

```java
public class AdvancedScorer {
    private final double recencyLambda;    // 默认 0.005
    private final double minScore;         // 默认 0.0
    private final boolean mmrEnabled;      // 默认 true
    private final double mmrPenalty;       // 默认 0.85

    public List<SearchResult> score(List<SearchResult> results, int topK)
}
```

### 3.2 评分流程（score 方法）

```java
public List<SearchResult> score(List<SearchResult> results, int topK) {
    // Step 1: 计算每个结果的最终分
    List<SearchResult> scored = results.stream().map(r -> {
        Chunk chunk = r.getChunk();

        // Recency Boost
        long daysSince = ChronoUnit.DAYS.between(
            chunk.getUpdatedAt(), Instant.now());
        double recencyBoost = Math.exp(-recencyLambda * daysSince);

        // Final Score = baseScore * (0.7 + 0.3 * recencyBoost * importance)
        double finalScore = r.getScore()
            * (0.7 + 0.3 * recencyBoost * chunk.getImportance());

        return r.withScore(finalScore);
    }).collect(toList());

    // Step 2: 过滤低分
    scored = scored.stream()
        .filter(r -> r.getScore() >= minScore)
        .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
        .collect(toList());

    // Step 3: MMR 多样性降权
    if (mmrEnabled) {
        Map<String, Integer> docCount = new HashMap<>();
        scored = scored.stream().map(r -> {
            String docId = r.getChunk().getDocId();
            int count = docCount.getOrDefault(docId, 0);
            docCount.put(docId, count + 1);
            if (count > 0) {
                double penalty = Math.pow(mmrPenalty, count);
                return r.withScore(r.getScore() * penalty);
            }
            return r;
        }).collect(toList());

        // MMR 后重新排序
        scored.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
    }

    // Step 4: 截取 topK
    return scored.stream().limit(topK).collect(toList());
}
```

---

## 4. 评分公式详解

### 4.1 时效性衰减

```
daysSince  = days(now - chunk.updatedAt)
recencyBoost = e^(-0.005 × daysSince)

典型值:
  0 天:   boost = 1.000（刚写入）
  30 天:  boost = 0.861
  100 天: boost = 0.607
  200 天: boost = 0.368
  365 天: boost = 0.160
```

### 4.2 最终分数

```
finalScore = baseScore × (0.7 + 0.3 × recencyBoost × importance)

极端情况:
  importance=1.0, 刚写入:  finalScore = baseScore × (0.7 + 0.3×1×1) = baseScore × 1.0
  importance=1.0, 365天后: finalScore = baseScore × (0.7 + 0.3×0.16×1) = baseScore × 0.748
  importance=0.0, 任何时间: finalScore = baseScore × 0.7（固定 30% 降权）
```

### 4.3 MMR 降权

```
同一 doc_id 的第 N 个 chunk（N 从 1 计数）:
  penalty = mmrPenalty^(N-1)

示例（mmrPenalty=0.85）:
  第1个 chunk: penalty = 0.85^0 = 1.00（不降权）
  第2个 chunk: penalty = 0.85^1 = 0.85
  第3个 chunk: penalty = 0.85^2 = 0.72
  第4个 chunk: penalty = 0.85^3 = 0.61
```

---

## 5. 配置项

```yaml
docrank:
  scoring:
    recency-lambda: 0.005      # 时间衰减系数（越大衰减越快）
    min-score: 0.0             # 低于此分的结果被过滤（0=不过滤）
    mmr-enabled: true          # 是否启用 MMR 多样性
    mmr-penalty: 0.85          # 同文档后续 chunk 的降权系数
```

---

## 6. 技术决策

### 6.1 为什么用指数衰减而非线性衰减？

指数衰减符合"信息半衰期"的直觉：刚写入的文档快速建立权威，随时间缓慢降低，而非线性归零。lambda=0.005 意味着约 139 天后衰减到 50%，符合大多数知识库场景。

### 6.2 为什么是 70% + 30% 而非直接相乘？

纯乘法会在 importance=0 时将分数清零，使文档完全不可被召回，与"低重要度只是降权"的意图不符。保留 70% 基础分保证了任何写入的文档都有被召回的机会。

### 6.3 为什么 MMR 在最终排序而非 RRF 阶段？

RRF 阶段需要保留所有候选用于 Reranker，若过早做 MMR 可能丢失好的候选。在 Reranker 之后做 MMR 保证了语义质量，再保证多样性。
