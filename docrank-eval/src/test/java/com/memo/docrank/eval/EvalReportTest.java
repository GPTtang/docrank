package com.memo.docrank.eval;

import com.memo.docrank.eval.model.EvalReport;
import com.memo.docrank.eval.model.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalReportTest {

    private EvalReport sampleReport() {
        return EvalReport.builder()
                .datasetName("test-bench")
                .totalQueries(2)
                .ndcgAt5(0.85).ndcgAt10(0.78).ndcgAt20(0.71)
                .recallAt5(0.60).recallAt10(0.80).recallAt20(0.90)
                .precisionAt5(0.40).precisionAt10(0.30)
                .mrr(0.75)
                .mapAt10(0.72)
                .perQueryResults(List.of(
                        EvalResult.builder().queryId("q1").queryText("query 1")
                                .ndcgAt10(0.9).recallAt10(0.8).reciprocalRank(1.0)
                                .averagePrecision(0.9).totalRelevant(2)
                                .retrievedDocIds(List.of()).build(),
                        EvalResult.builder().queryId("q2").queryText("query 2")
                                .ndcgAt10(0.66).recallAt10(0.8).reciprocalRank(0.5)
                                .averagePrecision(0.54).totalRelevant(1)
                                .retrievedDocIds(List.of()).build()
                ))
                .build();
    }

    @Test
    void toText_containsAllMetrics() {
        String text = sampleReport().toText();
        assertTrue(text.contains("NDCG"));
        assertTrue(text.contains("Recall"));
        assertTrue(text.contains("Precision"));
        assertTrue(text.contains("MRR"));
        assertTrue(text.contains("MAP"));
        assertTrue(text.contains("test-bench"));
    }

    @Test
    void toCsv_hasHeaderAndDataRow() {
        String csv = sampleReport().toCsv();
        String[] lines = csv.strip().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("ndcg@5"));
        assertTrue(lines[1].contains("test-bench"));
    }

    @Test
    void print_doesNotThrow() {
        assertDoesNotThrow(() -> sampleReport().print());
    }
}
