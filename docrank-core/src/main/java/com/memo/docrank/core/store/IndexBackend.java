package com.memo.docrank.core.store;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;

import java.util.List;
import java.util.Map;

public interface IndexBackend {

    void createIndex();
    void deleteIndex();

    void upsertChunks(List<ChunkWithVectors> chunks);
    void deleteByDocId(String docId);

    List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters);
    List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters);

    boolean isHealthy();
    long countChunks();

    /**
     * 分页拉取所有 chunk（用于重新向量化）。
     * 不支持的后端抛出 UnsupportedOperationException。
     */
    default List<Chunk> listAllChunks(int offset, int limit) {
        throw new UnsupportedOperationException("该后端不支持 listAllChunks");
    }
}
