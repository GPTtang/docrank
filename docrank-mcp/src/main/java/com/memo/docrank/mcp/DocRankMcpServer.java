package com.memo.docrank.mcp;

import com.memo.docrank.memory.IngestResult;
import com.memo.docrank.memory.KnowledgeBaseService;
import com.memo.docrank.core.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * DocRank MCP Server
 *
 * 为 AI Agent 提供本地知识库能力，遵循 MCP (Model Context Protocol) 规范。
 *
 * 工具列表：
 *   GET  /mcp/tools          — 工具清单（Agent 自动发现）
 *   POST /mcp/kb_search      — 混合语义搜索（BM25 + 向量 + 重排序）
 *   POST /mcp/kb_ingest      — 写入文本到知识库
 *   POST /mcp/kb_ingest_file — 上传文件到知识库（PDF/MD/DOCX/HTML/TXT）
 *   POST /mcp/kb_delete      — 删除文档
 *   GET  /mcp/kb_stats       — 索引状态
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class DocRankMcpServer {

    private final KnowledgeBaseService kb;

    // ============================================================ 工具清单

    @GetMapping("/tools")
    public ResponseEntity<McpManifest> listTools() {
        return ResponseEntity.ok(McpManifest.builder()
                .name("docrank")
                .version("1.0.0")
                .description("本地语义搜索引擎，专为 AI Agent 设计的知识库。支持中文、日文、英文混合搜索。")
                .tools(List.of(
                        McpTool.of("kb_search",
                                "混合语义搜索（Lucene BM25 + 向量 + LLM 重排序），零 API 成本，完全本地运行",
                                Map.of(
                                        "query", "string (required) — 自然语言查询，支持中/日/英",
                                        "top_k", "int (optional, default: 5) — 返回条数",
                                        "scope", "string (optional) — 命名空间隔离，只搜索该 scope",
                                        "tags",  "array<string> (optional) — 按标签过滤")),
                        McpTool.of("kb_ingest",
                                "将文本写入知识库，自动分块、向量化、建立 BM25 索引",
                                Map.of(
                                        "content",    "string (required) — 要写入的文本内容",
                                        "title",      "string (optional) — 文档标题",
                                        "tags",       "array<string> (optional) — 标签，用于过滤",
                                        "scope",      "string (optional, default: global) — 命名空间",
                                        "importance", "double (optional, 0.0~1.0, default: 1.0) — 重要度")),
                        McpTool.of("kb_ingest_file",
                                "上传文件写入知识库（支持 PDF/Markdown/HTML/DOCX/TXT/JSON）",
                                Map.of(
                                        "file",  "multipart file (required)",
                                        "tags",  "array<string> (optional)",
                                        "scope", "string (optional, default: global) — 命名空间")),
                        McpTool.of("kb_delete",
                                "从知识库删除文档（按 doc_id）",
                                Map.of("doc_id", "string (required) — 文档 ID")),
                        McpTool.of("kb_delete_scope",
                                "按 scope 批量删除（GDPR 数据清除）",
                                Map.of("scope", "string (required) — 要清除的命名空间")),
                        McpTool.of("kb_stats",
                                "查看知识库索引状态",
                                Map.of())
                ))
                .build());
    }

    // ============================================================ kb_search

    /**
     * 混合语义搜索
     *
     * 请求体：
     * {
     *   "query": "如何处理并发问题",
     *   "top_k": 5,
     *   "tags": ["技术文档"]
     * }
     */
    @PostMapping("/kb_search")
    public ResponseEntity<McpToolResult> search(@RequestBody Map<String, Object> params) {
        try {
            String query = (String) params.get("query");
            if (query == null || query.isBlank()) {
                return badRequest("query 不能为空");
            }

            int topK = toInt(params.get("top_k"), 5);
            String scope = (String) params.get("scope");

            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) params.get("tags");
            Map<String, Object> filters = new java.util.LinkedHashMap<>();
            if (tags != null && !tags.isEmpty()) filters.put("tags", tags);

            List<SearchResult> results = kb.search(query, topK, scope, filters);

            List<Map<String, Object>> output = results.stream().map(r -> Map.<String, Object>of(
                    "doc_id",       r.getChunk().getDocId(),
                    "title",        nvl(r.getChunk().getTitle()),
                    "section",      nvl(r.getChunk().getSectionPath()),
                    "content",      nvl(r.getChunk().getChunkText()),
                    "score",        r.getScore(),
                    "language",     r.getChunk().getLanguage() != null
                            ? r.getChunk().getLanguage().name() : "UNKNOWN",
                    "tags",         r.getChunk().getTags() != null
                            ? r.getChunk().getTags() : List.of()
            )).toList();

            return ResponseEntity.ok(McpToolResult.ok(Map.of(
                    "query",   query,
                    "total",   output.size(),
                    "results", output
            )));
        } catch (Exception e) {
            log.error("kb_search 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_ingest（文本）

    /**
     * 文本写入知识库
     *
     * 请求体：
     * {
     *   "content": "Spring Boot 是一个...",
     *   "title": "Spring Boot 介绍",
     *   "tags": ["Java", "框架"]
     * }
     */
    @PostMapping("/kb_ingest")
    public ResponseEntity<McpToolResult> ingest(@RequestBody Map<String, Object> params) {
        try {
            String content = (String) params.get("content");
            if (content == null || content.isBlank()) {
                return badRequest("content 不能为空");
            }

            String title = (String) params.getOrDefault("title", "");
            String scope = (String) params.getOrDefault("scope", "global");
            double importance = toDouble(params.get("importance"), 1.0);
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) params.getOrDefault("tags", List.of());
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) params.getOrDefault("metadata", Map.of());

            IngestResult result = kb.ingestText(title, content, tags, metadata,
                    scope, importance, null);

            return result.isSuccess()
                    ? ResponseEntity.ok(McpToolResult.ok(Map.of(
                            "doc_id",      result.getDocId(),
                            "title",       nvl(result.getTitle()),
                            "chunk_count", result.getChunkCount()
                    )))
                    : serverError(result.getError());
        } catch (Exception e) {
            log.error("kb_ingest 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_ingest_file（文件上传）

    /**
     * 文件上传写入知识库
     * 支持：PDF / Markdown / HTML / DOCX / TXT / JSON
     */
    @PostMapping("/kb_ingest_file")
    public ResponseEntity<McpToolResult> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tags",  required = false) List<String> tags,
            @RequestParam(value = "scope", required = false, defaultValue = "global") String scope) {
        try {
            if (file.isEmpty()) return badRequest("文件不能为空");

            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "unknown";

            if (!parserSupports(filename)) {
                return badRequest("不支持的文件格式: " + filename
                        + "。支持：pdf, md, html, docx, txt, json");
            }

            IngestResult result = kb.ingestFile(
                    file.getInputStream(), filename,
                    tags != null ? tags : List.of(), Map.of(),
                    scope, 1.0, null);

            return result.isSuccess()
                    ? ResponseEntity.ok(McpToolResult.ok(Map.of(
                            "doc_id",      result.getDocId(),
                            "title",       nvl(result.getTitle()),
                            "filename",    filename,
                            "chunk_count", result.getChunkCount()
                    )))
                    : serverError(result.getError());
        } catch (Exception e) {
            log.error("kb_ingest_file 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_delete

    /**
     * 删除文档
     *
     * 请求体：{"doc_id": "xxx"}
     */
    @PostMapping("/kb_delete")
    public ResponseEntity<McpToolResult> delete(@RequestBody Map<String, Object> params) {
        try {
            String docId = (String) params.get("doc_id");
            if (docId == null || docId.isBlank()) return badRequest("doc_id 不能为空");

            kb.delete(docId);
            return ResponseEntity.ok(McpToolResult.ok(Map.of("deleted", docId)));
        } catch (Exception e) {
            log.error("kb_delete 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_delete_scope

    /**
     * GDPR 批量删除：按 scope 清除所有数据
     *
     * 请求体：{"scope": "project-x"}
     */
    @PostMapping("/kb_delete_scope")
    public ResponseEntity<McpToolResult> deleteScope(@RequestBody Map<String, Object> params) {
        try {
            String scope = (String) params.get("scope");
            if (scope == null || scope.isBlank()) return badRequest("scope 不能为空");

            kb.deleteByScope(scope);
            return ResponseEntity.ok(McpToolResult.ok(Map.of("deleted_scope", scope)));
        } catch (Exception e) {
            log.error("kb_delete_scope 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_stats

    @GetMapping("/kb_stats")
    public ResponseEntity<McpToolResult> stats() {
        return ResponseEntity.ok(McpToolResult.ok(Map.of(
                "vector_chunks", kb.vectorCount(),
                "bm25_chunks",   kb.bm25Count(),
                "healthy",       kb.isHealthy()
        )));
    }

    // ============================================================ helpers

    private ResponseEntity<McpToolResult> badRequest(String msg) {
        return ResponseEntity.badRequest().body(McpToolResult.fail(msg));
    }

    private ResponseEntity<McpToolResult> serverError(String msg) {
        return ResponseEntity.internalServerError().body(McpToolResult.fail(msg));
    }

    private int toInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }

    private double toDouble(Object val, double defaultVal) {
        if (val instanceof Number n) return n.doubleValue();
        return defaultVal;
    }

    private String nvl(String s) { return s != null ? s : ""; }

    private boolean parserSupports(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf")  || lower.endsWith(".md")
            || lower.endsWith(".html") || lower.endsWith(".docx")
            || lower.endsWith(".txt")  || lower.endsWith(".json");
    }
}
