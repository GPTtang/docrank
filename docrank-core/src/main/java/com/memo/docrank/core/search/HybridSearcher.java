package com.memo.docrank.core.search;

import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.model.FusedCandidate;
import com.memo.docrank.core.model.RecallCandidate;
import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.core.rerank.Reranker;
import com.memo.docrank.core.store.IndexBackend;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合检索器
 *
 * 两路并行召回：
 *   - Lucene BM25（CJK 感知，本地磁盘索引）
 *   - LanceDB 向量检索（余弦相似度）
 * 融合：RRF (Reciprocal Rank Fusion)
 * 精排：ONNX bge-reranker-v2-m3
 */
@Slf4j
public class HybridSearcher {

    private static final int RRF_K = 60;

    private final BM25Index         bm25Index;
    private final IndexBackend      vectorBackend;
    private final EmbeddingProvider embedder;
    private final Reranker          reranker;
    private final AdvancedScorer    advancedScorer;

    public HybridSearcher(BM25Index bm25Index,
                          IndexBackend vectorBackend,
                          EmbeddingProvider embedder,
                          Reranker reranker) {
        this(bm25Index, vectorBackend, embedder, reranker, new AdvancedScorer());
    }

    public HybridSearcher(BM25Index bm25Index,
                          IndexBackend vectorBackend,
                          EmbeddingProvider embedder,
                          Reranker reranker,
                          AdvancedScorer advancedScorer) {
        this.bm25Index      = bm25Index;
        this.vectorBackend  = vectorBackend;
        this.embedder       = embedder;
        this.reranker       = reranker;
        this.advancedScorer = advancedScorer;
    }

    public List<SearchResult> search(String query, int topK, Map<String, Object> filters) {
        int recallSize = topK * 5;  // 扩大召回池，给高级评分留余量

        // 1. 并行两路召回
        CompletableFuture<List<RecallCandidate>> bm25Future = CompletableFuture
                .supplyAsync(() -> bm25Index.search(query, recallSize, filters));

        float[] queryVec = embedder.encodeSingle(query);
        CompletableFuture<List<RecallCandidate>> vecFuture = CompletableFuture
                .supplyAsync(() -> vectorBackend.vectorSearch(queryVec, recallSize, filters));

        List<RecallCandidate> bm25Results = bm25Future.join();
        List<RecallCandidate> vecResults  = vecFuture.join();

        log.debug("Lucene BM25 {} 条，LanceDB 向量 {} 条", bm25Results.size(), vecResults.size());

        // 2. 过滤已过期的 chunk（TTL）
        bm25Results = filterExpired(bm25Results);
        vecResults  = filterExpired(vecResults);

        // 3. RRF 融合
        List<FusedCandidate> fused = reciprocalRankFusion(bm25Results, vecResults);

        // 4. ONNX 重排序（候选数扩大到 topK*3）
        List<SearchResult> reranked = reranker.rerank(query, fused, topK * 3);

        // 5. 高级评分（时效性 + 重要度 + MMR + 阈值过滤）
        return advancedScorer.score(reranked, topK);
    }

    private List<RecallCandidate> filterExpired(List<RecallCandidate> candidates) {
        java.time.Instant now = java.time.Instant.now();
        return candidates.stream()
                .filter(c -> c.getChunk().getExpiresAt() == null
                        || c.getChunk().getExpiresAt().isAfter(now))
                .collect(java.util.stream.Collectors.toList());
    }

    // ----------------------------------------------------------------- RRF

    private List<FusedCandidate> reciprocalRankFusion(List<RecallCandidate> sparse,
                                                       List<RecallCandidate> vector) {
        Map<String, FusedCandidate> map = new LinkedHashMap<>();

        for (int i = 0; i < sparse.size(); i++) {
            RecallCandidate rc = sparse.get(i);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            map.put(rc.getChunk().getChunkId(),
                    FusedCandidate.builder()
                            .chunk(rc.getChunk())
                            .sparseScore(rc.getScore())
                            .sparseRank(i + 1)
                            .fusedScore(rrfScore)
                            .source(RecallCandidate.RecallSource.KEYWORD)
                            .build());
        }

        for (int i = 0; i < vector.size(); i++) {
            RecallCandidate rc = vector.get(i);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            String id = rc.getChunk().getChunkId();
            if (map.containsKey(id)) {
                FusedCandidate existing = map.get(id);
                map.put(id, existing.toBuilder()
                        .vectorScore(rc.getScore())
                        .vectorRank(i + 1)
                        .fusedScore(existing.getFusedScore() + rrfScore)
                        .source(RecallCandidate.RecallSource.HYBRID)
                        .build());
            } else {
                map.put(id, FusedCandidate.builder()
                        .chunk(rc.getChunk())
                        .vectorScore(rc.getScore())
                        .vectorRank(i + 1)
                        .fusedScore(rrfScore)
                        .source(RecallCandidate.RecallSource.VECTOR)
                        .build());
            }
        }

        return map.values().stream()
                .sorted(Comparator.comparingDouble(FusedCandidate::getFusedScore).reversed())
                .collect(Collectors.toList());
    }
}
