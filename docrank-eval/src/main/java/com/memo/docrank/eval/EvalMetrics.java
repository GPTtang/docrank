package com.memo.docrank.eval;

import com.memo.docrank.eval.model.EvalQuery;

import java.util.List;

/**
 * 检索评估指标静态工具类。
 *
 * <p>所有方法接受：
 * <ul>
 *   <li>{@code rankedDocIds} — 检索结果的 doc_id 列表（已按相关性分数降序排列）
 *   <li>{@code query}        — 包含 ground-truth 相关性标注的查询对象
 *   <li>{@code k}            — 截断深度
 * </ul>
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    // ──────────────────────────────────────── NDCG@K ─────────────────────

    /**
     * NDCG@K — Normalized Discounted Cumulative Gain.
     *
     * <p>使用 graded relevance（0‥3），支持二元相关性（grade 为 0/1）。
     */
    public static double ndcg(List<String> rankedDocIds, EvalQuery query, int k) {
        double dcg  = dcg(rankedDocIds, query, k);
        double idcg = idealDcg(query, k);
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /** DCG@K */
    public static double dcg(List<String> rankedDocIds, EvalQuery query, int k) {
        double score = 0.0;
        int    limit = Math.min(k, rankedDocIds.size());
        for (int i = 0; i < limit; i++) {
            int grade = query.gradeOf(rankedDocIds.get(i));
            if (grade > 0) {
                score += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
            }
        }
        return score;
    }

    /** Ideal DCG@K（对 ground-truth 按相关性降序排列后计算） */
    public static double idealDcg(EvalQuery query, int k) {
        if (query.getRelevant() == null || query.getRelevant().isEmpty()) return 0.0;
        List<Integer> grades = query.getRelevant().values().stream()
                .filter(g -> g > 0)
                .sorted((a, b) -> b - a)   // 降序
                .limit(k)
                .toList();
        double score = 0.0;
        for (int i = 0; i < grades.size(); i++) {
            score += (Math.pow(2, grades.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return score;
    }

    // ──────────────────────────────────────── Recall@K ───────────────────

    /**
     * Recall@K = |retrieved_relevant ∩ relevant| / |relevant|
     */
    public static double recall(List<String> rankedDocIds, EvalQuery query, int k) {
        int totalRelevant = query.relevantCount();
        if (totalRelevant == 0) return 1.0;   // 无相关文档时视为完美召回
        long hits = rankedDocIds.stream()
                .limit(k)
                .filter(id -> query.gradeOf(id) > 0)
                .count();
        return (double) hits / totalRelevant;
    }

    // ──────────────────────────────────────── Precision@K ────────────────

    /**
     * Precision@K = |retrieved_relevant| / K
     */
    public static double precision(List<String> rankedDocIds, EvalQuery query, int k) {
        if (k == 0) return 0.0;
        long hits = rankedDocIds.stream()
                .limit(k)
                .filter(id -> query.gradeOf(id) > 0)
                .count();
        return (double) hits / k;
    }

    // ──────────────────────────────────────── Reciprocal Rank ────────────

    /**
     * Reciprocal Rank = 1 / rank_of_first_relevant_doc.
     * 若前 K 名中无相关文档，返回 0。
     */
    public static double reciprocalRank(List<String> rankedDocIds, EvalQuery query) {
        for (int i = 0; i < rankedDocIds.size(); i++) {
            if (query.gradeOf(rankedDocIds.get(i)) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    // ──────────────────────────────────────── Average Precision ──────────

    /**
     * Average Precision@K
     *
     * <p>AP = (1 / |relevant|) * Σ_{k: doc_k is relevant} Precision@k
     */
    public static double averagePrecision(List<String> rankedDocIds, EvalQuery query, int k) {
        int totalRelevant = query.relevantCount();
        if (totalRelevant == 0) return 0.0;

        double apSum   = 0.0;
        int    hits    = 0;
        int    limit   = Math.min(k, rankedDocIds.size());

        for (int i = 0; i < limit; i++) {
            if (query.gradeOf(rankedDocIds.get(i)) > 0) {
                hits++;
                apSum += (double) hits / (i + 1);
            }
        }
        return apSum / totalRelevant;
    }

    // ──────────────────────────────────────── MRR / MAP helpers ──────────

    /** MRR over a list of reciprocal rank values */
    public static double mrr(List<Double> reciprocalRanks) {
        return reciprocalRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** MAP over a list of average precision values */
    public static double map(List<Double> averagePrecisions) {
        return averagePrecisions.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** Mean of a list of doubles */
    public static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
