package com.memo.docrank.core.search;

import com.memo.docrank.core.model.SearchResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 高级评分流水线（Phase 1）
 *
 * 在 RRF + Reranker 之后再做一轮分数调整：
 *
 *  1. Recency Boost   — 越新的文档分越高，exp(-lambda * daysSince)
 *  2. Importance Boost — chunk.importance (0.0~1.0) 乘权
 *  3. Score Threshold  — 过滤低于 minScore 的结果
 *  4. MMR Diversity    — 同文档多个 chunk 后续命中降权，减少冗余
 */
@Slf4j
public class AdvancedScorer {

    /** 时间衰减系数：每天衰减幅度，默认 0.005（200天后 boost 降到 ~0.37） */
    private final double recencyLambda;

    /** 最低分阈值，低于此分数的结果不返回 */
    private final double minScore;

    /** 是否启用 MMR 多样性降权 */
    private final boolean mmrEnabled;

    /** MMR 同文档降权系数（每多一个同文档 chunk，乘以此系数） */
    private final double mmrPenalty;

    public AdvancedScorer(double recencyLambda, double minScore,
                          boolean mmrEnabled, double mmrPenalty) {
        this.recencyLambda = recencyLambda;
        this.minScore      = minScore;
        this.mmrEnabled    = mmrEnabled;
        this.mmrPenalty    = mmrPenalty;
    }

    /** 默认参数构造 */
    public AdvancedScorer() {
        this(0.005, 0.0, true, 0.85);
    }

    /**
     * 对检索结果做后处理评分调整
     *
     * @param results 原始结果（已按 rerankScore 降序）
     * @param topK    最终返回条数
     * @return 调整后的结果列表
     */
    public List<SearchResult> score(List<SearchResult> results, int topK) {
        if (results.isEmpty()) return results;

        Instant now = Instant.now();
        List<SearchResult> adjusted = new ArrayList<>(results.size());

        for (SearchResult r : results) {
            double base = r.getScore();

            // 1. Recency Boost
            double recencyBoost = 1.0;
            if (r.getChunk().getUpdatedAt() != null) {
                long daysSince = ChronoUnit.DAYS.between(r.getChunk().getUpdatedAt(), now);
                daysSince = Math.max(0, daysSince);
                recencyBoost = Math.exp(-recencyLambda * daysSince);
            }

            // 2. Importance Boost (0.0~1.0)
            double importance = r.getChunk().getImportance();
            // 公式：保留70%基础分，30%来自时效性*重要度调节
            double finalScore = base * (0.7 + 0.3 * recencyBoost * importance);

            adjusted.add(SearchResult.builder()
                    .chunk(r.getChunk())
                    .score(finalScore)
                    .sparseScore(r.getSparseScore())
                    .vectorScore(r.getVectorScore())
                    .rerankScore(r.getRerankScore())
                    .build());
        }

        // 3. Score Threshold
        List<SearchResult> filtered = adjusted.stream()
                .filter(r -> r.getScore() >= minScore)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .toList();

        // 4. MMR Diversity — 同文档后续 chunk 降权
        if (!mmrEnabled) {
            return filtered.stream().limit(topK).toList();
        }

        List<SearchResult> diverse = new ArrayList<>();
        Set<String> seenDocIds = new HashSet<>();

        for (SearchResult r : filtered) {
            if (diverse.size() >= topK) break;
            String docId = r.getChunk().getDocId();
            if (!seenDocIds.contains(docId)) {
                seenDocIds.add(docId);
                diverse.add(r);
            } else {
                // 同文档降权后重新加入（仍可能入选）
                long sameDocCount = diverse.stream()
                        .filter(d -> docId.equals(d.getChunk().getDocId()))
                        .count();
                double penalty = Math.pow(mmrPenalty, sameDocCount);
                SearchResult penalized = SearchResult.builder()
                        .chunk(r.getChunk())
                        .score(r.getScore() * penalty)
                        .sparseScore(r.getSparseScore())
                        .vectorScore(r.getVectorScore())
                        .rerankScore(r.getRerankScore())
                        .build();
                diverse.add(penalized);
            }
        }

        // 降权后重排
        diverse.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.debug("AdvancedScorer: {} → {} 条（threshold={}, mmr={}）",
                results.size(), diverse.size(), minScore, mmrEnabled);
        return diverse;
    }
}
