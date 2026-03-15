package com.memo.docrank.core.store;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBackendTest {

    private InMemoryBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        backend.createIndex();
    }

    private ChunkWithVectors makeChunk(String chunkId, String docId, String text, float[] vec, String... tags) {
        Chunk chunk = Chunk.builder()
                .chunkId(chunkId)
                .docId(docId)
                .chunkText(text)
                .language(Language.ENGLISH)
                .tags(tags != null ? List.of(tags) : List.of())
                .build();
        List<Float> vecList = new ArrayList<>();
        for (float v : vec) {
            vecList.add(v);
        }
        return ChunkWithVectors.builder().chunk(chunk).vecChunk(vecList).build();
    }

    @Test
    void upsertAndCount() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "d1", "hello world", new float[]{1f, 0f}),
                makeChunk("c2", "d1", "spring boot", new float[]{0f, 1f})
        ));
        assertEquals(2, backend.countChunks());
    }

    @Test
    void upsert_overwritesSameChunkId() {
        backend.upsertChunks(List.of(makeChunk("c1", "d1", "v1", new float[]{1f, 0f})));
        backend.upsertChunks(List.of(makeChunk("c1", "d1", "v2", new float[]{1f, 0f})));
        assertEquals(1, backend.countChunks());
    }

    @Test
    void deleteByDocId_removesAllChunks() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "d1", "text1", new float[]{1f, 0f}),
                makeChunk("c2", "d1", "text2", new float[]{0f, 1f}),
                makeChunk("c3", "d2", "text3", new float[]{1f, 1f})
        ));
        backend.deleteByDocId("d1");
        assertEquals(1, backend.countChunks());
    }

    @Test
    void keywordSearch_findsMatchingText() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "d1", "spring boot tutorial", new float[]{1f, 0f}),
                makeChunk("c2", "d2", "java programming guide", new float[]{0f, 1f})
        ));
        List<RecallCandidate> results = backend.keywordSearch("spring", 10, Map.of());
        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).getChunk().getChunkId());
    }

    @Test
    void keywordSearch_appliesDocIdFilter() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "doc-a", "spring boot tutorial", new float[]{1f, 0f}),
                makeChunk("c2", "doc-b", "spring cloud tutorial", new float[]{0.9f, 0.1f})
        ));

        List<RecallCandidate> results = backend.keywordSearch("spring", 10, Map.of("doc_id", "doc-a"));

        assertEquals(1, results.size());
        assertEquals("doc-a", results.get(0).getChunk().getDocId());
    }

    @Test
    void vectorSearch_ranksByCosineSimilarity() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "d1", "text1", new float[]{1f, 0f}),
                makeChunk("c2", "d2", "text2", new float[]{0f, 1f})
        ));

        List<RecallCandidate> results = backend.vectorSearch(new float[]{1f, 0f}, 10, Map.of());

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).getChunk().getChunkId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void vectorSearch_appliesTagsFilter() {
        backend.upsertChunks(List.of(
                makeChunk("c1", "d1", "java text", new float[]{1f, 0f}, "java", "spring"),
                makeChunk("c2", "d2", "ops text", new float[]{1f, 0f}, "ops")
        ));

        List<RecallCandidate> results = backend.vectorSearch(new float[]{1f, 0f}, 10, Map.of("tags", List.of("java")));

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).getChunk().getChunkId());
    }

    @Test
    void isHealthy_returnsTrue() {
        assertTrue(backend.isHealthy());
    }

    @Test
    void deleteIndex_clearsAll() {
        backend.upsertChunks(List.of(makeChunk("c1", "d1", "text", new float[]{1f})));
        backend.deleteIndex();
        assertEquals(0, backend.countChunks());
    }
}
