# PRD: pgvector 向量后端

> **状态**：待实现（Phase 1.1）
> **模块**：docrank-core（store 包）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

许多团队已经在生产中使用 PostgreSQL，不希望为向量存储单独维护一套基础设施（LanceDB 或 Qdrant）。pgvector 是 PostgreSQL 的向量扩展，能让现有 PG 实例直接支持向量检索。

### 1.2 目标

实现 `pgvectorBackend`，让已有 PostgreSQL 的用户只需启用 pgvector 扩展，配置一行 YAML，即可将 DocRank 向量存储切换到 PostgreSQL。

### 1.3 非目标

- 不管理 PostgreSQL 安装（用户自行准备）
- 不支持 PostgreSQL < 15
- 不引入 JPA/Hibernate，使用原生 JDBC

---

## 2. 用户故事

```
作为已有 PostgreSQL 的开发者，
我希望切换到 pgvector 后端只需改一行 YAML，
以便复用现有 PG 实例，不新增基础设施。
```

---

## 3. 功能需求

- **P0** 实现 `IndexBackend` 接口，支持 `upsertChunks` / `vectorSearch` / `deleteByDocId` / `isHealthy` / `countChunks`
- **P0** 使用 pgvector 的 `<=>` 余弦距离算子做向量检索
- **P0** `keywordSearch` 返回空列表（全文检索由 Lucene 负责）
- **P0** 通过 `DocRankProperties` 配置 JDBC URL / 用户名 / 密码 / 表名
- **P0** 启动时自动建表（若不存在）并创建向量索引（IVFFlat）
- **P1** 支持 `docrank.backend.type=pgvector` 切换

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 依赖 | `org.postgresql:postgresql` JDBC 驱动 + `pgvector-java` 客户端库 |
| 兼容 | PostgreSQL 15+，pgvector 0.5+ |
| 性能 | IVFFlat 索引，lists=100（可配置） |

---

## 5. 验收标准

- [ ] AC1：配置 pgvector 后端后，`upsertChunks` 写入 PG 表，`vectorSearch` 可召回
- [ ] AC2：`deleteByDocId` 后该文档的 chunk 不再被召回
- [ ] AC3：`isHealthy()` 检测 PG 连接是否正常
- [ ] AC4：`docrank.backend.type=pgvector` 配置后自动装配 pgvectorBackend Bean

---

## 6. 依赖

- PostgreSQL 15+（需用户自行安装）
- `CREATE EXTENSION IF NOT EXISTS vector;`（需用户执行一次）
- Maven：`org.postgresql:postgresql`、`com.pgvector:pgvector`

---

## 7. 参考资料

- ROADMAP.md Phase 1.1 pgvector
- pgvector GitHub: https://github.com/pgvector/pgvector
