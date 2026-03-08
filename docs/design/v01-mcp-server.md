# 系统设计: MCP HTTP Server

> **对应 PRD**：`docs/prd/v01-mcp-server.md`
> **状态**：已实现（v0.1）
> **模块**：docrank-mcp

---

## 1. 概述

`DocRankMcpServer` 是一个 Spring `@RestController`，将 `KnowledgeBaseService` 的功能以 MCP 协议规范的 REST API 形式暴露，路径前缀 `/mcp`，统一响应格式 `McpToolResult`。

---

## 2. 架构设计

```
AI Agent / Claude / curl
    │  HTTP JSON / multipart
    ▼
DocRankMcpServer (@RestController)
    │  /mcp/tools        → 工具发现
    │  /mcp/kb_search    → 混合检索
    │  /mcp/kb_ingest    → 文本写入
    │  /mcp/kb_ingest_file → 文件上传
    │  /mcp/kb_delete    → 单文档删除
    │  /mcp/kb_delete_scope → scope 批删
    │  /mcp/kb_stats     → 统计
    ▼
KnowledgeBaseService
    （核心引擎，详见 v01-core-engine.md）
```

---

## 3. 接口设计

### 3.1 GET /mcp/tools

**功能**：MCP 工具发现端点

**响应**：
```json
{
  "name": "docrank",
  "version": "1.0.0",
  "description": "本地语义搜索知识库...",
  "tools": [
    {
      "name": "kb_search",
      "description": "混合语义搜索（BM25 + 向量 + 重排序）",
      "parameters": {
        "query": "string (required)",
        "top_k": "int (optional, default 5)",
        "scope": "string (optional)",
        "tags": "array<string> (optional)"
      }
    },
    { "name": "kb_ingest", ... },
    { "name": "kb_ingest_file", ... },
    { "name": "kb_delete", ... },
    { "name": "kb_stats", ... }
  ]
}
```

---

### 3.2 POST /mcp/kb_search

**请求**：
```json
{
  "query": "如何配置 Spring Boot",
  "top_k": 5,
  "scope": "project-x",
  "tags": ["java"]
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "query": "如何配置 Spring Boot",
    "total": 3,
    "results": [
      {
        "doc_id": "uuid-1",
        "title": "Spring Boot 指南",
        "section": "第2章 > 配置文件",
        "content": "Spring Boot 支持 YAML 和 Properties...",
        "score": 0.9234,
        "language": "CHINESE",
        "tags": ["java", "spring"]
      }
    ]
  }
}
```

**实现逻辑**：
```java
@PostMapping("/kb_search")
public McpToolResult search(@RequestBody Map<String, Object> body) {
    String query  = (String) body.get("query");
    int topK      = body.getOrDefault("top_k", 5);
    String scope  = (String) body.get("scope");
    List<String> tags = (List<String>) body.get("tags");

    Map<String, Object> filters = new HashMap<>();
    if (scope != null) filters.put("scope", scope);
    if (tags  != null) filters.put("tags", tags);

    List<SearchResult> results = kb.search(query, topK, filters);
    return McpToolResult.ok(buildSearchData(query, results));
}
```

---

### 3.3 POST /mcp/kb_ingest

**请求**：
```json
{
  "content": "Spring Boot 是...",
  "title": "Spring Boot 介绍",
  "tags": ["java", "框架"],
  "scope": "project-x",
  "importance": 0.8
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "doc_id": "uuid-2",
    "title": "Spring Boot 介绍",
    "chunk_count": 5
  }
}
```

---

### 3.4 POST /mcp/kb_ingest_file

**请求**：multipart/form-data
- `file`：文件二进制
- `tags`：多值字符串（可重复）
- `scope`：字符串

**支持格式**：`.pdf / .md / .html / .docx / .txt / .json`

**响应**：
```json
{
  "success": true,
  "data": {
    "doc_id": "uuid-3",
    "title": "API 文档",
    "filename": "api.pdf",
    "chunk_count": 12
  }
}
```

**格式校验**：不支持的扩展名返回 `success: false`，不抛 500。

---

### 3.5 POST /mcp/kb_delete

**请求**：`{ "doc_id": "uuid-1" }`

**响应**：`{ "success": true, "data": { "deleted": "uuid-1" } }`

---

### 3.6 POST /mcp/kb_delete_scope

**请求**：`{ "scope": "agent:001" }`

**响应**：`{ "success": true, "data": { "deleted_scope": "agent:001" } }`

---

### 3.7 GET /mcp/kb_stats

**响应**：
```json
{
  "success": true,
  "data": {
    "vector_chunks": 1024,
    "bm25_chunks": 1024,
    "healthy": true
  }
}
```

---

## 4. 数据模型

```java
// 统一响应包装
McpToolResult {
    boolean success;
    Object data;
    String error;

    static McpToolResult ok(Object data)
    static McpToolResult fail(String error)
}

// 工具描述
McpTool {
    String name;
    String description;
    Map<String, Object> parameters;
}

// 工具清单
McpManifest {
    String name;
    String version;
    String description;
    List<McpTool> tools;
}
```

---

## 5. 错误处理策略

```java
// 所有端点均用 try-catch，不抛 500
try {
    // 业务逻辑
    return McpToolResult.ok(data);
} catch (Exception e) {
    log.error("操作失败", e);
    return McpToolResult.fail(e.getMessage());
}
```

---

## 6. 技术决策

### 6.1 为什么用 REST 而非 WebSocket/gRPC？

MCP 协议当前主流实现为 HTTP REST，Agent 框架（Claude Desktop、LangChain4j）均原生支持 HTTP 工具调用。

### 6.2 为什么统一 `McpToolResult` 包装而非 HTTP 状态码？

Agent 框架解析响应时更依赖 JSON 字段而非 HTTP 状态码，统一包装简化 Agent 侧的错误处理逻辑。

### 6.3 文件上传格式校验

在 Controller 层校验扩展名，避免将不支持的文件传入 `ParserRegistry` 引发解析异常。
