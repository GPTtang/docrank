package com.memo.docrank.autoconfigure;

import com.memo.docrank.agent.AgentService;
import com.memo.docrank.agent.ClaudeProvider;
import com.memo.docrank.agent.LlmProvider;
import com.memo.docrank.agent.OpenAiProvider;
import com.memo.docrank.core.analyzer.LanguageDetector;
import com.memo.docrank.core.analyzer.MultiLingualAnalyzerFactory;
import com.memo.docrank.core.chunking.ChunkingService;
import com.memo.docrank.core.embedding.EmbeddingProvider;
import com.memo.docrank.core.embedding.OnnxEmbeddingProvider;
import com.memo.docrank.core.embedding.RandomEmbeddingProvider;
import com.memo.docrank.core.embedding.RemoteEmbeddingProvider;
import com.memo.docrank.core.ingest.ParserRegistry;
import com.memo.docrank.core.rerank.NoOpReranker;
import com.memo.docrank.core.rerank.OnnxReranker;
import com.memo.docrank.core.rerank.Reranker;
import com.memo.docrank.core.rerank.RemoteReranker;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(DocRankProperties.class)
public class DocRankAutoConfiguration {

    // ---------------------------------------------------------------- 向量存储后端

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
                        cfg.getJdbcUrl(), cfg.getUsername(), cfg.getPassword(),
                        cfg.getTableName(), props.getEmbedding().getDimension());
            }
            case "memory" -> {
                log.warn("DocRank 向量后端: InMemory（仅演示，不持久化）");
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

    // ---------------------------------------------------------------- Embedding（BGE-M3 或 Random）

    @Bean
    public EmbeddingProvider embeddingProvider(DocRankProperties props) {
        DocRankProperties.EmbeddingProps ep = props.getEmbedding();
        String type = ep.getType() != null ? ep.getType().toLowerCase() : "onnx";
        return switch (type) {
            case "random" -> {
                log.warn("DocRank Embedding: 随机向量模式（仅演示用，无语义检索能力）");
                yield new RandomEmbeddingProvider(ep.getDimension());
            }
            case "remote" -> {
                DocRankProperties.EmbeddingProps.RemoteEmbeddingProps r = ep.getRemote();
                String apiKey = r.getApiKey() != null && !r.getApiKey().isBlank()
                        ? r.getApiKey() : resolveEnvKey("OPENAI_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("DocRank Embedding: remote 模式未配置 API Key，" +
                            "请设置 docrank.embedding.remote.api-key 或 OPENAI_API_KEY 环境变量");
                }
                log.info("DocRank Embedding: 远程 API @ {}, model={}", r.getBaseUrl(), r.getModel());
                yield new RemoteEmbeddingProvider(
                        apiKey != null ? apiKey : "",
                        r.getModel(), r.getBaseUrl(), ep.getDimension());
            }
            default -> {
                String modelPath = ep.getOnnx().getModelPath();
                log.info("DocRank Embedding: BGE-M3 ONNX @ {}", modelPath);
                yield new OnnxEmbeddingProvider(modelPath, ep.getBatchSize());
            }
        };
    }

    // ---------------------------------------------------------------- Reranker（bge-reranker-v2-m3 或 NoOp）

    @Bean
    public Reranker reranker(DocRankProperties props) {
        if (!props.getReranker().isEnabled()) {
            log.warn("DocRank Reranker: 已禁用（NoOp）");
            return new NoOpReranker();
        }
        String type = props.getReranker().getType() != null
                ? props.getReranker().getType().toLowerCase() : "onnx";
        if ("remote".equals(type)) {
            DocRankProperties.RerankerProps.RemoteRerankerProps r = props.getReranker().getRemote();
            String envVar = "jina".equalsIgnoreCase(r.getProvider()) ? "JINA_API_KEY" : "COHERE_API_KEY";
            String apiKey = r.getApiKey() != null && !r.getApiKey().isBlank()
                    ? r.getApiKey() : resolveEnvKey(envVar);
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("DocRank Reranker: remote 模式未配置 API Key，" +
                        "请设置 docrank.reranker.remote.api-key 或 {} 环境变量", envVar);
            }
            log.info("DocRank Reranker: 远程 API provider={}, model={}", r.getProvider(), r.getModel());
            return new RemoteReranker(r.getProvider(), apiKey != null ? apiKey : "",
                    r.getModel(), r.getBaseUrl());
        }
        String modelPath = props.getReranker().getOnnx().getModelPath();
        log.info("DocRank Reranker: bge-reranker-v2-m3 ONNX @ {}", modelPath);
        return new OnnxReranker(modelPath);
    }

    private String resolveEnvKey(String envVar) {
        return System.getenv(envVar);
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
        return new ChunkingService(languageDetector,
                props.getChunk().getSize(), props.getChunk().getOverlap());
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

    // ---------------------------------------------------------------- AI Agent（可选）

    @Bean
    public AgentService agentService(KnowledgeBaseService kb, DocRankProperties props) {
        DocRankProperties.AgentProps agentProps = props.getAgent();
        if (!agentProps.isEnabled()) {
            log.info("DocRank Agent 未启用（设置 docrank.agent.enabled=true 开启）");
            return null;
        }

        DocRankProperties.AgentProps.LlmProps llm = agentProps.getLlm();
        LlmProvider provider = buildLlmProvider(llm);

        log.info("DocRank Agent 已启用: provider={}, model={}", llm.getProvider(), llm.getModel());
        return new AgentService(kb, provider,
                agentProps.getContextTopK(),
                agentProps.getMaxHistoryTurns(),
                agentProps.getSystemPrompt());
    }

    private LlmProvider buildLlmProvider(DocRankProperties.AgentProps.LlmProps llm) {
        String apiKey = resolveApiKey(llm);
        return switch (llm.getProvider().toLowerCase()) {
            case "openai" -> new OpenAiProvider(apiKey, llm.getModel(),
                    llm.getMaxTokens(), llm.getTemperature(), llm.getBaseUrl());
            default -> new ClaudeProvider(apiKey, llm.getModel(),
                    llm.getMaxTokens(), llm.getTemperature(), llm.getBaseUrl());
        };
    }

    private String resolveApiKey(DocRankProperties.AgentProps.LlmProps llm) {
        if (llm.getApiKey() != null && !llm.getApiKey().isBlank()) {
            return llm.getApiKey();
        }
        String envKey = "openai".equalsIgnoreCase(llm.getProvider())
                ? System.getenv("OPENAI_API_KEY")
                : System.getenv("ANTHROPIC_API_KEY");
        if (envKey == null || envKey.isBlank()) {
            log.warn("DocRank Agent: LLM API Key 未配置，调用 chat() 时将抛出异常。" +
                    "请设置 docrank.agent.llm.api-key 或对应环境变量");
        }
        return envKey != null ? envKey : "";
    }
}
