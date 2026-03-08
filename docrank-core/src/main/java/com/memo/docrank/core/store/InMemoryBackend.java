package com.memo.docrank.core.store;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量索引后端（仅用于测试 / 开发环境）
 *
 * 向量检索：暴力余弦相似度（O(n)），不适合生产。
 * 关键词检索：简单 contains 匹配。
 */
@Slf4j
public class InMemoryBackend implements IndexBackend {

    /** chunk_id → ChunkWithVectors */
    private final Map<String, ChunkWithVectors> store = new ConcurrentHashMap<>();

    @Override
    public void createIndex() {
        log.info("InMemoryBackend 就绪");
    }

    @Override
    public void deleteIndex() {
        store.clear();
        log.info("InMemoryBackend 已清空");
    }

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        for (ChunkWithVectors c : chunks) {
            store.put(c.getChunk().getChunkId(), c);
        }
        log.debug("InMemoryBackend upsert {} 条", chunks.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        store.values().removeIf(c -> docId.equals(c.getChunk().getDocId()));
    }

    @Override
    public void deleteByScope(String scope) {
        store.values().removeIf(c -> scope.equals(c.getChunk().getScope()));
        log.info("InMemoryBackend scope '{}' 数据已清除", scope);
    }

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK,
                                                Map<String, Object> filters) {
        String lq = query.toLowerCase();
        return store.values().stream()
                .filter(c -> matchesFilters(c.getChunk(), filters))
                .filter(c -> {
                    String text = c.getChunk().getChunkText();
                    return text != null && text.toLowerCase().contains(lq);
                })
                .map(c -> RecallCandidate.builder()
                        .chunk(c.getChunk())
                        .score(1.0)
                        .source(RecallCandidate.RecallSource.KEYWORD)
                        .build())
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK,
                                               Map<String, Object> filters) {
        return store.values().stream()
                .filter(c -> matchesFilters(c.getChunk(), filters))
                .map(c -> {
                    double sim = cosineSimilarity(queryVector, c.getVecChunk());
                    return RecallCandidate.builder()
                            .chunk(c.getChunk())
                            .score(sim)
                            .source(RecallCandidate.RecallSource.VECTOR)
                            .build();
                })
                .sorted(Comparator.comparingDouble(RecallCandidate::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isHealthy() { return true; }

    @Override
    public long countChunks() { return store.size(); }

    // ----------------------------------------------------------------- private

    private boolean matchesFilters(Chunk chunk, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            if ("scope".equals(e.getKey())) {
                if (!e.getValue().toString().equals(chunk.getScope())) return false;
            }
        }
        return true;
    }

    private double cosineSimilarity(float[] a, List<Float> bList) {
        if (bList == null || bList.isEmpty()) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, bList.size());
        for (int i = 0; i < len; i++) {
            double ai = a[i], bi = bList.get(i);
            dot   += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
