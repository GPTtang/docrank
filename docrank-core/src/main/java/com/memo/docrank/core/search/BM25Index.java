package com.memo.docrank.core.search;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.RecallCandidate;

import java.util.List;
import java.util.Map;

/**
 * BM25 全文索引接口
 */
public interface BM25Index {

    void addChunk(Chunk chunk);

    void addChunks(List<Chunk> chunks);

    void deleteByDocId(String docId);

    void deleteAll();

    /**
     * BM25 检索，支持多语言（中/日/英自动路由分析器）
     */
    List<RecallCandidate> search(String query, int topK, Map<String, Object> filters);

    long count();

    void close();
}
