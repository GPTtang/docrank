# PRD: CLI 命令行工具

> **状态**：待实现（Phase 1.5）
> **模块**：docrank-cli（新模块）
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

目前操作知识库（查看内容、搜索、导出、导入）只能通过 REST API 或 Java 代码，缺乏终端友好的操作方式，运维和调试不便。

### 1.2 目标

提供 `docrank` CLI 工具，支持常用知识库管理操作，直接通过终端调用本地或远程 DocRank 服务。

### 1.3 非目标

- CLI 不嵌入核心引擎，通过 HTTP 调用 MCP Server（薄客户端）
- 不实现交互式 TUI

---

## 2. 用户故事

```
作为开发者，
我希望在终端执行 `docrank search "spring boot 配置"` 即可搜索知识库，
以便在不打开浏览器或写代码的情况下快速验证内容。
```

```
作为运维，
我希望用 `docrank export --output backup.jsonl` 导出所有数据，
以便做数据备份或迁移。
```

---

## 3. 功能需求

### 3.1 命令列表

| 命令 | 说明 |
|------|------|
| `docrank search <query> [--top-k N]` | 混合语义搜索，打印结果 |
| `docrank stats` | 打印 vector/BM25 chunk 数和健康状态 |
| `docrank ingest <file> [--tags tag1,tag2]` | 写入文件 |
| `docrank delete <doc_id>` | 删除文档 |
| `docrank export [--output file.jsonl]` | 导出所有 chunk 为 JSONL |
| `docrank import <file.jsonl>` | 从 JSONL 导入 chunk |
| `docrank reembed` | 触发全量重新向量化 |

### 3.2 全局选项

- `--server http://localhost:8080`：指定 MCP Server 地址（默认 localhost:8080）
- `--help`：帮助信息

### 3.3 实现方式

- **P0** 基于 picocli 框架实现子命令结构
- **P0** 通过 HTTP 调用 `/mcp/*` 端点（不直接依赖 docrank-core）
- **P0** 打包为可执行 JAR（`java -jar docrank-cli.jar`）
- **P1** 提供 Shell wrapper 脚本（`docrank`）

### 3.4 export/import 格式（JSONL）

每行一个 JSON 对象：
```json
{"doc_id":"uuid","title":"标题","chunk_text":"内容","tags":["java"],"chunk_index":0,"language":"CHINESE"}
```

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 独立性 | 独立 Maven 模块（docrank-cli），不依赖 Spring |
| 框架 | picocli（轻量 CLI 框架） |
| 输出 | 终端友好的表格/JSON 输出 |

---

## 5. 验收标准

- [ ] AC1：`docrank stats` 打印 chunk 数量和健康状态
- [ ] AC2：`docrank search "查询"` 打印排序结果（title + score + 内容摘要）
- [ ] AC3：`docrank export --output out.jsonl` 生成合法 JSONL 文件
- [ ] AC4：`docrank import out.jsonl` 将导出的文件重新写入知识库
- [ ] AC5：`--server` 参数可指定远程服务地址

---

## 6. 依赖

- picocli 4.x
- Jackson（JSON 序列化）
- OkHttp 或 Java HttpClient（HTTP 调用）

---

## 7. 参考资料

- ROADMAP.md Phase 1.5 CLI Tools
