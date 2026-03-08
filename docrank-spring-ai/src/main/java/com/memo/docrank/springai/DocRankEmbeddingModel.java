package com.memo.docrank.springai;

import com.memo.docrank.core.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResultMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DocRank implementation of Spring AI {@link EmbeddingModel}.
 *
 * <p>Wraps the local ONNX BGE-M3 embedding provider — no API calls, zero cost.</p>
 *
 * <pre>{@code
 * @Bean
 * EmbeddingModel embeddingModel(EmbeddingProvider provider) {
 *     return DocRankEmbeddingModel.builder()
 *             .provider(provider)
 *             .dimension(1024)
 *             .build();
 * }
 * }</pre>
 */
@Slf4j
public class DocRankEmbeddingModel implements EmbeddingModel {

    private final EmbeddingProvider provider;
    private final int               dimension;

    private DocRankEmbeddingModel(Builder builder) {
        this.provider  = Objects.requireNonNull(builder.provider, "provider is required");
        this.dimension = builder.dimension > 0 ? builder.dimension : 1024;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> vecs = provider.encode(texts);

        List<Embedding> embeddings = new ArrayList<>(vecs.size());
        for (int i = 0; i < vecs.size(); i++) {
            embeddings.add(new Embedding(toDoubleList(vecs.get(i)), i));
        }
        log.debug("DocRankEmbeddingModel: embedded {} texts", texts.size());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public List<Double> embed(Document document) {
        float[] vec = provider.encodeSingle(document.getFormattedContent());
        return toDoubleList(vec);
    }

    @Override
    public List<Double> embed(String text) {
        return toDoubleList(provider.encodeSingle(text));
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        return provider.encode(texts).stream()
                .map(this::toDoubleList)
                .toList();
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    // ----------------------------------------------------------- Builder

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private EmbeddingProvider provider;
        private int               dimension = 1024;

        public Builder provider(EmbeddingProvider provider) {
            this.provider = provider; return this;
        }
        public Builder dimension(int dimension) {
            this.dimension = dimension; return this;
        }
        public DocRankEmbeddingModel build() {
            return new DocRankEmbeddingModel(this);
        }
    }

    // ----------------------------------------------------------- private

    private List<Double> toDoubleList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add((double) v);
        return list;
    }
}
