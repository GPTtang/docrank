# 系统设计: pgvector 向量后端

> **对应 PRD**：`docs/prd/v03-pgvector-backend.md`
> **状态**：待实现（Phase 1.1）

---

## 1. 概述

新增 `PgvectorBackend` 实现 `IndexBackend` 接口，通过 JDBC + pgvector 扩展将向量数据存储在 PostgreSQL 中。`keywordSearch` 返回空列表，全文检索仍由 Lucene 负责。

---

## 2. 表结构

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS docrank_chunks (
    chunk_id    TEXT PRIMARY KEY,
    doc_id      TEXT NOT NULL,
    title       TEXT,
    section_path TEXT,
    chunk_text  TEXT NOT NULL,
    vec_chunk   vector(1024),           -- pgvector 类型
    language    TEXT,
    updated_at  TIMESTAMPTZ,
    importance  DOUBLE PRECISION DEFAULT 1.0,
    expires_at  TIMESTAMPTZ
);

-- IVFFlat 向量索引（余弦距离）
CREATE INDEX IF NOT EXISTS docrank_chunks_vec_idx
    ON docrank_chunks USING ivfflat (vec_chunk vector_cosine_ops)
    WITH (lists = 100);

-- 普通索引加速按 doc_id 删除
CREATE INDEX IF NOT EXISTS docrank_chunks_doc_id_idx
    ON docrank_chunks (doc_id);
```

---

## 3. 核心实现

### 3.1 类结构

```java
public class PgvectorBackend implements IndexBackend {
    private final DataSource dataSource;   // JDBC 连接池（HikariCP）
    private final String tableName;        // 默认 "docrank_chunks"
    private final int dimension;           // 1024

    // 构造时执行 createIndex()
    public PgvectorBackend(String jdbcUrl, String username,
                           String password, String tableName, int dimension)
}
```

### 3.2 upsertChunks

```sql
INSERT INTO docrank_chunks
    (chunk_id, doc_id, title, section_path, chunk_text,
     vec_chunk, language, updated_at, importance, expires_at)
VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?)
ON CONFLICT (chunk_id) DO UPDATE SET
    doc_id      = EXCLUDED.doc_id,
    title       = EXCLUDED.title,
    chunk_text  = EXCLUDED.chunk_text,
    vec_chunk   = EXCLUDED.vec_chunk,
    updated_at  = EXCLUDED.updated_at;
```

向量传参：`pgvector-java` 的 `PGvector` 类型，或直接传字符串 `[0.1,0.2,...]`。

### 3.3 vectorSearch

```sql
SELECT chunk_id, doc_id, title, section_path, chunk_text,
       language, updated_at, importance, expires_at,
       1 - (vec_chunk <=> ?::vector) AS score
FROM docrank_chunks
WHERE expires_at IS NULL OR expires_at > NOW()
ORDER BY vec_chunk <=> ?::vector
LIMIT ?;
```

`<=>` 为余弦距离，`1 - distance` 转为相似度分数。

### 3.4 deleteByDocId

```sql
DELETE FROM docrank_chunks WHERE doc_id = ?;
```

### 3.5 isHealthy

```sql
SELECT 1;
```

连接成功返回 true，SQLException 返回 false。

### 3.6 countChunks

```sql
SELECT COUNT(*) FROM docrank_chunks;
```

---

## 4. 配置项

```yaml
docrank:
  backend:
    type: pgvector
    pgvector:
      jdbc-url: jdbc:postgresql://localhost:5432/mydb
      username: postgres
      password: secret
      table-name: docrank_chunks
      ivfflat-lists: 100          # IVFFlat 索引分区数
```

---

## 5. AutoConfiguration 扩展

```java
case "pgvector" -> new PgvectorBackend(
    props.getBackend().getPgvector().getJdbcUrl(),
    props.getBackend().getPgvector().getUsername(),
    props.getBackend().getPgvector().getPassword(),
    props.getBackend().getPgvector().getTableName(),
    props.getEmbedding().getDimension());
```

---

## 6. Maven 依赖

```xml
<!-- JDBC 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>
<!-- pgvector Java 客户端 -->
<dependency>
    <groupId>com.pgvector</groupId>
    <artifactId>pgvector</artifactId>
    <version>0.1.6</version>
</dependency>
<!-- HikariCP 连接池 -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

---

## 7. 技术决策

### 7.1 IVFFlat vs HNSW

| 索引 | 查询速度 | 构建速度 | 内存 | 推荐场景 |
|------|---------|---------|------|---------|
| IVFFlat | 快（近似） | 慢（需先有数据） | 低 | 数据量大、查询频繁 |
| HNSW | 最快 | 最慢 | 高 | 高精度要求 |

默认用 IVFFlat（lists=100），可通过配置切换。注意：IVFFlat 索引需要表中有足够数据才能创建，首次建表时建议延迟建索引。

### 7.2 向量传参方式

使用 `pgvector-java` 的 `PGvector` 对象，注册为 PG 自定义类型，避免手动拼接字符串。

### 7.3 keywordSearch 返回空列表

pgvector 无全文检索能力，`keywordSearch` 返回空列表。Lucene 独立负责 BM25，行为与 QdrantBackend 一致。
