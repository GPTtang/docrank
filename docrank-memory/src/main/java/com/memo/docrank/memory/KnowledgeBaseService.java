package com.memo.docrank.memory;

import com.memo.docrank.core.chunking.ChunkingService;
import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.ingest.ParsedDocument;
import com.memo.docrank.core.ingest.ParserRegistry;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;
import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.search.HybridSearcher;
import com.memo.docrank.core.store.IndexBackend;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
public class KnowledgeBaseService {

    private final IndexBackend      vectorBackend;
    private final BM25Index         bm25Index;
    private final EmbeddingProvider embedder;
    private final ChunkingService   chunker;
    private final HybridSearcher    searcher;
    private final ParserRegistry    parserRegistry;
    private final boolean           dedupEnabled;
    private final double            dedupThreshold;

    public KnowledgeBaseService(IndexBackend vectorBackend,
                                BM25Index bm25Index,
                                EmbeddingProvider embedder,
                                ChunkingService chunker,
                                HybridSearcher searcher,
                                ParserRegistry parserRegistry) {
        this(vectorBackend, bm25Index, embedder, chunker, searcher, parserRegistry, false, 0.95);
    }

    public KnowledgeBaseService(IndexBackend vectorBackend,
                                BM25Index bm25Index,
                                EmbeddingProvider embedder,
                                ChunkingService chunker,
                                HybridSearcher searcher,
                                ParserRegistry parserRegistry,
                                boolean dedupEnabled,
                                double dedupThreshold) {
        this.vectorBackend  = vectorBackend;
        this.bm25Index      = bm25Index;
        this.embedder       = embedder;
        this.chunker        = chunker;
        this.searcher       = searcher;
        this.parserRegistry = parserRegistry;
        this.dedupEnabled   = dedupEnabled;
        this.dedupThreshold = dedupThreshold;
    }

    // ---------------------------------------------------------------- ingest

    /**
     * 从文件流写入知识库（支持 PDF/MD/HTML/DOCX/JSON/TXT）
     */
    public IngestResult ingestFile(InputStream input, String filename,
                                   List<String> tags, Map<String, String> metadata) {
        String docId = UUID.randomUUID().toString();
        try {
            ParsedDocument parsed = parserRegistry.parse(input, filename, docId);
            return indexDocument(parsed, tags, metadata);
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
            return indexDocument(withTitle, tags, metadata);
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

    // ---------------------------------------------------------------- delete

    /**
     * 删除文档（从 LanceDB 和 Lucene 双删）
     */
    public void delete(String docId) {
        vectorBackend.deleteByDocId(docId);
        bm25Index.deleteByDocId(docId);
        log.info("文档删除完成 docId={}", docId);
    }

    // ---------------------------------------------------------------- stats

    public long vectorCount() { return vectorBackend.countChunks(); }
    public long bm25Count()   { return bm25Index.count(); }
    public boolean isHealthy() { return vectorBackend.isHealthy(); }

    // ---------------------------------------------------------------- private

    private IngestResult indexDocument(ParsedDocument parsed,
                                       List<String> tags,
                                       Map<String, String> metadata) {
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
                            .updatedAt(now).build())
                    .toList();
        }

        if (allChunks.isEmpty()) {
            return IngestResult.fail(parsed.getDocId(), "文档内容为空，无法分块");
        }

        // 批量向量化
        List<String> texts = allChunks.stream().map(Chunk::getChunkText).toList();
        List<float[]> vecs = embedder.encode(texts);

        // 去重过滤（向量化之后、双写之前）
        List<ChunkWithVectors> toWrite = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < allChunks.size(); i++) {
            if (isDuplicate(vecs.get(i))) {
                log.debug("跳过重复 chunk: {} (index={})", allChunks.get(i).getTitle(), i);
                skipped++;
            } else {
                toWrite.add(ChunkWithVectors.builder()
                        .chunk(allChunks.get(i))
                        .vecChunk(toFloatList(vecs.get(i)))
                        .build());
            }
        }

        // 双写索引
        if (!toWrite.isEmpty()) {
            vectorBackend.upsertChunks(toWrite);
            bm25Index.addChunks(toWrite.stream()
                    .map(ChunkWithVectors::getChunk)
                    .collect(Collectors.toList()));
        }

        log.info("文档写入完成 docId={}, title='{}', chunks={}, skipped={}",
                parsed.getDocId(), parsed.getTitle(), toWrite.size(), skipped);

        return IngestResult.ok(parsed.getDocId(), parsed.getTitle(), toWrite.size(), skipped);
    }

    private boolean isDuplicate(float[] vec) {
        if (!dedupEnabled) return false;
        List<RecallCandidate> nearest = vectorBackend.vectorSearch(vec, 1, Map.of());
        if (nearest.isEmpty()) return false;
        double similarity = nearest.get(0).getScore();
        return similarity >= dedupThreshold;
    }

    // ---------------------------------------------------------------- reembed

    /**
     * 用当前 Embedding 模型重新生成所有向量并 upsert 回去。
     * 不改变 chunk 文本，不触及 Lucene 索引。
     */
    public ReembedResult reembed(int batchSize) {
        long start = System.currentTimeMillis();
        int total = 0;
        int offset = 0;

        while (true) {
            List<Chunk> batch = vectorBackend.listAllChunks(offset, batchSize);
            if (batch.isEmpty()) break;

            List<String> texts = batch.stream()
                    .map(Chunk::getChunkText)
                    .collect(Collectors.toList());
            List<float[]> vecs = embedder.encode(texts);

            List<ChunkWithVectors> updated = IntStream.range(0, batch.size())
                    .mapToObj(i -> ChunkWithVectors.builder()
                            .chunk(batch.get(i))
                            .vecChunk(toFloatList(vecs.get(i)))
                            .build())
                    .collect(Collectors.toList());

            vectorBackend.upsertChunks(updated);

            total += batch.size();
            offset += batchSize;
            log.info("reembed 进度: {} chunks 已处理", total);
        }

        return ReembedResult.builder()
                .chunkCount(total)
                .elapsedMs(System.currentTimeMillis() - start)
                .build();
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }
}
