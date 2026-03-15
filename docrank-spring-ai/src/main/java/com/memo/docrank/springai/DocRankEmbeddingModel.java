package com.memo.docrank.springai;

import com.memo.docrank.core.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DocRank implementation of Spring AI {@link EmbeddingModel}.
 */
@Slf4j
public class DocRankEmbeddingModel implements EmbeddingModel {

    private final EmbeddingProvider provider;
    private final int dimension;

    private DocRankEmbeddingModel(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider is required");
        this.dimension = builder.dimension > 0 ? builder.dimension : 1024;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> vecs = provider.encode(texts);

        List<Embedding> embeddings = new ArrayList<>(vecs.size());
        for (int i = 0; i < vecs.size(); i++) {
            embeddings.add(new Embedding(vecs.get(i), i));
        }

        log.debug("DocRankEmbeddingModel: embedded {} texts", texts.size());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return provider.encodeSingle(document.getFormattedContent());
    }

    @Override
    public float[] embed(String text) {
        return provider.encodeSingle(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return provider.encode(texts);
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EmbeddingProvider provider;
        private int dimension = 1024;

        public Builder provider(EmbeddingProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        public DocRankEmbeddingModel build() {
            return new DocRankEmbeddingModel(this);
        }
    }
}
