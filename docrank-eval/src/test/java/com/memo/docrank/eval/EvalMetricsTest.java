package com.memo.docrank.eval;

import com.memo.docrank.eval.model.EvalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalMetricsTest {

    private EvalQuery binaryQuery;   // 二元相关性
    private EvalQuery gradedQuery;   // 分级相关性

    @BeforeEach
    void setUp() {
        // doc-a, doc-b 相关（grade=1），doc-c 不相关
        binaryQuery = EvalQuery.builder()
                .id("q1").query("test")
                .relevant(Map.of("doc-a", 1, "doc-b", 1))
                .build();

        // 分级：doc-a=3（完全相关），doc-b=2，doc-c=1
        gradedQuery = EvalQuery.builder()
                .id("q2").query("graded")
                .relevant(Map.of("doc-a", 3, "doc-b", 2, "doc-c", 1))
                .build();
    }

    // ──────────────────────────── NDCG ───────────────────────────────────

    @Test
    void ndcg_perfectRanking_returnsOne() {
        // 完美排名：先 a 后 b
        List<String> ranked = List.of("doc-a", "doc-b", "doc-x");
        double score = EvalMetrics.ndcg(ranked, binaryQuery, 5);
        assertEquals(1.0, score, 0.0001);
    }

    @Test
    void ndcg_noRelevantInTopK_returnsZero() {
        List<String> ranked = List.of("doc-x", "doc-y", "doc-z");
        double score = EvalMetrics.ndcg(ranked, binaryQuery, 5);
        assertEquals(0.0, score, 0.0001);
    }

    @Test
    void ndcg_partialRelevance_between0and1() {
        List<String> ranked = List.of("doc-x", "doc-a", "doc-y");
        double score = EvalMetrics.ndcg(ranked, binaryQuery, 5);
        assertTrue(score > 0.0 && score < 1.0,
                "Expected 0 < NDCG < 1, got: " + score);
    }

    @Test
    void ndcg_gradedPerfect_returnsOne() {
        List<String> ranked = List.of("doc-a", "doc-b", "doc-c");
        double score = EvalMetrics.ndcg(ranked, gradedQuery, 5);
        assertEquals(1.0, score, 0.0001);
    }

    @Test
    void ndcg_gradedWorseRanking_lowerThanPerfect() {
        List<String> perfect  = List.of("doc-a", "doc-b", "doc-c");
        List<String> reversed = List.of("doc-c", "doc-b", "doc-a");
        double perfectScore  = EvalMetrics.ndcg(perfect,  gradedQuery, 5);
        double reversedScore = EvalMetrics.ndcg(reversed, gradedQuery, 5);
        assertTrue(perfectScore > reversedScore);
    }

    @Test
    void ndcg_emptyRelevant_returnsZero() {
        EvalQuery empty = EvalQuery.builder().id("q").query("q").relevant(Map.of()).build();
        assertEquals(0.0, EvalMetrics.ndcg(List.of("doc-a"), empty, 5), 0.0001);
    }

    // ──────────────────────────── Recall ─────────────────────────────────

    @Test
    void recall_allRelevantRetrieved_returnsOne() {
        List<String> ranked = List.of("doc-a", "doc-b", "doc-x");
        assertEquals(1.0, EvalMetrics.recall(ranked, binaryQuery, 5), 0.0001);
    }

    @Test
    void recall_noneRelevantRetrieved_returnsZero() {
        List<String> ranked = List.of("doc-x", "doc-y");
        assertEquals(0.0, EvalMetrics.recall(ranked, binaryQuery, 5), 0.0001);
    }

    @Test
    void recall_halfRelevantRetrieved() {
        List<String> ranked = List.of("doc-a", "doc-x");
        assertEquals(0.5, EvalMetrics.recall(ranked, binaryQuery, 5), 0.0001);
    }

    @Test
    void recall_kCutoff_limitsCounting() {
        // doc-b 在位置 3，topK=2 时不被计入
        List<String> ranked = List.of("doc-a", "doc-x", "doc-b");
        assertEquals(0.5, EvalMetrics.recall(ranked, binaryQuery, 2), 0.0001);
    }

    // ──────────────────────────── Precision ──────────────────────────────

    @Test
    void precision_allRelevantInTopK() {
        List<String> ranked = List.of("doc-a", "doc-b");
        assertEquals(1.0, EvalMetrics.precision(ranked, binaryQuery, 2), 0.0001);
    }

    @Test
    void precision_noneRelevant_returnsZero() {
        List<String> ranked = List.of("doc-x", "doc-y");
        assertEquals(0.0, EvalMetrics.precision(ranked, binaryQuery, 2), 0.0001);
    }

    @Test
    void precision_oneOfTwo() {
        List<String> ranked = List.of("doc-a", "doc-x");
        assertEquals(0.5, EvalMetrics.precision(ranked, binaryQuery, 2), 0.0001);
    }

    // ──────────────────────────── Reciprocal Rank ────────────────────────

    @Test
    void rr_firstIsRelevant_returnsOne() {
        List<String> ranked = List.of("doc-a", "doc-x");
        assertEquals(1.0, EvalMetrics.reciprocalRank(ranked, binaryQuery), 0.0001);
    }

    @Test
    void rr_secondIsRelevant_returnsHalf() {
        List<String> ranked = List.of("doc-x", "doc-a");
        assertEquals(0.5, EvalMetrics.reciprocalRank(ranked, binaryQuery), 0.0001);
    }

    @Test
    void rr_noneRelevant_returnsZero() {
        List<String> ranked = List.of("doc-x", "doc-y");
        assertEquals(0.0, EvalMetrics.reciprocalRank(ranked, binaryQuery), 0.0001);
    }

    // ──────────────────────────── Average Precision ──────────────────────

    @Test
    void ap_perfectRanking() {
        // 2 relevant docs at positions 1 and 2
        List<String> ranked = List.of("doc-a", "doc-b", "doc-x");
        // AP = (1/1 + 2/2) / 2 = 1.0
        assertEquals(1.0, EvalMetrics.averagePrecision(ranked, binaryQuery, 10), 0.0001);
    }

    @Test
    void ap_reversedRanking_lowerThanPerfect() {
        List<String> perfect  = List.of("doc-a", "doc-b", "doc-x");
        List<String> reversed = List.of("doc-x", "doc-b", "doc-a");
        double apPerfect  = EvalMetrics.averagePrecision(perfect,  binaryQuery, 10);
        double apReversed = EvalMetrics.averagePrecision(reversed, binaryQuery, 10);
        assertTrue(apPerfect > apReversed);
    }

    @Test
    void ap_noRelevant_returnsZero() {
        EvalQuery empty = EvalQuery.builder().id("q").query("q").relevant(Map.of()).build();
        assertEquals(0.0, EvalMetrics.averagePrecision(List.of("doc-a"), empty, 10), 0.0001);
    }

    // ──────────────────────────── MRR / MAP helpers ──────────────────────

    @Test
    void mrr_meanOfReciprocalRanks() {
        double mrr = EvalMetrics.mrr(List.of(1.0, 0.5, 0.0));
        assertEquals(0.5, mrr, 0.0001);
    }

    @Test
    void map_meanOfAveragePrecisions() {
        double map = EvalMetrics.map(List.of(1.0, 0.5));
        assertEquals(0.75, map, 0.0001);
    }

    @Test
    void mean_emptyList_returnsZero() {
        assertEquals(0.0, EvalMetrics.mean(List.of()), 0.0001);
    }
}
