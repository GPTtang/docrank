package com.memo.docrank.autoconfigure;

import com.memo.docrank.core.analyzer.LanguageDetector;
import com.memo.docrank.core.analyzer.MultiLingualAnalyzerFactory;
import com.memo.docrank.core.chunking.ChunkingService;
import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.embedding.OnnxEmbeddingProvider;
import com.memo.docrank.core.ingest.ParserRegistry;
import com.memo.docrank.core.rerank.OnnxReranker;
import com.memo.docrank.core.rerank.Reranker;
import com.memo.docrank.core.search.AdvancedScorer;
import com.memo.docrank.core.search.BM25Index;
import com.memo.docrank.core.search.HybridSearcher;
import com.memo.docrank.core.search.LuceneBM25Index;
import com.memo.docrank.core.store.IndexBackend;
import com.memo.docrank.core.store.InMemoryBackend;
import com.memo.docrank.core.store.LanceDBBackend;
import com.memo.docrank.core.store.PgvectorBackend;
import com.memo.docrank.core.store.QdrantBackend;
import com.memo.docrank.memory.KnowledgeBaseService;
import com.memo.docrank.mcp.DocRankMcpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(DocRankProperties.class)
public class DocRankAutoConfiguration {

    // ---------------------------------------------------------------- 向量存储后端（lancedb / qdrant / memory）

    @Bean
    public IndexBackend indexBackend(DocRankProperties props) {
        DocRankProperties.BackendProps backend = props.getBackend();
        String type = backend.getType() != null ? backend.getType().toLowerCase() : "lancedb";

        return switch (type) {
            case "qdrant" -> {
                DocRankProperties.BackendProps.QdrantProps cfg = backend.getQdrant();
                log.info("DocRank 向量后端: Qdrant @ {}:{}", cfg.getHost(), cfg.getPort());
                QdrantBackend qb = new QdrantBackend(
                        cfg.getHost(), cfg.getPort(), cfg.getCollectionName(),
                        props.getEmbedding().getDimension());
                try { qb.createIndex(); }
                catch (Exception e) { log.warn("Qdrant 初始化失败: {}", e.getMessage()); }
                yield qb;
            }
            case "pgvector" -> {
                DocRankProperties.BackendProps.PgvectorProps cfg = backend.getPgvector();
                log.info("DocRank 向量后端: pgvector @ {}", cfg.getJdbcUrl());
                yield new PgvectorBackend(
                        cfg.getJdbcUrl(),
                        cfg.getUsername(),
                        cfg.getPassword(),
                        cfg.getTableName(),
                        props.getEmbedding().getDimension());
            }
            case "memory" -> {
                log.warn("DocRank 向量后端: InMemory（仅用于测试，不持久化）");
                InMemoryBackend mb = new InMemoryBackend();
                mb.createIndex();
                yield mb;
            }
            default -> {
                DocRankProperties.BackendProps.LanceDBProps cfg = backend.getLancedb();
                log.info("DocRank 向量后端: LanceDB @ {}:{}", cfg.getHost(), cfg.getPort());
                LanceDBBackend lb = new LanceDBBackend(
                        cfg.getHost(), cfg.getPort(), cfg.getTableName(),
                        props.getEmbedding().getDimension());
                try { lb.createIndex(); }
                catch (Exception e) { log.warn("LanceDB 初始化失败（服务可能未启动）: {}", e.getMessage()); }
                yield lb;
            }
        };
    }

    // ---------------------------------------------------------------- Lucene（BM25 全文）

    @Bean
    public BM25Index bm25Index(DocRankProperties props) {
        String indexPath = props.getLucene().getIndexPath();
        log.info("DocRank BM25 索引: Lucene @ {}", indexPath);
        return new LuceneBM25Index(indexPath);
    }

    // ---------------------------------------------------------------- Embedding（BGE-M3）

    @Bean
    public EmbeddingProvider embeddingProvider(DocRankProperties props) {
        String modelPath = props.getEmbedding().getOnnx().getModelPath();
        log.info("DocRank Embedding: BGE-M3 ONNX @ {}", modelPath);
        return new OnnxEmbeddingProvider(modelPath, props.getEmbedding().getBatchSize());
    }

    // ---------------------------------------------------------------- Reranker（bge-reranker-v2-m3）

    @Bean
    public Reranker reranker(DocRankProperties props) {
        String modelPath = props.getReranker().getOnnx().getModelPath();
        log.info("DocRank Reranker: bge-reranker-v2-m3 ONNX @ {}", modelPath);
        return new OnnxReranker(modelPath);
    }

    // ---------------------------------------------------------------- 语言分析

    @Bean
    public LanguageDetector languageDetector() {
        return new LanguageDetector();
    }

    @Bean
    public MultiLingualAnalyzerFactory multiLingualAnalyzerFactory() {
        return new MultiLingualAnalyzerFactory();
    }

    // ---------------------------------------------------------------- 文档解析

    @Bean
    public ParserRegistry parserRegistry() {
        return new ParserRegistry();
    }

    // ---------------------------------------------------------------- 分块

    @Bean
    public ChunkingService chunkingService(DocRankProperties props,
                                           LanguageDetector languageDetector) {
        return new ChunkingService(
                languageDetector,
                props.getChunk().getSize(),
                props.getChunk().getOverlap());
    }

    // ---------------------------------------------------------------- 高级评分

    @Bean
    public AdvancedScorer advancedScorer(DocRankProperties props) {
        DocRankProperties.ScoringProps s = props.getScoring();
        return new AdvancedScorer(s.getRecencyLambda(), s.getMinScore(),
                                  s.isMmrEnabled(), s.getMmrPenalty());
    }

    // ---------------------------------------------------------------- 混合检索

    @Bean
    public HybridSearcher hybridSearcher(BM25Index bm25Index,
                                         IndexBackend vectorBackend,
                                         EmbeddingProvider embedder,
                                         Reranker reranker,
                                         AdvancedScorer advancedScorer) {
        return new HybridSearcher(bm25Index, vectorBackend, embedder, reranker, advancedScorer);
    }

    // ---------------------------------------------------------------- 知识库核心服务

    @Bean
    public KnowledgeBaseService knowledgeBaseService(IndexBackend vectorBackend,
                                                      BM25Index bm25Index,
                                                      EmbeddingProvider embedder,
                                                      ChunkingService chunker,
                                                      HybridSearcher searcher,
                                                      ParserRegistry parserRegistry,
                                                      DocRankProperties props) {
        return new KnowledgeBaseService(
                vectorBackend, bm25Index, embedder, chunker, searcher, parserRegistry,
                props.getIngest().isDedupEnabled(),
                props.getIngest().getDedupThreshold());
    }

    // ---------------------------------------------------------------- MCP Server

    @Bean
    public DocRankMcpServer docRankMcpServer(KnowledgeBaseService kb) {
        return new DocRankMcpServer(kb);
    }
}
