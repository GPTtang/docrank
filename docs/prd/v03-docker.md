# PRD: Docker 部署支持

> **状态**：待实现（Phase 1.6）
> **模块**：项目根目录（Dockerfile + docker-compose.yml）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

目前部署 DocRank 需要手动安装 Java、下载 ONNX 模型、启动 LanceDB，步骤繁琐，无法快速体验。缺乏容器化支持使得生产部署和 CI/CD 困难。

### 1.2 目标

提供官方 `Dockerfile` 和 `docker-compose.yml`，让用户一条命令启动完整的 DocRank 服务（含 LanceDB）。

### 1.3 非目标

- 不提供 Kubernetes Helm Chart（Phase 3 考虑）
- 不将 ONNX 模型打包进镜像（模型文件太大，通过 volume 挂载）

---

## 2. 用户故事

```
作为开发者，
我希望执行 docker compose up -d 就能启动完整的 DocRank 服务，
以便快速体验而无需手动配置环境。
```

```
作为运维，
我希望有官方 Docker 镜像，
以便在生产环境中标准化部署并做健康检查。
```

---

## 3. 功能需求

### 3.1 Dockerfile

- **P0** 基础镜像：`eclipse-temurin:17-jre-alpine`（轻量）
- **P0** 复制 Spring Boot fat jar
- **P0** 暴露端口 8080
- **P0** 支持通过环境变量覆盖配置（`DOCRANK_EMBEDDING_ONNX_MODEL_PATH` 等）
- **P0** ONNX 模型通过 `-v /host/models:/opt/docrank/models` 挂载
- **P0** Lucene 索引通过 `-v /host/data:/opt/docrank/data` 挂载
- **P1** 健康检查：`HEALTHCHECK CMD curl -f http://localhost:8080/mcp/kb_stats`

### 3.2 docker-compose.yml

- **P0** 包含两个服务：`docrank`（Spring Boot）和 `lancedb`（向量数据库）
- **P0** `lancedb` 使用官方镜像，数据目录持久化
- **P0** `docrank` 依赖 `lancedb`（`depends_on`）
- **P0** 预配置环境变量（模型路径、LanceDB 地址等）
- **P0** Volume 声明：models、data、lancedb-data

### 3.3 环境变量映射

| 环境变量 | 对应配置项 |
|---------|----------|
| `DOCRANK_EMBEDDING_ONNX_MODEL_PATH` | `docrank.embedding.onnx.model-path` |
| `DOCRANK_RERANKER_ONNX_MODEL_PATH` | `docrank.reranker.onnx.model-path` |
| `DOCRANK_LUCENE_INDEX_PATH` | `docrank.lucene.index-path` |
| `DOCRANK_BACKEND_LANCEDB_HOST` | `docrank.backend.lancedb.host` |

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 镜像大小 | 使用 JRE alpine 基础镜像，目标 < 200MB（不含模型）|
| 启动时间 | 容器启动后服务就绪时间 < 30s（模型加载时间） |
| 数据持久化 | Lucene 索引和 LanceDB 数据均通过 volume 持久化 |

---

## 5. 验收标准

- [ ] AC1：`docker compose up -d` 后访问 `http://localhost:8080/mcp/kb_stats` 返回正常
- [ ] AC2：重启容器后 Lucene 和 LanceDB 数据不丢失
- [ ] AC3：通过环境变量可覆盖模型路径配置
- [ ] AC4：`docker ps` 显示 health status 为 healthy

---

## 6. 参考资料

- ROADMAP.md Phase 1.6 Docker & Deployment
