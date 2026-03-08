package com.memo.docrank.core.search;

import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.model.*;
import com.memo.docrank.core.rerank.Reranker;
import com.memo.docrank.core.store.IndexBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HybridSearcher 单元测试
 * 使用存根（无 Mockito），重点验证 RRF 融合逻辑。
 */
class HybridSearcherTest {

    // ---------------------------------------------------------------- 存根工厂

    private static Chunk chunk(String id) {
        return Chunk.builder()
                .chunkId(id).docId("doc-" + id).title("T")
                .chunkText("text " + id).chunkIndex(0)
                .language(Language.ENGLISH)
                .tags(List.of())
                .build();
    }

    /** BM25 存根：固定返回列表 */
    private static BM25Index bm25(List<RecallCandidate> hits) {
        return new BM25Index() {
            @Override public void addChunk(Chunk c) {}
            @Override public void addChunks(List<Chunk> c) {}
            @Override public void deleteByDocId(String id) {}
            @Override public void deleteAll() {}
            @Override public long count() { return 0; }
            @Override public void close() {}
            @Override public List<RecallCandidate> search(String q, int k, Map<String, Object> f) {
                return hits;
            }
        };
    }

    /** IndexBackend 存根：固定返回向量结果 */
    private static IndexBackend vectorBackend(List<RecallCandidate> hits) {
        return new IndexBackend() {
            @Override public void createIndex() {}
            @Override public void deleteIndex() {}
            @Override public void upsertChunks(List<com.memo.docrank.core.model.ChunkWithVectors> c) {}
            @Override public void deleteByDocId(String id) {}
            @Override public List<RecallCandidate> keywordSearch(String q, int k, Map<String, Object> f) { return List.of(); }
            @Override public List<RecallCandidate> vectorSearch(float[] v, int k, Map<String, Object> f) { return hits; }
            @Override public boolean isHealthy() { return true; }
            @Override public long countChunks() { return 0; }
        };
    }

    /** EmbeddingProvider 存根：返回零向量 */
    private static EmbeddingProvider embedder() {
        return new EmbeddingProvider() {
            @Override public List<float[]> encode(List<String> texts) {
                return texts.stream().map(t -> new float[4]).collect(Collectors.toList());
            }
            @Override public int dimension() { return 4; }
        };
    }

    /** Reranker 存根：直接把 FusedCandidate 转为 SearchResult，不改变排序 */
    private static Reranker passThroughReranker() {
        return (query, candidates, topN) -> candidates.stream()
                .limit(topN)
                .map(c -> SearchResult.builder()
                        .chunk(c.getChunk())
                        .score(c.getFusedScore())
                        .rerankScore(c.getFusedScore())
                        .sparseScore(c.getSparseScore())
                        .vectorScore(c.getVectorScore())
                        .build())
                .collect(Collectors.toList());
    }

    private static HybridSearcher searcher(List<RecallCandidate> bm25Hits,
                                            List<RecallCandidate> vecHits) {
        return new HybridSearcher(bm25(bm25Hits), vectorBackend(vecHits), embedder(), passThroughReranker());
    }

    // ---------------------------------------------------------------- 测试

    @Test
    void bothSourcesEmptyReturnsEmpty() {
        HybridSearcher s = searcher(List.of(), List.of());
        List<SearchResult> results = s.search("query", 5, Map.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void onlyBM25HitsReturnsResults() {
        Chunk c = chunk("a");
        List<RecallCandidate> bm25Hits = List.of(
                RecallCandidate.builder().chunk(c).score(1.0).source(RecallCandidate.RecallSource.KEYWORD).build()
        );
        HybridSearcher s = searcher(bm25Hits, List.of());

        List<SearchResult> results = s.search("query", 5, Map.of());
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).getChunk().getChunkId());
    }

    @Test
    void onlyVectorHitsReturnsResults() {
        Chunk c = chunk("b");
        List<RecallCandidate> vecHits = List.of(
                RecallCandidate.builder().chunk(c).score(0.9).source(RecallCandidate.RecallSource.VECTOR).build()
        );
        HybridSearcher s = searcher(List.of(), vecHits);

        List<SearchResult> results = s.search("query", 5, Map.of());
        assertEquals(1, results.size());
        assertEquals("b", results.get(0).getChunk().getChunkId());
    }

    @Test
    void chunkInBothSourcesGetsHigherFusedScore() {
        Chunk shared = chunk("shared");
        Chunk onlyBm25 = chunk("onlybm25");

        List<RecallCandidate> bm25Hits = List.of(
                RecallCandidate.builder().chunk(shared).score(1.0).source(RecallCandidate.RecallSource.KEYWORD).build(),
                RecallCandidate.builder().chunk(onlyBm25).score(0.5).source(RecallCandidate.RecallSource.KEYWORD).build()
        );
        List<RecallCandidate> vecHits = List.of(
                RecallCandidate.builder().chunk(shared).score(0.95).source(RecallCandidate.RecallSource.VECTOR).build()
        );

        HybridSearcher s = searcher(bm25Hits, vecHits);
        List<SearchResult> results = s.search("query", 5, Map.of());

        assertEquals(2, results.size());
        // shared 出现在两路中，得分应最高，排第一
        assertEquals("shared", results.get(0).getChunk().getChunkId());
    }

    @Test
    void topKLimitsResults() {
        List<RecallCandidate> bm25Hits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bm25Hits.add(RecallCandidate.builder()
                    .chunk(chunk("c" + i)).score(1.0 - i * 0.05)
                    .source(RecallCandidate.RecallSource.KEYWORD).build());
        }
        HybridSearcher s = searcher(bm25Hits, List.of());
        List<SearchResult> results = s.search("query", 3, Map.of());
        assertTrue(results.size() <= 3);
    }

    @Test
    void resultsHavePositiveScore() {
        Chunk c = chunk("x");
        List<RecallCandidate> hits = List.of(
                RecallCandidate.builder().chunk(c).score(0.8).source(RecallCandidate.RecallSource.VECTOR).build()
        );
        HybridSearcher s = searcher(List.of(), hits);
        List<SearchResult> results = s.search("query", 5, Map.of());
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getScore() > 0);
    }

    @Test
    void rrfScoreDecreasesByRank() {
        // RRF: score[i] = 1 / (60 + rank). 排名越高分越低。
        // 构造 3 个 BM25 命中，验证 RRF 分值单调递减
        List<RecallCandidate> bm25Hits = List.of(
                RecallCandidate.builder().chunk(chunk("r1")).score(3.0).source(RecallCandidate.RecallSource.KEYWORD).build(),
                RecallCandidate.builder().chunk(chunk("r2")).score(2.0).source(RecallCandidate.RecallSource.KEYWORD).build(),
                RecallCandidate.builder().chunk(chunk("r3")).score(1.0).source(RecallCandidate.RecallSource.KEYWORD).build()
        );
        HybridSearcher s = searcher(bm25Hits, List.of());
        List<SearchResult> results = s.search("query", 5, Map.of());

        assertEquals(3, results.size());
        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
        assertTrue(results.get(1).getScore() >= results.get(2).getScore());
    }
}
