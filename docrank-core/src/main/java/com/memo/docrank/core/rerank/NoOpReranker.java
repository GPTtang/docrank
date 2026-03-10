package com.memo.docrank.core.rerank;

import com.memo.docrank.core.model.FusedCandidate;
import com.memo.docrank.core.model.SearchResult;

import java.util.List;

/**
 * 空操作 Reranker（仅用于演示 / 测试）。
 *
 * <p>不需要 ONNX 模型文件，直接将 FusedCandidate 按 fusedScore 转为 SearchResult。
 * 通过 {@code docrank.reranker.enabled: false} 启用。
 */
public class NoOpReranker implements Reranker {

    @Override
    public List<SearchResult> rerank(String query, List<FusedCandidate> candidates, int topN) {
        return candidates.stream()
                .limit(topN)
                .map(c -> SearchResult.builder()
                        .chunk(c.getChunk())
                        .score(c.getFusedScore())
                        .sparseScore(c.getSparseScore())
                        .vectorScore(c.getVectorScore())
                        .rerankScore(0.0)
                        .build())
                .toList();
    }
}
