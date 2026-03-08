# PRD: 多向量存储后端

> **状态**：已实现（v0.1/Phase 1.1）
> **模块**：docrank-core（store 包）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

不同用户有不同的基础设施偏好和规模需求：小项目希望零依赖快速启动，生产环境需要专业向量数据库，部分用户已有 Qdrant 集群。若向量后端硬编码，切换成本极高。

### 1.2 目标

通过统一 `IndexBackend` 接口，支持三种向量后端（LanceDB/Qdrant/InMemory），配置文件一行切换，上层代码零修改。

### 1.3 非目标

- Phase 1 不实现 pgvector 后端（计划 v0.3）
- 不实现跨后端数据迁移

---

## 2. 用户故事

```
作为开发者（本地测试），
我希望使用 InMemory 后端，不安装任何外部服务，
以便在开发/测试阶段快速验证功能。
```

```
作为生产用户（已有 Qdrant），
我希望切换到 Qdrant 后端只需改一行 YAML，
以便复用现有基础设施。
```

---

## 3. 功能需求

### 3.1 统一接口（IndexBackend）

- **P0** `upsertChunks(List<ChunkWithVectors>)`：批量写入（存在则覆盖）
- **P0** `vectorSearch(float[], int, Map)`：余弦相似度向量检索
- **P0** `deleteByDocId(String)`：按文档 ID 删除
- **P0** `deleteByScope(String)`：按 scope 批量删除
- **P0** `isHealthy()`：健康检查
- **P0** `countChunks()`：统计 chunk 数量

### 3.2 InMemory 后端

- **P0** 数据存储在 `ConcurrentHashMap<String, ChunkWithVectors>`
- **P0** 向量检索：暴力余弦相似度 O(n)
- **P0** 关键词检索：简单 contains 匹配
- **P0** 进程退出后数据丢失（仅用于测试）

### 3.3 LanceDB 后端（默认）

- **P0** 连接 LanceDB HTTP API（localhost:8181）
- **P0** 表结构：chunk_id/doc_id/title/section_path/chunk_text/vec_chunk(1024)/language/updated_at/scope/importance/expires_at
- **P0** upsert：POST `/v1/table/{name}/insert/`，mode=overwrite
- **P0** 向量检索：POST `/v1/table/{name}/query/`，query_type=vector，metric=cosine
- **P0** 关键词检索：query_type=fts
- **P0** 健康检查：GET `/v1/table/`

### 3.4 Qdrant 后端

- **P0** 连接 Qdrant REST API（localhost:6333）
- **P0** Point ID：UUID.nameUUIDFromBytes(chunkId.getBytes)
- **P0** upsert：PUT `/collections/{name}/points`
- **P0** 向量检索：POST `/collections/{name}/points/search`
- **P0** 关键词检索：返回空列表（由 Lucene 负责全文检索）
- **P0** 删除：POST `/collections/{name}/points/delete`，按 doc_id/scope filter

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 可替换 | 一行 YAML（`docrank.backend.type`）切换，上层零修改 |
| 接口一致 | 三种后端实现完全相同的 IndexBackend 接口 |
| 容错 | HTTP 调用失败时抛出运行时异常，由调用方处理 |

---

## 5. 验收标准

- [ ] AC1：`backend.type=memory` 时无需启动外部服务，upsert+search 正常工作
- [ ] AC2：`backend.type=lancedb` 时写入 chunk 后可向量检索召回
- [ ] AC3：`backend.type=qdrant` 时写入 chunk 后可向量检索召回
- [ ] AC4：三种后端的 `deleteByDocId` 后，该 doc 的 chunk 不再被召回
- [ ] AC5：`deleteByScope("agent:001")` 只删除该 scope 的数据

---

## 6. 参考资料

- ROADMAP.md Phase 1.1 Multiple Vector Backends
- LanceDB HTTP API 文档
- Qdrant REST API 文档
