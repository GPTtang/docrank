# PRD: 检索质量评估模块

> **状态**：已实现（Phase 2.4）
> **模块**：docrank-eval
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

检索系统优化（调整分块大小、召回数、评分权重等）的效果无法通过肉眼判断，需要量化指标。缺乏评估模块，参数调优完全依赖主观感受，无法客观比较不同配置的优劣。

### 1.2 目标

提供离线评估框架，基于标注数据集计算标准信息检索指标（NDCG/Recall/Precision/MRR/MAP），输出格式化报告。

### 1.3 非目标

- 不实现在线 A/B 测试
- 不自动收集标注数据（人工标注）
- 不实现参数自动调优

---

## 2. 用户故事

```
作为 DocRank 开发者，
我希望修改分块策略后能用量化指标对比效果，
以便判断新策略是否真正提升了检索质量。
```

```
作为用户，
我希望能为自己的领域数据创建评估集，
以便在调整配置参数时有客观依据。
```

---

## 3. 功能需求

### 3.1 评估数据集格式（EvalDataset）

- **P0** JSON 格式数据集，字段：name / description / queries[]
- **P0** 每条 query：id / query / relevant（Map<doc_id, grade>） / scope（可选）
- **P0** Graded relevance：0（不相关）/ 1（部分相关）/ 2（高度相关）/ 3（完全相关）
- **P0** 工厂方法：fromJsonFile / fromJsonString / fromInputStream / of / single

### 3.2 评估指标（EvalMetrics）

- **P0** NDCG@K（使用 graded relevance，K=5/10/20）
- **P0** Recall@K（K=5/10/20）
- **P0** Precision@K（K=5/10）
- **P0** Reciprocal Rank（RR）
- **P0** Average Precision@K（AP@10）
- **P0** MRR（多查询平均）
- **P0** MAP（多查询平均）
- **P0** 所有方法为纯静态工具方法

### 3.3 评估服务（EvaluationService）

- **P0** `evaluate(EvalDataset)` → EvalReport，默认 retrieveK=20
- **P0** `evaluate(EvalDataset, int)` → 自定义 retrieveK
- **P0** 支持 scope 过滤（每条 query 可指定 scope）
- **P0** 输出每条 query 的详细结果（EvalResult）和聚合报告（EvalReport）

### 3.4 评估报告（EvalReport）

- **P0** 聚合指标：avgNDCG@5/10/20、avgRecall@5/10/20、avgPrecision@5/10、MRR、MAP@10
- **P0** `print()` → 格式化表格打印到 stdout
- **P0** `toText()` → 返回格式化字符串
- **P0** `toCsv()` → 返回 CSV 格式（适合写入文件对比）

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 独立性 | 不依赖 Spring 框架，可单独使用 |
| 正确性 | NDCG/Recall 等指标实现与学术定义一致 |
| 性能 | 评估为离线批处理，不需要实时性 |

---

## 5. 验收标准

- [ ] AC1：完美召回时 NDCG@5=1.0、Recall@5=1.0
- [ ] AC2：全部未召回时 NDCG=0.0、Recall=0.0
- [ ] AC3：`EvalDataset.fromJsonFile()` 可正确解析标准格式 JSON
- [ ] AC4：`EvalReport.print()` 输出可读的表格格式
- [ ] AC5：MRR = 1/rank（第一个相关文档排在第 rank 位）

---

## 6. 参考资料

- ROADMAP.md Phase 2.4 Evaluation Module
- BEIR 评测集标准
- NDCG 学术定义
