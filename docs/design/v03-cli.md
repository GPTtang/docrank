# 系统设计: CLI 命令行工具

> **对应 PRD**：`docs/prd/v03-cli.md`
> **状态**：待实现（Phase 1.5）

---

## 1. 概述

新建 `docrank-cli` Maven 模块，基于 picocli 框架实现子命令结构。CLI 是纯 HTTP 客户端，通过调用 MCP Server 的 `/mcp/*` 端点操作知识库，不依赖 docrank-core。

---

## 2. 模块结构

```
docrank-cli/
  pom.xml
  src/main/java/com/memo/docrank/cli/
    DocRankCli.java          ← 主入口（picocli @Command）
    McpClient.java           ← HTTP 客户端封装
    command/
      SearchCommand.java
      StatsCommand.java
      IngestCommand.java
      DeleteCommand.java
      ExportCommand.java
      ImportCommand.java
      ReembedCommand.java
```

---

## 3. 命令实现

### 3.1 主入口

```java
@Command(
    name = "docrank",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "DocRank 知识库命令行工具",
    subcommands = {
        SearchCommand.class,
        StatsCommand.class,
        IngestCommand.class,
        DeleteCommand.class,
        ExportCommand.class,
        ImportCommand.class,
        ReembedCommand.class
    }
)
public class DocRankCli implements Runnable {
    @Option(names = "--server", defaultValue = "http://localhost:8080",
            description = "DocRank MCP Server 地址")
    String serverUrl;

    public static void main(String[] args) {
        System.exit(new CommandLine(new DocRankCli()).execute(args));
    }
}
```

### 3.2 各子命令接口设计

```java
// docrank search "spring boot 配置" --top-k 5
@Command(name = "search")
class SearchCommand implements Runnable {
    @Parameters(index = "0") String query;
    @Option(names = "--top-k", defaultValue = "5") int topK;

    // 调用 POST /mcp/kb_search，打印表格结果
}

// docrank stats
@Command(name = "stats")
class StatsCommand implements Runnable {
    // 调用 GET /mcp/kb_stats，打印统计
}

// docrank ingest <file> [--tags tag1,tag2]
@Command(name = "ingest")
class IngestCommand implements Runnable {
    @Parameters(index = "0") File file;
    @Option(names = "--tags") String tags;

    // 调用 POST /mcp/kb_ingest_file（multipart）
}

// docrank delete <doc_id>
@Command(name = "delete")
class DeleteCommand implements Runnable {
    @Parameters(index = "0") String docId;
    // 调用 POST /mcp/kb_delete
}

// docrank export [--output out.jsonl]
@Command(name = "export")
class ExportCommand implements Runnable {
    @Option(names = "--output", defaultValue = "docrank_export.jsonl") String output;
    // 调用 GET /mcp/kb_export（需新增端点，见下）
}

// docrank import <file.jsonl>
@Command(name = "import")
class ImportCommand implements Runnable {
    @Parameters(index = "0") File file;
    // 逐行读取 JSONL，调用 POST /mcp/kb_ingest
}

// docrank reembed [--batch-size 100]
@Command(name = "reembed")
class ReembedCommand implements Runnable {
    @Option(names = "--batch-size", defaultValue = "100") int batchSize;
    // 调用 POST /mcp/kb_reembed
}
```

### 3.3 McpClient

```java
public class McpClient {
    private final String serverUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // POST JSON
    public Map<String, Object> post(String path, Object body) { ... }
    // GET
    public Map<String, Object> get(String path) { ... }
    // POST multipart（文件上传）
    public Map<String, Object> postFile(String path, File file, List<String> tags) { ... }
}
```

---

## 4. export/import 格式（JSONL）

每行一个 chunk：
```json
{"doc_id":"uuid","title":"Spring Boot 指南","chunk_text":"Spring Boot 是...","tags":["java"],"chunk_index":0,"language":"CHINESE"}
```

**export**：需要 MCP Server 新增 `GET /mcp/kb_export` 端点（分页拉取所有 chunk）。

**import**：逐行读取，将 `chunk_text` 作为 content，`title`/`tags` 作为参数，调用 `POST /mcp/kb_ingest`。

---

## 5. MCP Server 新增端点（export 支持）

```java
// GET /mcp/kb_export?offset=0&limit=1000
@GetMapping("/kb_export")
public ResponseEntity<McpToolResult> export(
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "1000") int limit) {
    List<Chunk> chunks = kb.listAllChunks(offset, limit);
    return ResponseEntity.ok(McpToolResult.ok(Map.of(
        "chunks", chunks,
        "offset", offset,
        "count",  chunks.size()
    )));
}
```

---

## 6. 输出格式

### search 输出示例
```
┌─────┬──────────────────────┬────────┬────────────────────────────────┐
│  #  │ Title                │ Score  │ Content                        │
├─────┼──────────────────────┼────────┼────────────────────────────────┤
│  1  │ Spring Boot 指南      │ 0.9234 │ Spring Boot 支持 YAML 配置...  │
│  2  │ 配置文件详解          │ 0.8912 │ application.yml 是主要配置...  │
└─────┴──────────────────────┴────────┴────────────────────────────────┘
```

### stats 输出示例
```
DocRank Stats
  向量索引 (LanceDB):  1,024 chunks
  BM25 索引 (Lucene):  1,024 chunks
  服务状态:            ✓ 正常
```

---

## 7. Maven 依赖

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.5</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

打包使用 `maven-shade-plugin` 生成可执行 fat jar。

---

## 8. 技术决策

### 8.1 薄客户端 vs 嵌入引擎

CLI 选择薄客户端（HTTP 调用），而非直接引入 docrank-core。原因：CLI 通常在不同机器上运行，嵌入引擎意味着要配置本地模型路径，违背 CLI 轻量的初衷。

### 8.2 Java HttpClient 而非 OkHttp

Java 17 内置 HttpClient 足够满足需求，减少依赖数量，CLI 模块保持轻量。
