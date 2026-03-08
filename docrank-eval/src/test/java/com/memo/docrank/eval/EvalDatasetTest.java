package com.memo.docrank.eval;

import com.memo.docrank.eval.model.EvalDataset;
import com.memo.docrank.eval.model.EvalQuery;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalDatasetTest {

    @Test
    void fromJsonString_parsesCorrectly() throws Exception {
        String json = """
                {
                  "name": "test-bench",
                  "queries": [
                    { "id": "q1", "query": "hello world", "relevant": { "doc-1": 2, "doc-2": 1 } }
                  ]
                }
                """;
        EvalDataset dataset = EvalDataset.fromJsonString(json);
        assertEquals("test-bench", dataset.getName());
        assertEquals(1, dataset.size());
        EvalQuery q = dataset.getQueries().get(0);
        assertEquals("q1", q.getId());
        assertEquals("hello world", q.getQuery());
        assertEquals(2, q.gradeOf("doc-1"));
        assertEquals(1, q.gradeOf("doc-2"));
        assertEquals(0, q.gradeOf("doc-x"));
    }

    @Test
    void fromInputStream_parsesJsonFile() throws Exception {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("sample-dataset.json");
        assertNotNull(is, "sample-dataset.json not found on classpath");
        EvalDataset dataset = EvalDataset.fromInputStream(is);
        assertEquals("docrank-sample-bench", dataset.getName());
        assertEquals(3, dataset.size());
    }

    @Test
    void single_factory_createsDataset() {
        EvalDataset ds = EvalDataset.single("test query", Map.of("doc-1", 1));
        assertEquals(1, ds.size());
        assertEquals("test query", ds.getQueries().get(0).getQuery());
    }

    @Test
    void of_factory_createsDataset() {
        EvalQuery q = EvalQuery.builder().id("q1").query("q").relevant(Map.of("d1", 1)).build();
        EvalDataset ds = EvalDataset.of("my-bench", java.util.List.of(q));
        assertEquals("my-bench", ds.getName());
        assertEquals(1, ds.size());
    }

    @Test
    void evalQuery_relevantCount() {
        EvalQuery q = EvalQuery.builder()
                .id("q1").query("q")
                .relevant(Map.of("d1", 3, "d2", 1, "d3", 0))
                .build();
        // grade=0 is NOT relevant
        assertEquals(2, q.relevantCount());
    }

    @Test
    void evalQuery_gradeOf_unknownDoc_returnsZero() {
        EvalQuery q = EvalQuery.builder()
                .id("q1").query("q")
                .relevant(Map.of("d1", 2))
                .build();
        assertEquals(0, q.gradeOf("unknown-doc"));
    }
}
