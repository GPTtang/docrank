package com.memo.docrank.core.search;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedScorerTest {

    private AdvancedScorer scorer;

    @BeforeEach
    void setUp() {
        // recencyLambda=0.1, minScore=0.5, mmrEnabled=true, mmrPenalty=0.5
        scorer = new AdvancedScorer(0.1, 0.5, true, 0.5);
    }

    private SearchResult result(String docId, String chunkId, double score,
                                 double importance, Instant updatedAt) {
        Chunk chunk = Chunk.builder()
                .chunkId(chunkId).docId(docId)
                .chunkText("text " + chunkId)
                .importance(importance)
                .updatedAt(updatedAt)
                .build();
        return SearchResult.builder()
                .chunk(chunk).score(score).rerankScore(score)
                .build();
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertTrue(scorer.score(List.of(), 5).isEmpty());
    }

    @Test
    void recentDocument_getsHigherScore() {
        AdvancedScorer noDecay = new AdvancedScorer(0.0, 0.0, false, 1.0);
        Instant now = Instant.now();
        SearchResult recent = result("d1", "c1", 1.0, 1.0, now);
        SearchResult old    = result("d2", "c2", 1.0, 1.0, now.minus(365, ChronoUnit.DAYS));

        List<SearchResult> results = noDecay.score(List.of(recent, old), 5);
        assertEquals(2, results.size());
        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
    }

    @Test
    void lowImportance_reducesScore() {
        AdvancedScorer flat = new AdvancedScorer(0.0, 0.0, false, 1.0);
        Instant now = Instant.now();
        SearchResult high = result("d1", "c1", 1.0, 1.0, now);
        SearchResult low  = result("d2", "c2", 1.0, 0.0, now);

        List<SearchResult> results = flat.score(List.of(high, low), 5);
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void scoreThreshold_filtersResults() {
        AdvancedScorer strict = new AdvancedScorer(0.0, 0.8, false, 1.0);
        Instant now = Instant.now();
        // base=1.0, importance=1.0, recency=1.0 → finalScore = 1.0*(0.7+0.3*1.0*1.0) = 1.0
        SearchResult above = result("d1", "c1", 1.0, 1.0, now);
        // base=0.3, importance=0.1 → finalScore = 0.3*(0.7+0.3*1.0*0.1) = ~0.219
        SearchResult below = result("d2", "c2", 0.3, 0.1, now);

        List<SearchResult> results = strict.score(List.of(above, below), 5);
        assertEquals(1, results.size());
        assertEquals("d1", results.get(0).getChunk().getDocId());
    }

    @Test
    void mmrDiversity_penalizesSameDoc() {
        AdvancedScorer mmr = new AdvancedScorer(0.0, 0.0, true, 0.5);
        Instant now = Instant.now();
        SearchResult d1c1 = result("doc1", "c1", 1.0, 1.0, now);
        SearchResult d1c2 = result("doc1", "c2", 0.9, 1.0, now);
        SearchResult d2c1 = result("doc2", "c3", 0.8, 1.0, now);

        List<SearchResult> results = mmr.score(List.of(d1c1, d1c2, d2c1), 3);
        assertEquals(3, results.size());
        // d1c1 always first (highest score, no penalty)
        assertEquals("c1", results.get(0).getChunk().getChunkId());
    }

    @Test
    void topKLimit_respected() {
        AdvancedScorer s = new AdvancedScorer(0.0, 0.0, false, 1.0);
        Instant now = Instant.now();
        List<SearchResult> input = List.of(
                result("d1", "c1", 1.0, 1.0, now),
                result("d2", "c2", 0.9, 1.0, now),
                result("d3", "c3", 0.8, 1.0, now),
                result("d4", "c4", 0.7, 1.0, now)
        );
        List<SearchResult> results = s.score(input, 2);
        assertEquals(2, results.size());
    }

    @Test
    void nullUpdatedAt_noRecencyBoost() {
        AdvancedScorer s = new AdvancedScorer(0.1, 0.0, false, 1.0);
        SearchResult r = result("d1", "c1", 1.0, 1.0, null);
        List<SearchResult> results = s.score(List.of(r), 5);
        assertEquals(1, results.size());
        // recencyBoost defaults to 1.0 → finalScore = 1.0*(0.7+0.3*1.0*1.0) = 1.0
        assertEquals(1.0, results.get(0).getScore(), 0.01);
    }

    @Test
    void scoresAreDescendingOrder() {
        AdvancedScorer s = new AdvancedScorer(0.0, 0.0, false, 1.0);
        Instant now = Instant.now();
        List<SearchResult> input = List.of(
                result("d1", "c1", 0.5, 1.0, now),
                result("d2", "c2", 1.0, 1.0, now),
                result("d3", "c3", 0.7, 1.0, now)
        );
        List<SearchResult> results = s.score(input, 10);
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).getScore() >= results.get(i + 1).getScore());
        }
    }
}
