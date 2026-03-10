package com.memo.docrank.mcp;

import com.memo.docrank.agent.AgentChatResult;
import com.memo.docrank.agent.AgentService;
import com.memo.docrank.memory.IngestResult;
import com.memo.docrank.memory.KnowledgeBaseService;
import com.memo.docrank.memory.ReembedResult;
import com.memo.docrank.core.model.SearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class DocRankMcpServer {

    private final KnowledgeBaseService kb;

    @Setter
    @Autowired(required = false)
    private AgentService agentService;

    // ============================================================ 工具清单

    @Operation(summary = "工具清单", description = "返回所有 MCP 工具定义，供 AI Agent 自动发现")
    @Tag(name = "知识库")
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
                                        "tags",  "array<string> (optional) — 按标签过滤")),
                        McpTool.of("kb_ingest",
                                "将文本写入知识库，自动分块、向量化、建立 BM25 索引",
                                Map.of(
                                        "content",    "string (required) — 要写入的文本内容",
                                        "title",      "string (optional) — 文档标题",
                                        "tags",       "array<string> (optional) — 标签，用于过滤",
                                        "importance", "double (optional, 0.0~1.0, default: 1.0) — 重要度")),
                        McpTool.of("kb_ingest_file",
                                "上传文件写入知识库（支持 PDF/Markdown/HTML/DOCX/TXT/JSON/XLSX/PPTX/EPUB/CSV）",
                                Map.of(
                                        "file",  "multipart file (required)",
                                        "tags",  "array<string> (optional)")),
                        McpTool.of("kb_delete",
                                "从知识库删除文档（按 doc_id）",
                                Map.of("doc_id", "string (required) — 文档 ID")),
                        McpTool.of("kb_stats",
                                "查看知识库索引状态",
                                Map.of()),
                        McpTool.of("kb_reembed",
                                "用当前 Embedding 模型重新生成所有向量（升级模型后使用）",
                                Map.of("batch_size", "int (optional, default: 100)")),
                        McpTool.of("agent_chat",
                                "RAG 问答：从知识库检索相关内容，由 LLM 生成回答（需 docrank.agent.enabled=true）",
                                Map.of(
                                        "question",   "string (required) — 用户问题",
                                        "session_id", "string (optional) — 会话 ID，用于多轮对话")),
                        McpTool.of("agent_new_session",
                                "创建新会话，返回生成的 session_id",
                                Map.of()),
                        McpTool.of("agent_clear_session",
                                "清空指定会话的历史记录",
                                Map.of("session_id", "string (required) — 要清空的会话 ID"))
                ))
                .build());
    }

    // ============================================================ kb_search

    @Operation(summary = "混合语义搜索", description = "BM25 + 向量检索 + 重排序，支持按 tags 过滤")
    @Tag(name = "知识库")
    @PostMapping("/kb_search")
    public ResponseEntity<McpToolResult> search(@RequestBody Map<String, Object> params) {
        try {
            String query = (String) params.get("query");
            if (query == null || query.isBlank()) return badRequest("query 不能为空");

            int topK = toInt(params.get("top_k"), 5);

            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) params.get("tags");
            Map<String, Object> filters = new java.util.LinkedHashMap<>();
            if (tags != null && !tags.isEmpty()) filters.put("tags", tags);

            List<SearchResult> results = kb.search(query, topK, filters);

            List<Map<String, Object>> output = results.stream().map(r -> Map.<String, Object>of(
                    "doc_id",   r.getChunk().getDocId(),
                    "title",    nvl(r.getChunk().getTitle()),
                    "section",  nvl(r.getChunk().getSectionPath()),
                    "content",  nvl(r.getChunk().getChunkText()),
                    "score",    r.getScore(),
                    "language", r.getChunk().getLanguage() != null ? r.getChunk().getLanguage().name() : "UNKNOWN",
                    "tags",     r.getChunk().getTags() != null ? r.getChunk().getTags() : List.of()
            )).toList();

            return ResponseEntity.ok(McpToolResult.ok(Map.of(
                    "query", query, "total", output.size(), "results", output)));
        } catch (Exception e) {
            log.error("kb_search 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_ingest

    @Operation(summary = "写入文本", description = "将文本写入知识库，自动分块、向量化、建立 BM25 索引")
    @Tag(name = "知识库")
    @PostMapping("/kb_ingest")
    public ResponseEntity<McpToolResult> ingest(@RequestBody Map<String, Object> params) {
        try {
            String content = (String) params.get("content");
            if (content == null || content.isBlank()) return badRequest("content 不能为空");

            String title = (String) params.getOrDefault("title", "");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) params.getOrDefault("tags", List.of());
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) params.getOrDefault("metadata", Map.of());

            IngestResult result = kb.ingestText(title, content, tags, metadata);

            return result.isSuccess()
                    ? ResponseEntity.ok(McpToolResult.ok(Map.of(
                            "doc_id", result.getDocId(), "title", nvl(result.getTitle()),
                            "chunk_count", result.getChunkCount(), "skipped_chunks", result.getSkippedChunks())))
                    : serverError(result.getError());
        } catch (Exception e) {
            log.error("kb_ingest 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_ingest_file

    @Operation(summary = "上传文件", description = "支持 PDF / Markdown / HTML / DOCX / TXT / JSON / XLSX / PPTX / EPUB / CSV")
    @Tag(name = "知识库")
    @PostMapping("/kb_ingest_file")
    public ResponseEntity<McpToolResult> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tags", required = false) List<String> tags) {
        try {
            if (file.isEmpty()) return badRequest("文件不能为空");

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            if (!parserSupports(filename)) {
                return badRequest("不支持的文件格式: " + filename
                        + "。支持：pdf, md, html, docx, txt, json, xlsx, pptx, epub, csv");
            }

            IngestResult result = kb.ingestFile(
                    file.getInputStream(), filename, tags != null ? tags : List.of(), Map.of());

            return result.isSuccess()
                    ? ResponseEntity.ok(McpToolResult.ok(Map.of(
                            "doc_id", result.getDocId(), "title", nvl(result.getTitle()),
                            "filename", filename, "chunk_count", result.getChunkCount(),
                            "skipped_chunks", result.getSkippedChunks())))
                    : serverError(result.getError());
        } catch (Exception e) {
            log.error("kb_ingest_file 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ kb_reembed

    @Operation(summary = "重新向量化", description = "用当前 Embedding 模型重新生成所有向量，适用于升级模型后的数据迁移")
    @Tag(name = "知识库")
    @PostMapping("/kb_reembed")
    public ResponseEntity<McpToolResult> reembed(@RequestBody(required = false) Map<String, Object> params) {
        try {
            int batchSize = params != null ? ((Number) params.getOrDefault("batch_size", 100)).intValue() : 100;
            ReembedResult result = kb.reembed(batchSize);
            return ResponseEntity.ok(McpToolResult.ok(Map.of(
                    "chunk_count", result.getChunkCount(), "elapsed_ms", result.getElapsedMs())));
        } catch (Exception e) {
            log.error("reembed 失败", e);
            return ResponseEntity.ok(McpToolResult.fail(e.getMessage()));
        }
    }

    // ============================================================ kb_delete

    @Operation(summary = "删除文档", description = "按 doc_id 从知识库删除文档（同时删除向量索引和 BM25 索引）")
    @Tag(name = "知识库")
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

    // ============================================================ kb_stats

    @Operation(summary = "索引状态", description = "返回向量索引和 BM25 索引的 chunk 数量及健康状态")
    @Tag(name = "知识库")
    @GetMapping("/kb_stats")
    public ResponseEntity<McpToolResult> stats() {
        return ResponseEntity.ok(McpToolResult.ok(Map.of(
                "vector_chunks", kb.vectorCount(),
                "bm25_chunks",   kb.bm25Count(),
                "healthy",       kb.isHealthy())));
    }

    // ============================================================ agent_chat

    @Operation(summary = "RAG 问答", description = "从知识库检索相关内容，由 LLM 生成回答；支持多轮会话（session_id）")
    @Tag(name = "Agent")
    @PostMapping("/agent_chat")
    public ResponseEntity<McpToolResult> agentChat(@RequestBody Map<String, Object> params) {
        if (agentService == null) return badRequest("AI Agent 未启用，请设置 docrank.agent.enabled=true 并配置 API Key");
        try {
            String question = (String) params.get("question");
            if (question == null || question.isBlank()) return badRequest("question 不能为空");
            String sessionId = (String) params.getOrDefault("session_id", "default");

            AgentChatResult result = agentService.chat(sessionId, question);

            List<Map<String, Object>> sources = result.sources().stream().map(r -> Map.<String, Object>of(
                    "doc_id",  r.getChunk().getDocId(),
                    "title",   nvl(r.getChunk().getTitle()),
                    "content", nvl(r.getChunk().getChunkText()),
                    "score",   r.getScore()
            )).toList();

            return ResponseEntity.ok(McpToolResult.ok(Map.of(
                    "answer", result.answer(), "session_id", sessionId, "sources", sources)));
        } catch (Exception e) {
            log.error("agent_chat 失败", e);
            return serverError(e.getMessage());
        }
    }

    // ============================================================ agent_new_session

    @Operation(summary = "创建会话", description = "创建新的对话会话，返回自动生成的 session_id")
    @Tag(name = "Agent")
    @PostMapping("/agent_new_session")
    public ResponseEntity<McpToolResult> agentNewSession() {
        if (agentService == null) return badRequest("AI Agent 未启用，请设置 docrank.agent.enabled=true 并配置 API Key");
        return ResponseEntity.ok(McpToolResult.ok(Map.of("session_id", agentService.newSession())));
    }

    // ============================================================ agent_clear_session

    @Operation(summary = "清空会话历史", description = "清空指定 session_id 的对话历史，保留 session 本身")
    @Tag(name = "Agent")
    @PostMapping("/agent_clear_session")
    public ResponseEntity<McpToolResult> agentClearSession(@RequestBody Map<String, Object> params) {
        if (agentService == null) return badRequest("AI Agent 未启用，请设置 docrank.agent.enabled=true 并配置 API Key");
        String sessionId = (String) params.get("session_id");
        if (sessionId == null || sessionId.isBlank()) return badRequest("session_id 不能为空");
        agentService.clearSession(sessionId);
        return ResponseEntity.ok(McpToolResult.ok(Map.of("cleared", sessionId)));
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

    private String nvl(String s) { return s != null ? s : ""; }

    private boolean parserSupports(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf")  || lower.endsWith(".md")
            || lower.endsWith(".html") || lower.endsWith(".docx")
            || lower.endsWith(".txt")  || lower.endsWith(".json")
            || lower.endsWith(".xlsx") || lower.endsWith(".pptx")
            || lower.endsWith(".epub") || lower.endsWith(".csv");
    }
}
