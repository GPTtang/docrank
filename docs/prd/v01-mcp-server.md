# PRD: MCP HTTP Server

> **状态**：已实现（v0.1）
> **模块**：docrank-mcp
> **日期**：2025-03-08

---

## 1. 背景与目标

### 1.1 问题陈述

AI Agent（如 Claude、GPT）需要通过标准化协议发现并调用外部工具。DocRank 作为知识库需要暴露符合 MCP（Model Context Protocol）规范的 HTTP 接口，使 Agent 能自动发现工具列表并调用检索/写入功能。

### 1.2 目标

提供一套遵循 MCP 协议的 REST HTTP Server，将 DocRank 核心能力（搜索、写入、删除、统计）暴露为 Agent 可自动发现的工具。

### 1.3 非目标

- 不实现 WebSocket 或 gRPC 协议
- 不实现认证鉴权（由上层基础设施负责）
- 不实现请求限流

---

## 2. 用户故事

```
作为 AI Agent，
我希望通过 GET /mcp/tools 自动发现可用工具列表，
以便动态了解 DocRank 支持哪些操作。
```

```
作为 AI Agent，
我希望通过 POST /mcp/kb_search 以自然语言查询知识库，
以便获取与当前任务相关的上下文信息。
```

```
作为开发者，
我希望通过 curl 快速测试写入和检索接口，
以便验证知识库内容的正确性。
```

---

## 3. 功能需求

### 3.1 工具发现

- **P0** `GET /mcp/tools`：返回所有可用工具的名称、描述、参数结构（MCP 标准格式）

### 3.2 检索

- **P0** `POST /mcp/kb_search`：混合语义搜索，支持 query / top_k / scope / tags 参数

### 3.3 写入

- **P0** `POST /mcp/kb_ingest`：写入纯文本，支持 content / title / tags / scope / importance
- **P0** `POST /mcp/kb_ingest_file`：上传文件（multipart/form-data），支持 PDF/MD/HTML/DOCX/TXT/JSON

### 3.4 删除

- **P0** `POST /mcp/kb_delete`：按 doc_id 删除单个文档及其所有 chunk
- **P0** `POST /mcp/kb_delete_scope`：按 scope 批量删除（GDPR 清除）

### 3.5 统计

- **P0** `GET /mcp/kb_stats`：返回向量 chunk 数、BM25 chunk 数、服务健康状态

### 3.6 响应格式

- **P0** 统一响应格式：`{ "success": bool, "data": {...}, "error": "..." }`

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 协议 | HTTP REST，JSON 请求/响应 |
| 兼容性 | 遵循 MCP 规范，Agent 可自动发现工具 |
| 错误处理 | 所有异常返回 `success: false + error` 消息，不抛 500 |

---

## 5. 验收标准

- [ ] AC1：`GET /mcp/tools` 返回包含 5 个工具（kb_search/kb_ingest/kb_ingest_file/kb_delete/kb_stats）的 JSON
- [ ] AC2：`POST /mcp/kb_ingest` 写入文本后返回 `{ "doc_id": "...", "chunk_count": N }`
- [ ] AC3：`POST /mcp/kb_search` 返回按分数排序的结果列表
- [ ] AC4：不支持的文件格式返回 `success: false` 而非 500
- [ ] AC5：`GET /mcp/kb_stats` 返回 `{ "vector_chunks": N, "bm25_chunks": N, "healthy": true }`

---

## 6. 参考资料

- ROADMAP.md v0.1 MCP HTTP Server
- Model Context Protocol 规范
