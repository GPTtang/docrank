package com.memo.docrank.eval.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单条查询的评估结果。
 */
@Data
@Builder
public class EvalResult {

    private String queryId;
    private String queryText;

    /** 检索到的 doc_id 列表（按排名顺序） */
    private List<String> retrievedDocIds;

    // ──── 各 K 值指标 ────────────────────────────────────────────

    private double ndcgAt5;
    private double ndcgAt10;
    private double ndcgAt20;

    private double recallAt5;
    private double recallAt10;
    private double recallAt20;

    private double precisionAt5;
    private double precisionAt10;

    /** Reciprocal Rank（单条查询，MRR 是对此取均值） */
    private double reciprocalRank;

    /** Average Precision（单条查询，MAP 是对此取均值） */
    private double averagePrecision;

    /** 该查询的相关文档总数 */
    private int totalRelevant;
}
