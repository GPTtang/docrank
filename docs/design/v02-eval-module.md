# 系统设计: 检索质量评估模块

> **对应 PRD**：`docs/prd/v02-eval-module.md`
> **状态**：已实现（Phase 2.4）
> **模块**：docrank-eval

---

## 1. 概述

评估模块由三层组成：数据层（EvalDataset/EvalQuery 的 JSON 加载）、指标层（EvalMetrics 纯函数计算）、服务层（EvaluationService 编排）。完全无 Spring 依赖，可独立使用。

---

## 2. 模块结构

```
docrank-eval
  ├── model/
  │   ├── EvalQuery      → 单条查询 + graded relevance 标注
  │   ├── EvalDataset    → 查询集合，支持 JSON 加载
  │   ├── EvalResult     → 单条查询的指标结果
  │   └── EvalReport     → 所有查询的聚合报告
  ├── EvalMetrics        → 静态指标计算工具类
  └── EvaluationService  → 编排评估流程
```

---

## 3. 数据模型

### 3.1 EvalDataset JSON 格式

```json
{
  "name": "docrank-bench-v1",
  "description": "中文技术文档检索基准",
  "queries": [
    {
      "id": "q1",
      "query": "Spring Boot 自动配置原理",
      "relevant": { "doc-abc": 3, "doc-xyz": 1 },
      "scope": "spring-boot"
    }
  ]
}
```

### 3.2 EvalQuery

```java
public class EvalQuery {
    private String id;
    private String query;
    private Map<String, Integer> relevant;   // doc_id → grade [0-3]
    private String scope;                    // 可选 scope 过滤

    public int relevantCount() {
        return (int) relevant.values().stream().filter(g -> g > 0).count();
    }

    public int gradeOf(String docId) {
        return relevant.getOrDefault(docId, 0);
    }
}
```

---

## 4. 指标计算（EvalMetrics）

### 4.1 NDCG@K

```java
public static double ndcg(List<String> ranked, EvalQuery query, int k) {
    return dcg(ranked, query, k) / idealDcg(query, k);
}

private static double dcg(List<String> ranked, EvalQuery query, int k) {
    double dcg = 0;
    for (int i = 0; i < Math.min(k, ranked.size()); i++) {
        int grade = query.gradeOf(ranked.get(i));
        dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    return dcg;
}

private static double idealDcg(EvalQuery query, int k) {
    // 将所有 relevant grade 降序排列，计算理想 DCG
    List<Integer> grades = new ArrayList<>(query.getRelevant().values());
    grades.sort(Comparator.reverseOrder());
    double idcg = 0;
    for (int i = 0; i < Math.min(k, grades.size()); i++) {
        idcg += (Math.pow(2, grades.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    return idcg == 0 ? 1.0 : idcg;  // 避免除以零
}
```

### 4.2 Recall@K

```java
public static double recall(List<String> ranked, EvalQuery query, int k) {
    int total = query.relevantCount();
    if (total == 0) return 1.0;
    long hits = ranked.stream().limit(k)
        .filter(id -> query.gradeOf(id) > 0).count();
    return (double) hits / total;
}
```

### 4.3 Reciprocal Rank

```java
public static double reciprocalRank(List<String> ranked, EvalQuery query) {
    for (int i = 0; i < ranked.size(); i++) {
        if (query.gradeOf(ranked.get(i)) > 0) {
            return 1.0 / (i + 1);
        }
    }
    return 0.0;
}
```

### 4.4 Average Precision@K

```java
public static double averagePrecision(List<String> ranked, EvalQuery query, int k) {
    int total = query.relevantCount();
    if (total == 0) return 0.0;

    double ap = 0;
    int hits = 0;
    for (int i = 0; i < Math.min(k, ranked.size()); i++) {
        if (query.gradeOf(ranked.get(i)) > 0) {
            hits++;
            ap += (double) hits / (i + 1);  // Precision@(i+1)
        }
    }
    return ap / total;
}
```

---

## 5. EvaluationService 流程

```java
public EvalReport evaluate(EvalDataset dataset, int retrieveK) {
    List<EvalResult> results = dataset.getQueries().stream()
        .map(query -> evaluateSingle(query, retrieveK))
        .collect(toList());
    return aggregate(dataset.getName(), results);
}

private EvalResult evaluateSingle(EvalQuery query, int k) {
    // 1. 检索
    List<SearchResult> searchResults = kb.search(
        query.getQuery(), k, query.getScope(), Map.of());
    List<String> rankedDocIds = searchResults.stream()
        .map(r -> r.getChunk().getDocId())
        .collect(toList());

    // 2. 计算各指标
    return EvalResult.builder()
        .queryId(query.getId())
        .queryText(query.getQuery())
        .retrievedDocIds(rankedDocIds)
        .totalRelevant(query.relevantCount())
        .ndcgAt5(EvalMetrics.ndcg(rankedDocIds, query, 5))
        .ndcgAt10(EvalMetrics.ndcg(rankedDocIds, query, 10))
        .ndcgAt20(EvalMetrics.ndcg(rankedDocIds, query, 20))
        .recallAt5(EvalMetrics.recall(rankedDocIds, query, 5))
        .recallAt10(EvalMetrics.recall(rankedDocIds, query, 10))
        .recallAt20(EvalMetrics.recall(rankedDocIds, query, 20))
        .precisionAt5(EvalMetrics.precision(rankedDocIds, query, 5))
        .precisionAt10(EvalMetrics.precision(rankedDocIds, query, 10))
        .reciprocalRank(EvalMetrics.reciprocalRank(rankedDocIds, query))
        .averagePrecision(EvalMetrics.averagePrecision(rankedDocIds, query, 10))
        .build();
}

private EvalReport aggregate(String name, List<EvalResult> results) {
    // 各指标取算术平均
    return EvalReport.builder()
        .datasetName(name)
        .totalQueries(results.size())
        .ndcgAt5(EvalMetrics.mean(results.stream().map(EvalResult::getNdcgAt5).collect(toList())))
        // ... 其余指标
        .mrr(EvalMetrics.mrr(results.stream().map(EvalResult::getReciprocalRank).collect(toList())))
        .mapAt10(EvalMetrics.map(results.stream().map(EvalResult::getAveragePrecision).collect(toList())))
        .perQueryResults(results)
        .build();
}
```

---

## 6. 报告输出格式

```
────────────────────────────────────────────────────
  DocRank Evaluation Report — docrank-bench-v1
  Queries: 100
────────────────────────────────────────────────────
  Metric                   @5       @10      @20
  ─────────────────────────────────────────────────
  NDCG                0.6234  0.5891  0.5234
  Recall              0.7890  0.8456  0.8923
  Precision           0.5432  0.4123  —
  ─────────────────────────────────────────────────
  MRR                                     0.7654
  MAP@10                                  0.6789
────────────────────────────────────────────────────
```

---

## 7. 技术决策

### 7.1 Graded Relevance vs Binary Relevance

选择 graded（0-3 级），而非 binary（0/1）。NDCG 在 graded 标注下更能区分检索质量差异，与 BEIR/MIRACL 等标准评测集格式兼容。

### 7.2 NDCG 分母使用 log₂(i+2) 而非 log₂(i+1)

`i` 从 0 计数，避免第 1 名 log₂(1)=0 导致除以零。`log₂(i+2)` 是 IR 领域 NDCG 的标准计算方式。

### 7.3 relevantCount=0 时 Recall=1.0

无相关文档的查询视为不需要召回，返回 1.0 不污染平均值。这与 TREC 评测惯例一致。
