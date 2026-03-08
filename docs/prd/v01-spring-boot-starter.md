# PRD: Spring Boot Starter & 自动配置

> **状态**：已实现（v0.1）
> **模块**：docrank-spring-boot-starter
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

DocRank 涉及 10+ 个组件（向量后端、BM25 索引、Embedding 模型、Reranker、分块服务等），手动装配复杂、容易出错，不适合快速集成到 Spring Boot 项目。

### 1.2 目标

提供一个 Spring Boot Starter，让用户只需添加一个依赖 + 几行 YAML，即可完整启用 DocRank 全部功能。

### 1.3 非目标

- 不支持非 Spring 框架（Quarkus、Micronaut 等）
- 不提供运行时动态切换后端

---

## 2. 用户故事

```
作为 Spring Boot 开发者，
我希望添加一个 Maven 依赖并配置 application.yml，
以便不写任何 Bean 定义代码就能使用 KnowledgeBaseService。
```

```
作为运维，
我希望通过 YAML 控制所有核心参数（模型路径、分块大小、评分系数等），
以便在不同环境中灵活部署而无需修改代码。
```

---

## 3. 功能需求

### 3.1 自动装配

- **P0** 根据 `docrank.backend.type`（lancedb/qdrant/memory）自动选择并创建向量后端 Bean
- **P0** 自动创建 Lucene BM25 索引 Bean
- **P0** 自动创建 BGE-M3 ONNX Embedding Bean
- **P0** 自动创建 bge-reranker-v2-m3 ONNX Reranker Bean
- **P0** 自动创建 LanguageDetector、ChunkingService、ParserRegistry Bean
- **P0** 自动创建 AdvancedScorer、HybridSearcher Bean
- **P0** 自动创建 KnowledgeBaseService Bean
- **P0** 自动创建 DocRankMcpServer Bean（REST Controller）

### 3.2 配置项

- **P0** `docrank.backend.type`：后端类型（默认 lancedb）
- **P0** `docrank.backend.lancedb.*`：host/port/tableName
- **P0** `docrank.backend.qdrant.*`：host/port/collectionName
- **P0** `docrank.embedding.onnx.modelPath`：BGE-M3 模型目录
- **P0** `docrank.embedding.dimension`：向量维度（默认 1024）
- **P0** `docrank.embedding.batchSize`：批量向量化大小（默认 32）
- **P0** `docrank.reranker.enabled`：是否启用重排（默认 true）
- **P0** `docrank.reranker.topN`：重排候选数（默认 20）
- **P0** `docrank.reranker.onnx.modelPath`：Reranker 模型目录
- **P0** `docrank.lucene.indexPath`：Lucene 索引目录
- **P0** `docrank.lucene.ramBufferMb`：内存缓冲（默认 64）
- **P0** `docrank.chunk.size`：分块大小（默认 512）
- **P0** `docrank.chunk.overlap`：重叠长度（默认 64）
- **P0** `docrank.scoring.*`：recencyLambda/minScore/mmrEnabled/mmrPenalty

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 零代码集成 | 不需要任何 `@Bean` 定义，纯 YAML 驱动 |
| Spring Boot 兼容 | Spring Boot 3.3.0+，Java 17+ |
| 条件装配 | 使用 `@ConditionalOnMissingBean` 允许用户覆盖任意 Bean |

---

## 5. 验收标准

- [ ] AC1：空 Spring Boot 项目添加 starter 依赖后，配置 YAML 启动无报错
- [ ] AC2：`@Autowired KnowledgeBaseService` 可正常注入
- [ ] AC3：`docrank.backend.type=memory` 时使用 InMemoryBackend
- [ ] AC4：用户自定义 `@Bean IndexBackend` 时，自动配置的 LanceDB Bean 不创建

---

## 6. 参考资料

- ROADMAP.md v0.1 Spring Boot Starter
- Spring Boot Auto-configuration 文档
