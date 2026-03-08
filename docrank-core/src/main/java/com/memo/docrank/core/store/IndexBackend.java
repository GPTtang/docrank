package com.memo.docrank.core.store;

import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;

import java.util.List;
import java.util.Map;

public interface IndexBackend {

    void createIndex();
    void deleteIndex();

    void upsertChunks(List<ChunkWithVectors> chunks);
    void deleteByDocId(String docId);
    /** 按 scope 批量删除（GDPR 清除）*/
    default void deleteByScope(String scope) {}

    List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters);
    List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters);

    boolean isHealthy();
    long countChunks();
}
