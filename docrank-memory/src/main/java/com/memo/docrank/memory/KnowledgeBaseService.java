package com.memo.docrank.memory;

import com.memo.docrank.core.chunking.ChunkingService;
import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.ingest.ParsedDocument;
import com.memo.docrank.core.ingest.ParserRegistry;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.search.HybridSearcher;
import com.memo.docrank.core.store.IndexBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库核心服务 — 专为 AI Agent 设计的本地语义搜索引擎
 *
 * 写入链路：
 *   file/text → ParserRegistry → ParsedDocument
 *            → ChunkingService（CJK 感知分块）
 *            → EmbeddingProvider（BGE-M3 本地向量化）
 *            → 双写：LanceDB（向量） + Lucene（BM25）
 *
 * 检索链路：
 *   query → HybridSearcher
 *         → Lucene BM25 ‖ LanceDB 向量（并行）
 *         → RRF 融合
 *         → bge-reranker-v2-m3 精排
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final IndexBackend      vectorBackend;
    private final BM25Index         bm25Index;
    private final EmbeddingProvider embedder;
    private final ChunkingService   chunker;
    private final HybridSearcher    searcher;
    private final ParserRegistry    parserRegistry;

    // ---------------------------------------------------------------- ingest

    /**
     * 从文件流写入知识库（支持 PDF/MD/HTML/DOCX/JSON/TXT）
     */
    public IngestResult ingestFile(InputStream input, String filename,
                                   List<String> tags, Map<String, String> metadata) {
        return ingestFile(input, filename, tags, metadata, "global", 1.0, null);
    }

    /**
     * 从文件流写入知识库（带 scope / importance / TTL 参数）
     */
    public IngestResult ingestFile(InputStream input, String filename,
                                   List<String> tags, Map<String, String> metadata,
                                   String scope, double importance, java.time.Instant expiresAt) {
        String docId = UUID.randomUUID().toString();
        try {
            ParsedDocument parsed = parserRegistry.parse(input, filename, docId);
            return indexDocument(parsed, tags, metadata, scope, importance, expiresAt);
        } catch (Exception e) {
            log.error("文件写入失败 filename={}: {}", filename, e.getMessage(), e);
            return IngestResult.fail(docId, e.getMessage());
        }
    }

    /**
     * 从原始文本写入知识库
     */
    public IngestResult ingestText(String title, String content,
                                   List<String> tags, Map<String, String> metadata) {
        return ingestText(title, content, tags, metadata, "global", 1.0, null);
    }

    /**
     * 从原始文本写入知识库（带 scope / importance / TTL 参数）
     */
    public IngestResult ingestText(String title, String content,
                                   List<String> tags, Map<String, String> metadata,
                                   String scope, double importance, java.time.Instant expiresAt) {
        String docId = UUID.randomUUID().toString();
        try {
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            String filename = (title != null && !title.isBlank() ? title : docId) + ".txt";
            ParsedDocument parsed = parserRegistry.parse(stream, filename, docId);
            ParsedDocument withTitle = ParsedDocument.builder()
                    .docId(parsed.getDocId())
                    .title(title != null && !title.isBlank() ? title : parsed.getTitle())
                    .text(parsed.getText())
                    .sections(parsed.getSections())
                    .metadata(metadata != null ? metadata : parsed.getMetadata())
                    .mimeType("text/plain")
                    .build();
            return indexDocument(withTitle, tags, metadata, scope, importance, expiresAt);
        } catch (Exception e) {
            log.error("文本写入失败 title={}: {}", title, e.getMessage(), e);
            return IngestResult.fail(docId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * 混合语义搜索（BM25 + 向量 + 重排序）
     *
     * @param query   自然语言查询，支持中/日/英
     * @param topK    返回条数
     * @param filters 过滤条件，例如 {"scope": "project-x", "tags": ["技术文档"]}
     */
    public List<SearchResult> search(String query, int topK, Map<String, Object> filters) {
        if (query == null || query.isBlank()) return List.of();
        List<SearchResult> results = searcher.search(query, topK, filters);
        log.debug("kb_search query='{}' → {} 条结果", query, results.size());
        return results;
    }

    /**
     * 按 scope 过滤的混合语义搜索
     */
    public List<SearchResult> search(String query, int topK, String scope,
                                     Map<String, Object> extraFilters) {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        if (extraFilters != null) filters.putAll(extraFilters);
        if (scope != null && !scope.isBlank()) filters.put("scope", scope);
        return search(query, topK, filters);
    }

    // ---------------------------------------------------------------- delete

    /**
     * 删除文档（从 LanceDB 和 Lucene 双删）
     */
    public void delete(String docId) {
        vectorBackend.deleteByDocId(docId);
        bm25Index.deleteByDocId(docId);
        log.info("文档删除完成 docId={}", docId);
    }

    /**
     * GDPR 删除：按 scope 批量删除所有文档
     */
    public void deleteByScope(String scope) {
        vectorBackend.deleteByScope(scope);
        bm25Index.deleteByScope(scope);
        log.info("Scope 数据清除完成 scope={}", scope);
    }

    // ---------------------------------------------------------------- stats

    public long vectorCount() { return vectorBackend.countChunks(); }
    public long bm25Count()   { return bm25Index.count(); }
    public boolean isHealthy() { return vectorBackend.isHealthy(); }

    // ---------------------------------------------------------------- private

    private IngestResult indexDocument(ParsedDocument parsed,
                                       List<String> tags,
                                       Map<String, String> metadata,
                                       String scope, double importance,
                                       java.time.Instant expiresAt) {
        String resolvedScope = scope != null && !scope.isBlank() ? scope : "global";
        List<Chunk> allChunks = new ArrayList<>();
        Instant now = Instant.now();

        // 优先按 Section 分块，保留文档结构
        if (parsed.getSections() != null && !parsed.getSections().isEmpty()) {
            for (ParsedDocument.Section section : parsed.getSections()) {
                List<Chunk> sectionChunks = chunker.chunk(
                        parsed.getDocId(), parsed.getTitle(), section.getContent());
                for (Chunk chunk : sectionChunks) {
                    allChunks.add(Chunk.builder()
                            .chunkId(chunk.getChunkId())
                            .docId(chunk.getDocId())
                            .title(parsed.getTitle())
                            .sectionPath(section.getPath())
                            .chunkText(chunk.getChunkText())
                            .chunkIndex(chunk.getChunkIndex())
                            .language(chunk.getLanguage())
                            .tags(tags != null ? tags : List.of())
                            .scope(resolvedScope)
                            .importance(importance)
                            .expiresAt(expiresAt)
                            .updatedAt(now)
                            .build());
                }
            }
        } else {
            // 无结构：直接对全文分块
            allChunks = chunker.chunk(parsed.getDocId(), parsed.getTitle(), parsed.getText())
                    .stream().map(c -> Chunk.builder()
                            .chunkId(c.getChunkId()).docId(c.getDocId())
                            .title(parsed.getTitle()).sectionPath("")
                            .chunkText(c.getChunkText()).chunkIndex(c.getChunkIndex())
                            .language(c.getLanguage())
                            .tags(tags != null ? tags : List.of())
                            .scope(resolvedScope)
                            .importance(importance)
                            .expiresAt(expiresAt)
                            .updatedAt(now).build())
                    .toList();
        }

        if (allChunks.isEmpty()) {
            return IngestResult.fail(parsed.getDocId(), "文档内容为空，无法分块");
        }

        // 批量向量化
        List<String> texts = allChunks.stream().map(Chunk::getChunkText).toList();
        List<float[]> vecs = embedder.encode(texts);

        List<ChunkWithVectors> withVecs = new ArrayList<>();
        for (int i = 0; i < allChunks.size(); i++) {
            withVecs.add(ChunkWithVectors.builder()
                    .chunk(allChunks.get(i))
                    .vecChunk(toFloatList(vecs.get(i)))
                    .build());
        }

        // 双写索引
        vectorBackend.upsertChunks(withVecs);
        bm25Index.addChunks(allChunks);

        log.info("文档写入完成 docId={}, title='{}', chunks={}",
                parsed.getDocId(), parsed.getTitle(), allChunks.size());

        return IngestResult.ok(parsed.getDocId(), parsed.getTitle(), allChunks.size());
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }
}
