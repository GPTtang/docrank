package com.memo.docrank.langchain4j;

import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.memory.KnowledgeBaseService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DocRank implementation of LangChain4j {@link ContentRetriever}.
 *
 * <p>Exposes the full DocRank hybrid pipeline (BM25 + Vector + RRF + Rerank + AdvancedScorer)
 * as a LangChain4j retriever that can be plugged into any RAG chain.</p>
 *
 * <pre>{@code
 * ContentRetriever retriever = DocRankHybridRetriever.builder()
 *         .knowledgeBase(knowledgeBaseService)
 *         .topK(5)
 *         .scope("project-x")
 *         .build();
 *
 * RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
 *         .contentRetriever(retriever)
 *         .build();
 * }</pre>
 */
@Slf4j
public class DocRankHybridRetriever implements ContentRetriever {

    private final KnowledgeBaseService kb;
    private final int                  topK;
    private final String               scope;
    private final Map<String, Object>  extraFilters;

    private DocRankHybridRetriever(Builder builder) {
        this.kb           = Objects.requireNonNull(builder.kb, "knowledgeBase is required");
        this.topK         = builder.topK > 0 ? builder.topK : 5;
        this.scope        = builder.scope;
        this.extraFilters = builder.extraFilters != null ? builder.extraFilters : Map.of();
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<SearchResult> results = kb.search(query.text(), topK, scope, extraFilters);

        List<Content> contents = results.stream()
                .map(r -> {
                    dev.langchain4j.data.document.Metadata meta =
                            new dev.langchain4j.data.document.Metadata();
                    meta.put("doc_id",   r.getChunk().getDocId());
                    meta.put("title",    r.getChunk().getTitle()       != null ? r.getChunk().getTitle()       : "");
                    meta.put("section",  r.getChunk().getSectionPath() != null ? r.getChunk().getSectionPath() : "");
                    meta.put("scope",    r.getChunk().getScope()       != null ? r.getChunk().getScope()       : "");
                    meta.put("score",    String.valueOf(r.getScore()));
                    meta.put("language", r.getChunk().getLanguage()    != null ? r.getChunk().getLanguage().name() : "");
                    TextSegment segment = TextSegment.from(
                            r.getChunk().getChunkText() != null ? r.getChunk().getChunkText() : "",
                            meta);
                    return Content.from(segment);
                })
                .collect(Collectors.toList());

        log.debug("DocRankHybridRetriever: query='{}', scope='{}', hits={}", query.text(), scope, contents.size());
        return contents;
    }

    // ----------------------------------------------------------- Builder

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private KnowledgeBaseService kb;
        private int                  topK = 5;
        private String               scope;
        private Map<String, Object>  extraFilters;

        public Builder knowledgeBase(KnowledgeBaseService kb) {
            this.kb = kb; return this;
        }
        public Builder topK(int topK) {
            this.topK = topK; return this;
        }
        public Builder scope(String scope) {
            this.scope = scope; return this;
        }
        public Builder extraFilters(Map<String, Object> filters) {
            this.extraFilters = filters; return this;
        }
        public DocRankHybridRetriever build() {
            return new DocRankHybridRetriever(this);
        }
    }
}
