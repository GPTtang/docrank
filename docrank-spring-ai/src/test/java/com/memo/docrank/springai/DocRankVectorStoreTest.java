package com.memo.docrank.springai;

import com.memo.docrank.core.store.InMemoryBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DocRankVectorStoreTest {

    private DocRankVectorStore store;
    private InMemoryBackend    backend;

    /** Stub EmbeddingModel: encodes by hashing text to a 3-dim float[] */
    static class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> list = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                list.add(new Embedding(toDoubles(encode(texts.get(i))), i));
            }
            return new EmbeddingResponse(list);
        }

        @Override
        public List<Double> embed(String text) {
            return toDoubles(encode(text));
        }

        @Override
        public int dimensions() { return 3; }

        private float[] encode(String text) {
            int h = text.hashCode();
            return new float[]{(h & 0xFF) / 255f, ((h >> 8) & 0xFF) / 255f, ((h >> 16) & 0xFF) / 255f};
        }

        private List<Double> toDoubles(float[] arr) {
            List<Double> list = new ArrayList<>(arr.length);
            for (float v : arr) list.add((double) v);
            return list;
        }
    }

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        backend.createIndex();
        store = DocRankVectorStore.builder()
                .embeddingModel(new StubEmbeddingModel())
                .vectorBackend(backend)
                .defaultScope("test-scope")
                .build();
    }

    @Test
    void add_storesDocuments() {
        store.add(List.of(
                new Document("Spring Boot tutorial", Map.of("title", "Spring")),
                new Document("Kubernetes guide",     Map.of("title", "K8s"))
        ));
        assertEquals(2, backend.countChunks());
    }

    @Test
    void add_emptyList_noOp() {
        store.add(List.of());
        assertEquals(0, backend.countChunks());
    }

    @Test
    void delete_removesDocuments() {
        Document doc = new Document("some content", Map.of());
        store.add(List.of(doc));
        assertEquals(1, backend.countChunks());

        Optional<Boolean> result = store.delete(List.of(doc.getId()));
        assertTrue(result.orElse(false));
        assertEquals(0, backend.countChunks());
    }

    @Test
    void similaritySearch_returnsResults() {
        store.add(List.of(
                new Document("machine learning algorithms", Map.of()),
                new Document("cooking recipes for dinner",  Map.of())
        ));

        SearchRequest request = SearchRequest.builder()
                .query("machine learning")
                .topK(5)
                .similarityThreshold(0.0)
                .build();

        List<Document> results = store.similaritySearch(request);
        assertFalse(results.isEmpty());
    }

    @Test
    void similaritySearch_respectsTopK() {
        for (int i = 0; i < 5; i++) {
            store.add(List.of(new Document("document number " + i, Map.of())));
        }
        SearchRequest request = SearchRequest.builder()
                .query("document")
                .topK(2)
                .similarityThreshold(0.0)
                .build();
        List<Document> results = store.similaritySearch(request);
        assertTrue(results.size() <= 2);
    }

    @Test
    void add_preservesMetadata() {
        Document doc = new Document("Spring AI vector store",
                Map.of("title", "Spring AI Guide", "scope", "docs"));
        store.add(List.of(doc));

        SearchRequest request = SearchRequest.builder()
                .query("Spring AI")
                .topK(5)
                .similarityThreshold(0.0)
                .build();
        List<Document> results = store.similaritySearch(request);
        assertFalse(results.isEmpty());
        // The result document should have a title in metadata
        assertNotNull(results.get(0).getMetadata().get("title"));
    }

    @Test
    void builderRequiresEmbeddingModel() {
        assertThrows(NullPointerException.class, () ->
                DocRankVectorStore.builder()
                        .vectorBackend(backend)
                        .build());
    }

    @Test
    void builderRequiresVectorBackend() {
        assertThrows(NullPointerException.class, () ->
                DocRankVectorStore.builder()
                        .embeddingModel(new StubEmbeddingModel())
                        .build());
    }
}
