# 系统设计: Docker 部署支持

> **对应 PRD**：`docs/prd/v03-docker.md`
> **状态**：待实现（Phase 1.6）

---

## 1. 概述

在项目根目录提供 `Dockerfile` 和 `docker-compose.yml`，基于 Spring Boot fat jar 构建镜像，通过 volume 挂载 ONNX 模型和数据目录，docker-compose 编排 DocRank + LanceDB 两个服务。

---

## 2. Dockerfile

```dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY docrank-core/pom.xml docrank-core/
COPY docrank-memory/pom.xml docrank-memory/
COPY docrank-mcp/pom.xml docrank-mcp/
COPY docrank-spring-boot-starter/pom.xml docrank-spring-boot-starter/
# ... 其余模块
RUN mvn dependency:go-offline -q

COPY . .
RUN mvn clean package -DskipTests -pl docrank-mcp -am -q

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
LABEL maintainer="DocRank"

RUN addgroup -S docrank && adduser -S docrank -G docrank

WORKDIR /app
COPY --from=builder /build/docrank-mcp/target/docrank-mcp-*.jar app.jar

# 默认挂载点
VOLUME ["/opt/docrank/models", "/opt/docrank/data"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/mcp/kb_stats || exit 1

USER docrank

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
```

---

## 3. docker-compose.yml

```yaml
version: '3.8'

services:
  lancedb:
    image: lancedb/lancedb:latest
    ports:
      - "8181:8181"
    volumes:
      - lancedb-data:/data
    command: ["--host", "0.0.0.0", "--port", "8181", "--storage-path", "/data"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8181/v1/table/"]
      interval: 10s
      timeout: 5s
      retries: 5

  docrank:
    image: memo/docrank:latest
    build: .
    ports:
      - "8080:8080"
    depends_on:
      lancedb:
        condition: service_healthy
    volumes:
      - ./models:/opt/docrank/models:ro    # ONNX 模型（只读）
      - docrank-data:/opt/docrank/data     # Lucene 索引（持久化）
    environment:
      # 向量后端
      DOCRANK_BACKEND_LANCEDB_HOST: lancedb
      DOCRANK_BACKEND_LANCEDB_PORT: "8181"
      # 模型路径
      DOCRANK_EMBEDDING_ONNX_MODEL_PATH: /opt/docrank/models/bge-m3
      DOCRANK_RERANKER_ONNX_MODEL_PATH: /opt/docrank/models/bge-reranker-v2-m3
      # Lucene 索引
      DOCRANK_LUCENE_INDEX_PATH: /opt/docrank/data/lucene-index
      # JVM
      JAVA_OPTS: "-Xms512m -Xmx2g"

volumes:
  lancedb-data:
  docrank-data:
```

---

## 4. 环境变量映射

Spring Boot 自动将环境变量转换为配置项（`_` → `.`，大写 → 小写）：

| 环境变量 | 对应 YAML 配置 |
|---------|--------------|
| `DOCRANK_BACKEND_TYPE` | `docrank.backend.type` |
| `DOCRANK_BACKEND_LANCEDB_HOST` | `docrank.backend.lancedb.host` |
| `DOCRANK_BACKEND_LANCEDB_PORT` | `docrank.backend.lancedb.port` |
| `DOCRANK_EMBEDDING_ONNX_MODEL_PATH` | `docrank.embedding.onnx.model-path` |
| `DOCRANK_RERANKER_ONNX_MODEL_PATH` | `docrank.reranker.onnx.model-path` |
| `DOCRANK_LUCENE_INDEX_PATH` | `docrank.lucene.index-path` |
| `DOCRANK_CHUNK_SIZE` | `docrank.chunk.size` |
| `DOCRANK_RERANKER_ENABLED` | `docrank.reranker.enabled` |

Spring Boot 原生支持，无需额外代码。

---

## 5. 目录结构（用户视角）

```
my-project/
  docker-compose.yml
  models/                    # 用户需要提前下载
    bge-m3/
      model.onnx
      tokenizer.json
      tokenizer_config.json
    bge-reranker-v2-m3/
      model.onnx
      tokenizer.json
```

---

## 6. 快速启动命令

```bash
# 1. 下载模型（一次性）
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-m3
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-reranker-v2-m3

# 2. 启动服务
docker compose up -d

# 3. 验证
curl http://localhost:8080/mcp/kb_stats
```

---

## 7. 技术决策

### 7.1 多阶段构建

使用 builder + runtime 双阶段，最终镜像只含 JRE 和 jar，不含 Maven、源码、编译产物，镜像更小。

### 7.2 模型不打包进镜像

BGE-M3 ONNX 模型约 2GB+，打包进镜像不现实。通过 volume 挂载，与镜像版本解耦，用户可独立升级模型。

### 7.3 HEALTHCHECK start-period=60s

ONNX 模型加载需要时间（GPU 模式更长），设 60s 启动宽限期，避免健康检查在模型加载完成前失败导致重启。
