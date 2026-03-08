package com.memo.docrank.springai;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.RecallCandidate;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.store.IndexBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DocRank implementation of Spring AI {@link VectorStore}.
 *
 * <p>Stores Spring AI {@link Document}s in DocRank's vector + BM25 backends,
 * supporting full hybrid retrieval (BM25 + vector + RRF + rerank).</p>
 *
 * <pre>{@code
 * @Bean
 * VectorStore vectorStore(EmbeddingModel embeddingModel,
 *                          IndexBackend vectorBackend,
 *                          BM25Index bm25Index) {
 *     return DocRankVectorStore.builder()
 *             .embeddingModel(embeddingModel)
 *             .vectorBackend(vectorBackend)
 *             .bm25Index(bm25Index)
 *             .defaultScope("my-project")
 *             .build();
 * }
 * }</pre>
 */
@Slf4j
public class DocRankVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private final IndexBackend   vectorBackend;
    private final BM25Index      bm25Index;
    private final String         defaultScope;

    private DocRankVectorStore(Builder builder) {
        this.embeddingModel = Objects.requireNonNull(builder.embeddingModel, "embeddingModel is required");
        this.vectorBackend  = Objects.requireNonNull(builder.vectorBackend,  "vectorBackend is required");
        this.bm25Index      = builder.bm25Index;
        this.defaultScope   = builder.defaultScope != null ? builder.defaultScope : "global";
    }

    // ----------------------------------------------------------- VectorStore API

    @Override
    public void add(List<Document> documents) {
        if (documents.isEmpty()) return;

        // 批量向量化
        List<float[]> vectors = documents.stream()
                .map(d -> toFloatArray(embeddingModel.embed(d.getFormattedContent())))
                .collect(Collectors.toList());

        List<ChunkWithVectors> batch = new ArrayList<>(documents.size());
        List<Chunk> chunks = new ArrayList<>(documents.size());

        for (int i = 0; i < documents.size(); i++) {
            Chunk chunk = documentToChunk(documents.get(i));
            chunks.add(chunk);
            batch.add(toChunkWithVectors(chunk, vectors.get(i)));
        }

        vectorBackend.upsertChunks(batch);
        if (bm25Index != null) bm25Index.addChunks(chunks);
        log.debug("DocRankVectorStore: added {} documents", documents.size());
    }

    @Override
    public Optional<Boolean> delete(List<String> idList) {
        try {
            for (String id : idList) {
                vectorBackend.deleteByDocId(id);
                if (bm25Index != null) bm25Index.deleteByDocId(id);
            }
            return Optional.of(true);
        } catch (Exception e) {
            log.error("DocRankVectorStore: delete failed", e);
            return Optional.of(false);
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        float[] queryVec = toFloatArray(embeddingModel.embed(request.getQuery()));
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();

        Map<String, Object> filters = new LinkedHashMap<>();
        // Spring AI filter expression → scope mapping (simplified)
        if (request.getFilterExpression() != null) {
            String filterStr = request.getFilterExpression().toString();
            if (filterStr.contains("scope")) {
                // parse "scope == 'xxx'" style
                String scope = parseSimpleFilter(filterStr, "scope");
                if (scope != null) filters.put("scope", scope);
            }
        }
        if (filters.isEmpty()) filters.put("scope", defaultScope);

        List<RecallCandidate> candidates = vectorBackend.vectorSearch(queryVec, topK, filters);

        return candidates.stream()
                .filter(c -> c.getScore() >= threshold)
                .map(c -> chunkToDocument(c.getChunk(), c.getScore()))
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------- Builder

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private EmbeddingModel embeddingModel;
        private IndexBackend   vectorBackend;
        private BM25Index      bm25Index;
        private String         defaultScope;

        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel; return this;
        }
        public Builder vectorBackend(IndexBackend vectorBackend) {
            this.vectorBackend = vectorBackend; return this;
        }
        public Builder bm25Index(BM25Index bm25Index) {
            this.bm25Index = bm25Index; return this;
        }
        public Builder defaultScope(String scope) {
            this.defaultScope = scope; return this;
        }
        public DocRankVectorStore build() {
            return new DocRankVectorStore(this);
        }
    }

    // ----------------------------------------------------------- private helpers

    private Chunk documentToChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String id        = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
        String docId     = getStr(meta, "doc_id",  id);
        String title     = getStr(meta, "title",   "");
        String section   = getStr(meta, "section", "");
        String scope     = getStr(meta, "scope",   defaultScope);
        double importance = getDouble(meta, "importance", 1.0);

        return Chunk.builder()
                .chunkId(id).docId(docId)
                .title(title).sectionPath(section)
                .chunkText(doc.getFormattedContent())
                .scope(scope).importance(importance)
                .updatedAt(Instant.now())
                .build();
    }

    private Document chunkToDocument(Chunk chunk, double score) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("doc_id",     chunk.getDocId()      != null ? chunk.getDocId()      : "");
        meta.put("title",      chunk.getTitle()       != null ? chunk.getTitle()      : "");
        meta.put("section",    chunk.getSectionPath() != null ? chunk.getSectionPath(): "");
        meta.put("scope",      chunk.getScope()       != null ? chunk.getScope()      : "");
        meta.put("importance", chunk.getImportance());
        meta.put("score",      score);
        if (chunk.getUpdatedAt() != null) meta.put("updated_at", chunk.getUpdatedAt().toString());

        Document doc = new Document(chunk.getChunkText() != null ? chunk.getChunkText() : "", meta);
        // Set the same id so delete() can target this doc later
        return doc;
    }

    private ChunkWithVectors toChunkWithVectors(Chunk chunk, float[] vector) {
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) vec.add(v);
        return ChunkWithVectors.builder().chunk(chunk).vecChunk(vec).build();
    }

    private float[] toFloatArray(List<Double> doubles) {
        float[] arr = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) arr[i] = doubles.get(i).floatValue();
        return arr;
    }

    private String getStr(Map<String, Object> meta, String key, String def) {
        Object v = meta.get(key);
        return v != null ? v.toString() : def;
    }

    private double getDouble(Map<String, Object> meta, String key, double def) {
        Object v = meta.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    /** 简单解析 "scope == 'value'" 形式的过滤表达式 */
    private String parseSimpleFilter(String expr, String key) {
        // e.g. "scope == 'project-x'" or "scope = 'project-x'"
        int idx = expr.indexOf(key);
        if (idx < 0) return null;
        String after = expr.substring(idx + key.length()).replaceAll("\\s*==?\\s*", "").trim();
        if (after.startsWith("'") && after.contains("'")) {
            return after.substring(1, after.indexOf("'", 1));
        }
        return null;
    }
}
