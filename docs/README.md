# DocRank 文档中心

## 工作流规范

**文档优先原则**：所有功能开发必须先完成文档，再写代码。

```
PRD（产品需求） → 系统设计 → 代码实现
```

## 目录结构

```
docs/
  prd/           # 产品需求文档（PRD）
    TEMPLATE.md  # PRD 模板
  design/        # 系统设计文档
    TEMPLATE.md  # 系统设计模板
```

## 已有文档索引

### PRD（产品需求）

| 文件 | 功能 | 状态 |
|------|------|------|
| `prd/v01-core-engine.md` | 核心检索引擎（写入流水线 + 混合检索） | 已实现 v0.1 |
| `prd/v01-mcp-server.md` | MCP HTTP Server | 已实现 v0.1 |
| `prd/v01-spring-boot-starter.md` | Spring Boot Starter 自动配置 | 已实现 v0.1 |
| `prd/v01-multi-vector-backend.md` | 多向量后端（LanceDB/Qdrant/InMemory） | 已实现 Phase 1.1 |
| `prd/v01-advanced-scoring.md` | 高级评分（时效性/重要度/MMR） | 已实现 Phase 1.2 |
| `prd/v02-langchain4j-adapter.md` | LangChain4j 适配器 | 已实现 Phase 2.2 |
| `prd/v02-spring-ai-adapter.md` | Spring AI 适配器 | 已实现 Phase 2.3 |
| `prd/v02-eval-module.md` | 检索质量评估模块 | 已实现 Phase 2.4 |
| `prd/v02-webui.md` | Web 管理界面 | 已实现 Phase 2.1 MVP |

### 系统设计

| 文件 | 功能 |
|------|------|
| `design/v01-core-engine.md` | 核心检索引擎架构、数据模型、算法 |
| `design/v01-mcp-server.md` | MCP 接口定义、请求/响应格式 |
| `design/v01-spring-boot-starter.md` | Bean 装配顺序、配置项清单 |
| `design/v01-multi-vector-backend.md` | IndexBackend 接口、各后端 HTTP 调用 |
| `design/v01-advanced-scoring.md` | 评分公式、实现代码 |
| `design/v02-langchain4j-adapter.md` | 三个适配类的接口实现 |
| `design/v02-spring-ai-adapter.md` | VectorStore/EmbeddingModel 适配 |
| `design/v02-eval-module.md` | 指标计算公式、评估服务流程 |
| `design/v02-webui.md` | 前端架构、API 调用映射、状态管理 |

### Phase 1 剩余功能（待实现）

| PRD | 设计 | 功能 |
|-----|------|------|
| `prd/v03-pgvector-backend.md` | `design/v03-pgvector-backend.md` | pgvector 向量后端 |
| `prd/v03-deduplication.md` | `design/v03-deduplication.md` | 写入去重 |
| `prd/v03-reembed.md` | `design/v03-reembed.md` | 重新向量化端点 |
| `prd/v03-cli.md` | `design/v03-cli.md` | CLI 命令行工具 |
| `prd/v03-docker.md` | `design/v03-docker.md` | Docker 部署支持 |
| `prd/v03-more-parsers.md` | `design/v03-more-parsers.md` | Excel/PPT/EPUB/CSV 解析器 |

## 新功能开发流程

1. **写 PRD** — 复制 `docs/prd/TEMPLATE.md`，命名为 `docs/prd/<feature-name>.md`
   - 明确问题、目标、用户故事、功能需求、验收标准

2. **写系统设计** — 复制 `docs/design/TEMPLATE.md`，命名为 `docs/design/<feature-name>.md`
   - 设计架构、接口、数据模型、技术决策
   - 评审并确认设计

3. **写代码** — 按设计文档实现
   - 单元测试覆盖核心逻辑
   - 代码完成后更新文档中的状态

## 命名规范

- PRD：`docs/prd/v0x-<feature>.md`（如 `v02-qdrant-backend.md`）
- 设计：`docs/design/v0x-<feature>.md`（如 `v02-qdrant-backend.md`）
