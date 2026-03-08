package com.memo.docrank.langchain4j;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.store.IndexBackend;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DocRank implementation of LangChain4j {@link EmbeddingStore}.
 *
 * <p>Stores embeddings in the configured {@link IndexBackend} (LanceDB / Qdrant / InMemory)
 * and optionally maintains a {@link BM25Index} for keyword retrieval.</p>
 *
 * <pre>{@code
 * EmbeddingStore<TextSegment> store = DocRankEmbeddingStore.builder()
 *         .vectorBackend(new LanceDBBackend("localhost", 8181, "my_kb", 1024))
 *         .bm25Index(new LuceneBM25Index("/data/lucene"))
 *         .build();
 * }</pre>
 */
@Slf4j
public class DocRankEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final IndexBackend vectorBackend;
    private final BM25Index   bm25Index;     // nullable — BM25 indexing is optional

    private DocRankEmbeddingStore(Builder builder) {
        this.vectorBackend = Objects.requireNonNull(builder.vectorBackend, "vectorBackend is required");
        this.bm25Index     = builder.bm25Index;
    }

    // ----------------------------------------------------------- EmbeddingStore API

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        Chunk chunk = Chunk.builder()
                .chunkId(id).docId(id)
                .chunkText("")
                .updatedAt(Instant.now())
                .build();
        upsert(chunk, embedding.vector());
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        Chunk chunk = segmentToChunk(id, textSegment);
        upsert(chunk, embedding.vector());
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>(embeddings.size());
        List<ChunkWithVectors> batch = new ArrayList<>(embeddings.size());
        for (Embedding emb : embeddings) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            Chunk chunk = Chunk.builder()
                    .chunkId(id).docId(id)
                    .chunkText("")
                    .updatedAt(Instant.now())
                    .build();
            batch.add(toChunkWithVectors(chunk, emb.vector()));
        }
        vectorBackend.upsertChunks(batch);
        return ids;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings.size() != segments.size()) {
            throw new IllegalArgumentException("embeddings and segments must have the same size");
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        List<ChunkWithVectors> batch = new ArrayList<>(embeddings.size());
        List<Chunk> chunks = new ArrayList<>(embeddings.size());

        for (int i = 0; i < embeddings.size(); i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            Chunk chunk = segmentToChunk(id, segments.get(i));
            chunks.add(chunk);
            batch.add(toChunkWithVectors(chunk, embeddings.get(i).vector()));
        }
        vectorBackend.upsertChunks(batch);
        if (bm25Index != null) bm25Index.addChunks(chunks);
        log.debug("DocRankEmbeddingStore: batch upsert {} segments", batch.size());
        return ids;
    }

    @Override
    public void remove(String id) {
        vectorBackend.deleteByDocId(id);
        if (bm25Index != null) bm25Index.deleteByDocId(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ids.forEach(this::remove);
    }

    @Override
    public void removeAll() {
        vectorBackend.deleteIndex();
        if (bm25Index != null) bm25Index.deleteAll();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        float[] queryVec = request.queryEmbedding().vector();
        int topK = request.maxResults();
        double minScore = request.minScore();

        List<RecallCandidate> candidates = vectorBackend.vectorSearch(queryVec, topK, Map.of());

        List<EmbeddingMatch<TextSegment>> matches = candidates.stream()
                .filter(c -> c.getScore() >= minScore)
                .map(c -> new EmbeddingMatch<>(
                        c.getScore(),
                        c.getChunk().getChunkId(),
                        Embedding.from(new float[0]),   // vector not stored back for efficiency
                        chunkToSegment(c.getChunk())))
                .collect(Collectors.toList());

        return new EmbeddingSearchResult<>(matches);
    }

    // ----------------------------------------------------------- Builder

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private IndexBackend vectorBackend;
        private BM25Index    bm25Index;

        public Builder vectorBackend(IndexBackend vectorBackend) {
            this.vectorBackend = vectorBackend; return this;
        }
        public Builder bm25Index(BM25Index bm25Index) {
            this.bm25Index = bm25Index; return this;
        }
        public DocRankEmbeddingStore build() {
            return new DocRankEmbeddingStore(this);
        }
    }

    // ----------------------------------------------------------- private helpers

    private void upsert(Chunk chunk, float[] vector) {
        vectorBackend.upsertChunks(List.of(toChunkWithVectors(chunk, vector)));
        if (bm25Index != null) bm25Index.addChunk(chunk);
    }

    private ChunkWithVectors toChunkWithVectors(Chunk chunk, float[] vector) {
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) vec.add(v);
        return ChunkWithVectors.builder().chunk(chunk).vecChunk(vec).build();
    }

    private Chunk segmentToChunk(String id, TextSegment segment) {
        dev.langchain4j.data.document.Metadata meta = segment.metadata();
        String docId     = getOrDefault(meta, "doc_id",  id);
        String title     = getOrDefault(meta, "title",   "");
        String section   = getOrDefault(meta, "section", "");
        double importance = parseDouble(meta, "importance", 1.0);

        return Chunk.builder()
                .chunkId(id).docId(docId)
                .title(title).sectionPath(section)
                .chunkText(segment.text())
                .importance(importance)
                .updatedAt(Instant.now())
                .build();
    }

    private TextSegment chunkToSegment(Chunk chunk) {
        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        if (chunk.getDocId()      != null) meta.put("doc_id",     chunk.getDocId());
        if (chunk.getTitle()      != null) meta.put("title",      chunk.getTitle());
        if (chunk.getSectionPath()!= null) meta.put("section",    chunk.getSectionPath());
        meta.put("importance", String.valueOf(chunk.getImportance()));
        if (chunk.getUpdatedAt()  != null) meta.put("updated_at", chunk.getUpdatedAt().toString());
        return TextSegment.from(
                chunk.getChunkText() != null ? chunk.getChunkText() : "", meta);
    }

    private String getOrDefault(dev.langchain4j.data.document.Metadata meta,
                                 String key, String def) {
        String v = meta.getString(key);
        return v != null ? v : def;
    }

    private double parseDouble(dev.langchain4j.data.document.Metadata meta,
                                String key, double def) {
        String v = meta.getString(key);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }
}
