package com.memo.docrank.langchain4j;

import com.memo.docrank.core.store.InMemoryBackend;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocRankEmbeddingStoreTest {

    private DocRankEmbeddingStore store;
    private InMemoryBackend       backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        backend.createIndex();
        store = DocRankEmbeddingStore.builder()
                .vectorBackend(backend)
                .build();
    }

    private float[] vec(float... values) { return values; }

    @Test
    void add_singleEmbedding_returnsId() {
        String id = store.add(Embedding.from(vec(1f, 0f, 0f)));
        assertNotNull(id);
        assertFalse(id.isBlank());
        assertEquals(1, backend.countChunks());
    }

    @Test
    void addWithId_storesCorrectly() {
        store.add("my-id", Embedding.from(vec(1f, 0f, 0f)));
        assertEquals(1, backend.countChunks());
    }

    @Test
    void addWithTextSegment_preservesText() {
        Metadata meta = new Metadata();
        meta.put("title", "Spring Boot Guide");
        TextSegment segment = TextSegment.from("Spring Boot simplifies development", meta);

        String id = store.add(Embedding.from(vec(1f, 0f, 0f)), segment);
        assertNotNull(id);
        assertEquals(1, backend.countChunks());
    }

    @Test
    void addAll_embeddings_returnsIds() {
        List<Embedding> embeddings = List.of(
                Embedding.from(vec(1f, 0f)),
                Embedding.from(vec(0f, 1f)),
                Embedding.from(vec(1f, 1f))
        );
        List<String> ids = store.addAll(embeddings);
        assertEquals(3, ids.size());
        assertEquals(3, backend.countChunks());
    }

    @Test
    void addAll_withSegments_returnsIds() {
        List<Embedding> embeddings = List.of(
                Embedding.from(vec(1f, 0f)),
                Embedding.from(vec(0f, 1f))
        );
        Metadata m1 = new Metadata();
        Metadata m2 = new Metadata();
        List<TextSegment> segments = List.of(
                TextSegment.from("text one", m1),
                TextSegment.from("text two", m2)
        );
        List<String> ids = store.addAll(embeddings, segments);
        assertEquals(2, ids.size());
        assertEquals(2, backend.countChunks());
    }

    @Test
    void remove_deletesEntry() {
        String id = store.add(Embedding.from(vec(1f, 0f)));
        assertEquals(1, backend.countChunks());
        store.remove(id);
        assertEquals(0, backend.countChunks());
    }

    @Test
    void removeAll_ids_deletesAll() {
        String id1 = store.add(Embedding.from(vec(1f, 0f)));
        String id2 = store.add(Embedding.from(vec(0f, 1f)));
        assertEquals(2, backend.countChunks());
        store.removeAll(List.of(id1, id2));
        assertEquals(0, backend.countChunks());
    }

    @Test
    void search_returnsRelevantResults() {
        Metadata meta = new Metadata();
        store.add(Embedding.from(vec(1f, 0f)), TextSegment.from("aligned document", meta));
        store.add(Embedding.from(vec(0f, 1f)), TextSegment.from("orthogonal document", meta));

        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vec(1f, 0f)))
                .maxResults(5)
                .minScore(0.0)
                .build();
        EmbeddingSearchResult<TextSegment> result = store.search(req);

        assertFalse(result.matches().isEmpty());
        // Top result should be the aligned document
        EmbeddingMatch<TextSegment> top = result.matches().get(0);
        assertTrue(top.score() >= result.matches().get(result.matches().size() - 1).score());
    }

    @Test
    void addAll_sizeMismatch_throwsException() {
        List<Embedding> embeddings = List.of(Embedding.from(vec(1f, 0f)));
        List<TextSegment> segments = List.of(
                TextSegment.from("text one"),
                TextSegment.from("text two")
        );
        assertThrows(IllegalArgumentException.class,
                () -> store.addAll(embeddings, segments));
    }
}
