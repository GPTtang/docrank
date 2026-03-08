package com.memo.docrank.langchain4j;

import com.memo.docrank.core.embedding.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DocRank implementation of LangChain4j {@link EmbeddingModel}.
 *
 * <p>Wraps the local ONNX BGE-M3 embedding provider — no API calls, zero cost.</p>
 *
 * <pre>{@code
 * EmbeddingModel model = DocRankEmbeddingModel.builder()
 *         .provider(new OnnxEmbeddingProvider("/opt/models/bge-m3", 32))
 *         .dimension(1024)
 *         .build();
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
    public Response<Embedding> embed(String text) {
        float[] vec = provider.encodeSingle(text);
        return Response.from(Embedding.from(vec));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return embed(textSegment.text());
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = textSegments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
        List<float[]> vecs = provider.encode(texts);
        List<Embedding> embeddings = vecs.stream()
                .map(Embedding::from)
                .collect(Collectors.toList());
        log.debug("DocRankEmbeddingModel: embedded {} segments", embeddings.size());
        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
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
}
