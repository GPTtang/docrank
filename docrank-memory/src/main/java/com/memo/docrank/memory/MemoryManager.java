package com.memo.docrank.memory;

import com.memo.docrank.core.chunking.ChunkingService;
import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.search.HybridSearcher;
import com.memo.docrank.core.store.IndexBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理器
 *
 * store  → 分块 → 向量化 → 写入 LanceDB（向量）+ Lucene（BM25）
 * recall → HybridSearcher（Lucene BM25 + LanceDB 向量 → RRF → 重排）
 * forget → 同时从 LanceDB 和 Lucene 删除
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryManager {

    private static final double DEFAULT_IMPORTANCE = 0.5;

    private final IndexBackend    vectorBackend;
    private final BM25Index       bm25Index;
    private final EmbeddingProvider embedder;
    private final ChunkingService chunker;
    private final HybridSearcher  searcher;

    public Memory store(String content, String scope, List<String> tags) {
        String memId = UUID.randomUUID().toString();
        List<Chunk> chunks = chunker.chunk(memId, scope, content);

        List<ChunkWithVectors> withVecs = new ArrayList<>();
        for (Chunk chunk : chunks) {
            Chunk enriched = enrich(chunk, scope, tags);
            float[] vec    = embedder.encodeSingle(enriched.getChunkText());
            withVecs.add(ChunkWithVectors.builder()
                    .chunk(enriched)
                    .vecChunk(toFloatList(vec))
                    .build());
        }

        // 双写：LanceDB（向量） + Lucene（BM25）
        vectorBackend.upsertChunks(withVecs);
        bm25Index.addChunks(withVecs.stream().map(ChunkWithVectors::getChunk).toList());

        Memory memory = Memory.builder()
                .id(memId)
                .content(content)
                .scope(scope)
                .importance(DEFAULT_IMPORTANCE)
                .tags(tags)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .accessCount(0)
                .build();

        log.info("记忆写入完成 id={}, scope={}, chunks={}", memId, scope, chunks.size());
        return memory;
    }

    public List<SearchResult> recall(String query, String scope, int topK) {
        Map<String, Object> filters = scope != null ? Map.of("scope", scope) : Map.of();
        List<SearchResult> results  = searcher.search(query, topK, filters);
        log.debug("记忆召回 query='{}', scope={}, hits={}", query, scope, results.size());
        return results;
    }

    public void forget(String memoryId) {
        vectorBackend.deleteByDocId(memoryId);
        bm25Index.deleteByDocId(memoryId);
        log.info("记忆删除完成 id={}", memoryId);
    }

    // ----------------------------------------------------------------- private

    private Chunk enrich(Chunk chunk, String scope, List<String> tags) {
        return Chunk.builder()
                .chunkId(chunk.getChunkId())
                .docId(chunk.getDocId())
                .title(chunk.getTitle())
                .sectionPath(chunk.getSectionPath())
                .chunkText(chunk.getChunkText())
                .chunkIndex(chunk.getChunkIndex())
                .language(chunk.getLanguage())
                .tags(tags)
                .scope(scope != null ? scope : "global")
                .importance(DEFAULT_IMPORTANCE)
                .updatedAt(Instant.now())
                .build();
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }
}
