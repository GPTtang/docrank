package com.memo.docrank.core.chunking;

import com.memo.docrank.core.analyzer.LanguageDetector;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    static ChunkingService service;

    @BeforeAll
    static void setup() {
        service = new ChunkingService(new LanguageDetector(), 100, 20);
    }

    @Test
    void emptyTextReturnsEmptyList() {
        assertTrue(service.chunk("doc1", "title", "").isEmpty());
        assertTrue(service.chunk("doc1", "title", null).isEmpty());
        assertTrue(service.chunk("doc1", "title", "   ").isEmpty());
    }

    @Test
    void shortTextProducesSingleChunk() {
        String text = "This is a short sentence.";
        List<Chunk> chunks = service.chunk("doc1", "My Doc", text);
        assertEquals(1, chunks.size());
        assertEquals("doc1", chunks.get(0).getDocId());
        assertEquals("My Doc", chunks.get(0).getTitle());
        assertEquals(0, chunks.get(0).getChunkIndex());
    }

    @Test
    void chunkIndexIsSequential() {
        // 50 Chinese sentences, each 10 chars → 500 chars total, chunkSize=100 → ~5 chunks
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("这是第").append(i).append("个中文句子示例内容。");
        }
        List<Chunk> chunks = service.chunk("docX", "Title", sb.toString());
        assertTrue(chunks.size() > 1, "应该产生多个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex());
        }
    }

    @Test
    void allChunksHaveSameDocId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("人工智能技术发展非常迅速，带来了很多应用场景。");
        List<Chunk> chunks = service.chunk("myDocId", "AI", sb.toString());
        assertTrue(chunks.size() >= 1);
        chunks.forEach(c -> assertEquals("myDocId", c.getDocId()));
    }

    @Test
    void chunkHasLanguage() {
        List<Chunk> chunks = service.chunk("d1", "t", "这是中文文本内容示例。");
        assertEquals(1, chunks.size());
        assertEquals(Language.CHINESE, chunks.get(0).getLanguage());
    }

    @Test
    void chunkTextIsNotEmpty() {
        List<Chunk> chunks = service.chunk("d1", "t", "Hello world. This is a test.");
        assertFalse(chunks.isEmpty());
        chunks.forEach(c -> assertFalse(c.getChunkText().isBlank()));
    }

    @Test
    void chunkIdsAreUnique() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("人工智能技术的发展日新月异，推动了社会各领域的深刻变革。");
        List<Chunk> chunks = service.chunk("doc1", "t", sb.toString());
        long distinct = chunks.stream().map(Chunk::getChunkId).distinct().count();
        assertEquals(chunks.size(), distinct);
    }

    @Test
    void updatedAtIsSet() {
        List<Chunk> chunks = service.chunk("d1", "t", "Test sentence here.");
        chunks.forEach(c -> assertNotNull(c.getUpdatedAt()));
    }
}
