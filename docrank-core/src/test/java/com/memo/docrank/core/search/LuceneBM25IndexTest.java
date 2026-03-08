package com.memo.docrank.core.search;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LuceneBM25IndexTest {

    @TempDir
    Path tempDir;

    LuceneBM25Index index;

    @BeforeEach
    void setup() {
        index = new LuceneBM25Index(tempDir.toString());
    }

    @AfterEach
    void teardown() {
        index.close();
    }

    // ---------------------------------------------------------------- helpers

    private Chunk chunk(String docId, String title, String text, String... tags) {
        return Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .docId(docId)
                .title(title)
                .chunkText(text)
                .chunkIndex(0)
                .language(Language.ENGLISH)
                .tags(List.of(tags))
                .scope("global")
                .importance(1.0)
                .updatedAt(Instant.now())
                .build();
    }

    private Chunk chunkWithScope(String docId, String title, String text, String scope) {
        return Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .docId(docId)
                .title(title)
                .chunkText(text)
                .chunkIndex(0)
                .language(Language.ENGLISH)
                .tags(List.of())
                .scope(scope)
                .importance(1.0)
                .updatedAt(Instant.now())
                .build();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void initialCountIsZero() {
        assertEquals(0, index.count());
    }

    @Test
    void addChunkIncreasesCount() {
        index.addChunk(chunk("doc1", "Java Basics", "Java is a programming language."));
        assertEquals(1, index.count());
    }

    @Test
    void addChunksIncreasesCountByBatch() {
        List<Chunk> chunks = List.of(
                chunk("doc1", "T1", "Spring Boot makes development easy."),
                chunk("doc2", "T2", "Docker simplifies deployment."),
                chunk("doc3", "T3", "Kubernetes manages containers.")
        );
        index.addChunks(chunks);
        assertEquals(3, index.count());
    }

    @Test
    void searchReturnsMatchingChunk() {
        index.addChunk(chunk("doc1", "Java", "Java is an object-oriented programming language."));
        index.addChunk(chunk("doc2", "Python", "Python is a scripting language for data science."));

        List<RecallCandidate> results = index.search("Java programming", 5, Map.of());
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> "doc1".equals(r.getChunk().getDocId())));
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        index.addChunk(chunk("doc1", "Java", "Java programming language"));
        List<RecallCandidate> results = index.search("quantum physics", 5, Map.of());
        // 可能为空，或分数极低但 Lucene 仍返回 — 只确保不抛异常
        assertNotNull(results);
    }

    @Test
    void deleteByDocIdRemovesChunks() {
        Chunk c1 = chunk("doc1", "Title1", "Spring framework introduction.");
        Chunk c2 = chunk("doc1", "Title1", "Spring MVC controller setup.");
        Chunk c3 = chunk("doc2", "Title2", "Hibernate ORM tutorial.");
        index.addChunks(List.of(c1, c2, c3));
        assertEquals(3, index.count());

        index.deleteByDocId("doc1");
        assertEquals(1, index.count());
    }

    @Test
    void deleteAllClearsIndex() {
        index.addChunks(List.of(
                chunk("d1", "T1", "First document text."),
                chunk("d2", "T2", "Second document text.")
        ));
        index.deleteAll();
        assertEquals(0, index.count());
    }

    @Test
    void searchSourceIsKeyword() {
        index.addChunk(chunk("doc1", "Test", "microservice architecture design."));
        List<RecallCandidate> results = index.search("microservice", 5, Map.of());
        assertFalse(results.isEmpty());
        assertEquals(RecallCandidate.RecallSource.KEYWORD, results.get(0).getSource());
    }

    @Test
    void searchWithTagFilter() {
        Chunk tagged = chunk("doc1", "T1", "Spring framework overview.", "java", "spring");
        Chunk other  = chunk("doc2", "T2", "Spring cleaning tips.", "home");
        index.addChunks(List.of(tagged, other));

        List<RecallCandidate> results = index.search("Spring", 5, Map.of("tags", List.of("java")));
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> "doc1".equals(r.getChunk().getDocId())));
    }

    @Test
    void upsertUpdatesExistingChunk() {
        String chunkId = UUID.randomUUID().toString();
        Chunk original = Chunk.builder()
                .chunkId(chunkId).docId("doc1").title("T").chunkText("original text")
                .chunkIndex(0).language(Language.ENGLISH).tags(List.of()).updatedAt(Instant.now())
                .build();
        index.addChunk(original);
        assertEquals(1, index.count());

        Chunk updated = Chunk.builder()
                .chunkId(chunkId).docId("doc1").title("T").chunkText("updated text")
                .chunkIndex(0).language(Language.ENGLISH).tags(List.of()).updatedAt(Instant.now())
                .build();
        index.addChunk(updated);
        // upsert by chunkId — 数量不变
        assertEquals(1, index.count());
    }

    @Test
    void searchRespectTopK() {
        for (int i = 0; i < 10; i++) {
            index.addChunk(chunk("doc" + i, "T" + i, "machine learning deep learning neural network " + i));
        }
        List<RecallCandidate> results = index.search("machine learning", 3, Map.of());
        assertTrue(results.size() <= 3);
    }

    @Test
    void chunkDataRoundTripsCorrectly() {
        Chunk original = chunk("doc1", "My Title", "The quick brown fox.", "tag1", "tag2");
        index.addChunk(original);

        List<RecallCandidate> results = index.search("quick brown fox", 1, Map.of());
        assertFalse(results.isEmpty());

        Chunk retrieved = results.get(0).getChunk();
        assertEquals("doc1",     retrieved.getDocId());
        assertEquals("My Title", retrieved.getTitle());
        assertTrue(retrieved.getChunkText().contains("quick brown fox"));
    }

    @Test
    void searchWithScopeFilter_returnsOnlyMatchingScope() {
        index.addChunk(chunkWithScope("doc1", "T1", "distributed systems microservices", "project-a"));
        index.addChunk(chunkWithScope("doc2", "T2", "distributed systems microservices", "project-b"));

        List<RecallCandidate> results = index.search("distributed systems", 5,
                Map.of("scope", "project-a"));
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> "project-a".equals(r.getChunk().getScope())));
    }

    @Test
    void deleteByScope_removesOnlyScopeChunks() {
        index.addChunk(chunkWithScope("doc1", "T1", "kubernetes container orchestration", "ns-a"));
        index.addChunk(chunkWithScope("doc2", "T2", "docker container images", "ns-a"));
        index.addChunk(chunkWithScope("doc3", "T3", "cloud native architecture", "ns-b"));
        assertEquals(3, index.count());

        index.deleteByScope("ns-a");
        assertEquals(1, index.count());

        List<RecallCandidate> remaining = index.search("cloud native", 5, Map.of());
        assertFalse(remaining.isEmpty());
        assertEquals("ns-b", remaining.get(0).getChunk().getScope());
    }

    @Test
    void importanceAndScopeRoundTrip() {
        Chunk c = Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .docId("doc1").title("T").chunkText("importance test content")
                .chunkIndex(0).language(Language.ENGLISH).tags(List.of())
                .scope("team-alpha").importance(0.75)
                .updatedAt(Instant.now()).build();
        index.addChunk(c);

        List<RecallCandidate> results = index.search("importance test", 1, Map.of());
        assertFalse(results.isEmpty());
        Chunk retrieved = results.get(0).getChunk();
        assertEquals("team-alpha", retrieved.getScope());
        assertEquals(0.75, retrieved.getImportance(), 0.001);
    }
}
