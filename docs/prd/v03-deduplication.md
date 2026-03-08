# PRD: 写入去重

> **状态**：待实现（Phase 1.4）
> **模块**：docrank-memory（KnowledgeBaseService）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

同一份文档可能被重复写入（如定时同步脚本、用户误操作），导致知识库中存在大量近似重复的 chunk，污染检索结果、浪费存储。

### 1.2 目标

在写入前进行相似度检测，跳过与已有内容高度相似的 chunk，防止重复数据污染知识库。

### 1.3 非目标

- 不做精确文本去重（不用 MD5/SHA，用向量相似度）
- 不合并相似 chunk（只跳过，不修改已有内容）
- 默认关闭，需显式配置开启

---

## 2. 用户故事

```
作为开发者，
我希望重复写入同一篇文档时，知识库能自动识别并跳过重复内容，
以便保持知识库干净，避免检索结果出现重复段落。
```

---

## 3. 功能需求

- **P0** 写入每个 chunk 前，先用其向量在向量后端做 top-1 检索
- **P0** 若最近邻的余弦相似度 ≥ `dedupThreshold`（默认 0.95），跳过该 chunk
- **P0** 通过 `docrank.ingest.dedup-enabled=true` 开启，默认关闭
- **P0** 通过 `docrank.ingest.dedup-threshold=0.95` 配置阈值
- **P0** `IngestResult` 中新增 `skippedChunks` 字段，记录跳过数量
- **P1** 日志记录每个被跳过的 chunk（title + 相似度）

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 性能 | 每个 chunk 多一次 top-1 向量检索，batch 写入时可并行 |
| 默认行为 | `dedup-enabled=false` 时与当前完全一致，零性能影响 |

---

## 5. 验收标准

- [ ] AC1：开启去重后，重复写入同一文档，第二次 `IngestResult.skippedChunks > 0`
- [ ] AC2：相似度 < threshold 的不同内容正常写入
- [ ] AC3：`dedup-enabled=false` 时重复写入不检测，行为与当前一致
- [ ] AC4：阈值可通过 YAML 配置

---

## 6. 参考资料

- ROADMAP.md Phase 1.4 Deduplication
