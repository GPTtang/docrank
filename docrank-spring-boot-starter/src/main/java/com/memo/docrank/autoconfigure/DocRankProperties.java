package com.memo.docrank.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "docrank")
public class DocRankProperties {

    private BackendProps   backend   = new BackendProps();
    private EmbeddingProps embedding = new EmbeddingProps();
    private RerankerProps  reranker  = new RerankerProps();
    private ChunkProps     chunk     = new ChunkProps();
    private LuceneProps    lucene    = new LuceneProps();
    private LanguageProps  language  = new LanguageProps();
    private ScoringProps   scoring   = new ScoringProps();
    private IngestProps    ingest    = new IngestProps();

    // ---------------------------------------------------------------- backend
    @Data
    public static class BackendProps {
        /** 向量后端类型：lancedb | qdrant | pgvector | memory */
        private String       type     = "lancedb";
        private LanceDBProps lancedb  = new LanceDBProps();
        private QdrantProps  qdrant   = new QdrantProps();
        private PgvectorProps pgvector = new PgvectorProps();

        @Data
        public static class LanceDBProps {
            private String host      = "localhost";
            private int    port      = 8181;
            private String tableName = "docrank_memories";
        }

        @Data
        public static class QdrantProps {
            private String host           = "localhost";
            private int    port           = 6333;
            private String collectionName = "docrank_memories";
        }

        @Data
        public static class PgvectorProps {
            private String jdbcUrl   = "jdbc:postgresql://localhost:5432/docrank";
            private String username  = "postgres";
            private String password  = "";
            private String tableName = "docrank_chunks";
        }
    }

    // -------------------------------------------------------------- embedding
    @Data
    public static class EmbeddingProps {
        private int      dimension = 1024;
        private int      batchSize = 32;
        private OnnxProps onnx     = new OnnxProps();

        @Data
        public static class OnnxProps {
            /** BGE-M3 模型目录，包含 model.onnx + tokenizer.json */
            private String modelPath = "/opt/docrank/models/bge-m3";
        }
    }

    // --------------------------------------------------------------- reranker
    @Data
    public static class RerankerProps {
        private boolean  enabled = true;
        private int      topN    = 20;
        private OnnxProps onnx   = new OnnxProps();

        @Data
        public static class OnnxProps {
            /** bge-reranker-v2-m3 模型目录，包含 model.onnx + tokenizer.json */
            private String modelPath = "/opt/docrank/models/bge-reranker-v2-m3";
        }
    }

    // ---------------------------------------------------------------- lucene
    @Data
    public static class LuceneProps {
        /** Lucene 本地索引目录 */
        private String indexPath    = "/opt/docrank/data/lucene-index";
        /** IndexWriter RAM 缓冲区（MB），越大批量写入越快 */
        private int    ramBufferMb  = 64;
    }

    // ----------------------------------------------------------------- chunk
    @Data
    public static class ChunkProps {
        /** CJK 按字符数，英文按词数 */
        private int size    = 512;
        private int overlap = 64;
    }

    // -------------------------------------------------------------- language
    @Data
    public static class LanguageProps {
        /** 支持：zh, ja, en, auto */
        private String defaultLang = "auto";
    }

    // --------------------------------------------------------------- scoring
    @Data
    public static class ScoringProps {
        /** 时间衰减系数 λ，每天衰减幅度 */
        private double  recencyLambda = 0.005;
        /** 低于此分数的结果不返回 */
        private double  minScore      = 0.0;
        /** 是否启用 MMR 多样性降权 */
        private boolean mmrEnabled    = true;
        /** MMR 同文档降权系数（每多一个同文档 chunk，乘以此系数） */
        private double  mmrPenalty    = 0.85;
    }

    // ---------------------------------------------------------------- ingest
    @Data
    public static class IngestProps {
        /** 是否在写入前做内容去重（相似度阈值） */
        private boolean dedupEnabled   = false;
        /** 去重相似度阈值（0.0~1.0），超过此值视为重复 */
        private double  dedupThreshold = 0.95;
        /** 批量写入并发线程数 */
        private int     batchThreads   = 4;
    }
}
