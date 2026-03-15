package com.memo.docrank.core.store;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector backend for testing and development.
 */
@Slf4j
public class InMemoryBackend implements IndexBackend {

    /** chunk_id -> ChunkWithVectors */
    private final Map<String, ChunkWithVectors> store = new ConcurrentHashMap<>();

    @Override
    public void createIndex() {
        log.info("InMemoryBackend ready");
    }

    @Override
    public void deleteIndex() {
        store.clear();
        log.info("InMemoryBackend cleared");
    }

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        for (ChunkWithVectors c : chunks) {
            store.put(c.getChunk().getChunkId(), c);
        }
        log.debug("InMemoryBackend upsert {} rows", chunks.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        store.values().removeIf(c -> docId.equals(c.getChunk().getDocId()));
    }

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters) {
        String lq = query.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(c -> matchesFilters(c.getChunk(), filters))
                .filter(c -> {
                    String text = c.getChunk().getChunkText();
                    return text != null && text.toLowerCase(Locale.ROOT).contains(lq);
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
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters) {
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
    public boolean isHealthy() {
        return true;
    }

    @Override
    public long countChunks() {
        return store.size();
    }

    @Override
    public List<Chunk> listAllChunks(int offset, int limit) {
        return store.values().stream()
                .map(ChunkWithVectors::getChunk)
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private boolean matchesFilters(Chunk chunk, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (!matchesFilter(chunk, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(Chunk chunk, String key, Object value) {
        if (key == null || key.isBlank()) {
            return true;
        }
        return switch (key) {
            case "doc_id" -> matchesValue(chunk.getDocId(), value);
            case "title" -> matchesValue(chunk.getTitle(), value);
            case "section_path" -> matchesValue(chunk.getSectionPath(), value);
            case "language" -> matchesValue(chunk.getLanguage() != null ? chunk.getLanguage().name() : null, value);
            case "tags" -> matchesTags(chunk.getTags(), value);
            default -> true;
        };
    }

    private boolean matchesValue(String actual, Object expected) {
        if (expected instanceof List<?> list) {
            return list.stream().anyMatch(v -> equalsIgnoreCase(actual, v));
        }
        return equalsIgnoreCase(actual, expected);
    }

    private boolean matchesTags(List<String> tags, Object expected) {
        if (expected instanceof List<?> list) {
            for (Object value : list) {
                if (tagContains(tags, value)) {
                    return true;
                }
            }
            return false;
        }
        return tagContains(tags, expected);
    }

    private boolean tagContains(List<String> tags, Object expected) {
        if (tags == null || tags.isEmpty() || expected == null) {
            return false;
        }
        String expectedNorm = expected.toString().toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).equals(expectedNorm)) {
                return true;
            }
        }
        return false;
    }

    private boolean equalsIgnoreCase(String actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return actual.equalsIgnoreCase(expected.toString());
    }

    private double cosineSimilarity(float[] a, List<Float> bList) {
        if (bList == null || bList.isEmpty()) {
            return 0.0;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;
        int len = Math.min(a.length, bList.size());
        for (int i = 0; i < len; i++) {
            double ai = a[i];
            double bi = bList.get(i);
            dot += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
