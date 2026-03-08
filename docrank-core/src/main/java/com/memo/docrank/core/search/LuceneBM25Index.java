package com.memo.docrank.core.search;

import com.memo.docrank.core.analyzer.LanguageRoutingAnalyzer;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Lucene BM25 全文索引
 *
 * 特性：
 *   - BM25Similarity（Lucene 默认）
 *   - CJK 感知：LanguageRoutingAnalyzer 自动路由中/日/英分析器
 *   - 字段加权：title^3, chunk_text^1, tags^2
 *   - 持久化：FSDirectory（本地磁盘）
 *   - 线程安全：SearcherManager 负责读，IndexWriter 负责写
 */
@Slf4j
public class LuceneBM25Index implements BM25Index {

    // ---------------------------------------------------------------- 字段常量
    static final String F_CHUNK_ID    = "chunk_id";
    static final String F_DOC_ID      = "doc_id";
    static final String F_TITLE       = "title";
    static final String F_SECTION     = "section_path";
    static final String F_TEXT        = "chunk_text";
    static final String F_TAGS        = "tags";
    static final String F_LANGUAGE    = "language";
    static final String F_CHUNK_INDEX = "chunk_index";
    static final String F_UPDATED_AT  = "updated_at";
    static final String F_IMPORTANCE  = "importance";
    static final String F_EXPIRES_AT  = "expires_at";

    // 字段加权
    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            F_TITLE, 3.0f,
            F_TEXT,  1.0f,
            F_TAGS,  2.0f
    );

    private final LanguageRoutingAnalyzer analyzer;
    private final Directory               directory;
    private final IndexWriter             writer;
    private final SearcherManager         searcherManager;

    public LuceneBM25Index(String indexPath) {
        try {
            Path path = Path.of(indexPath);
            Files.createDirectories(path);

            this.analyzer  = new LanguageRoutingAnalyzer();
            this.directory = MMapDirectory.open(path);         // 内存映射，兼顾速度与持久化

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setRAMBufferSizeMB(64);

            this.writer          = new IndexWriter(directory, config);
            this.searcherManager = new SearcherManager(writer, new SearcherFactory());

            log.info("LuceneBM25Index 初始化完成，索引路径: {}, 已有文档数: {}", indexPath, count());
        } catch (IOException e) {
            throw new IllegalStateException("Lucene 索引初始化失败: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- 写入

    @Override
    public void addChunk(Chunk chunk) {
        addChunks(List.of(chunk));
    }

    @Override
    public void addChunks(List<Chunk> chunks) {
        try {
            for (Chunk chunk : chunks) {
                Document doc = toDocument(chunk);
                // 以 chunk_id 为主键 upsert
                writer.updateDocument(new Term(F_CHUNK_ID, chunk.getChunkId()), doc);
            }
            writer.flush();
            searcherManager.maybeRefresh();
            log.debug("Lucene 写入 {} 条", chunks.size());
        } catch (IOException e) {
            throw new IllegalStateException("Lucene 写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByDocId(String docId) {
        try {
            writer.deleteDocuments(new Term(F_DOC_ID, docId));
            writer.flush();
            searcherManager.maybeRefresh();
            log.debug("Lucene 删除 docId={}", docId);
        } catch (IOException e) {
            log.warn("Lucene 删除失败: {}", e.getMessage());
        }
    }

    @Override
    public void deleteAll() {
        try {
            writer.deleteAll();
            writer.flush();
            searcherManager.maybeRefresh();
            log.info("Lucene 索引已清空");
        } catch (IOException e) {
            log.warn("Lucene 清空失败: {}", e.getMessage());
        }
    }

    // --------------------------------------------------------------- 检索

    @Override
    public List<RecallCandidate> search(String query, int topK, Map<String, Object> filters) {
        IndexSearcher searcher = null;
        try {
            searcherManager.maybeRefresh();
            searcher = searcherManager.acquire();

            Query luceneQuery = buildQuery(query, filters);
            TopDocs topDocs   = searcher.search(luceneQuery, topK);

            List<RecallCandidate> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc   = searcher.storedFields().document(scoreDoc.doc);
                Chunk    chunk = docToChunk(doc);
                results.add(RecallCandidate.builder()
                        .chunk(chunk)
                        .score(scoreDoc.score)
                        .source(RecallCandidate.RecallSource.KEYWORD)
                        .build());
            }
            log.debug("Lucene BM25 检索 '{}' → {} 条", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("Lucene 检索失败: {}", e.getMessage());
            return List.of();
        } finally {
            if (searcher != null) {
                try { searcherManager.release(searcher); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public long count() {
        try {
            searcherManager.maybeRefresh();
            IndexSearcher s = searcherManager.acquire();
            try {
                return s.getIndexReader().numDocs();
            } finally {
                searcherManager.release(s);
            }
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            searcherManager.close();
            writer.close();
            directory.close();
            analyzer.close();
        } catch (IOException e) {
            log.warn("Lucene 关闭异常: {}", e.getMessage());
        }
    }

    // --------------------------------------------------------------- private

    private Document toDocument(Chunk chunk) {
        Document doc = new Document();

        // keyword 字段（精确匹配，不分词）
        doc.add(new StringField(F_CHUNK_ID,    chunk.getChunkId(),  Field.Store.YES));
        doc.add(new StringField(F_DOC_ID,      chunk.getDocId(),    Field.Store.YES));
        doc.add(new StoredField(F_CHUNK_INDEX, chunk.getChunkIndex()));
        doc.add(new StringField(F_LANGUAGE,
                chunk.getLanguage() != null ? chunk.getLanguage().name() : Language.UNKNOWN.name(),
                Field.Store.YES));
        if (chunk.getSectionPath() != null)
            doc.add(new StringField(F_SECTION, chunk.getSectionPath(), Field.Store.YES));
        if (chunk.getUpdatedAt() != null)
            doc.add(new StoredField(F_UPDATED_AT, chunk.getUpdatedAt().toString()));

        // importance / expiresAt
        doc.add(new StoredField(F_IMPORTANCE, chunk.getImportance()));
        if (chunk.getExpiresAt() != null)
            doc.add(new StoredField(F_EXPIRES_AT, chunk.getExpiresAt().toString()));

        // text 字段（分词，BM25）
        if (chunk.getTitle() != null && !chunk.getTitle().isBlank()) {
            FieldType titleType = buildTextFieldType();
            doc.add(new Field(F_TITLE, chunk.getTitle(), titleType));
            // 同时存原始值
            doc.add(new StoredField(F_TITLE + "_raw", chunk.getTitle()));
        }
        if (chunk.getChunkText() != null && !chunk.getChunkText().isBlank()) {
            FieldType textType = buildTextFieldType();
            doc.add(new Field(F_TEXT, chunk.getChunkText(), textType));
            doc.add(new StoredField(F_TEXT + "_raw", chunk.getChunkText()));
        }

        // tags（keyword，多值）
        if (chunk.getTags() != null) {
            for (String tag : chunk.getTags()) {
                doc.add(new StringField(F_TAGS, tag.toLowerCase(), Field.Store.YES));
            }
        }

        return doc;
    }

    private FieldType buildTextFieldType() {
        FieldType ft = new FieldType();
        ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        ft.setStored(false);          // 原始值另存 _raw 字段
        ft.setTokenized(true);
        ft.setStoreTermVectors(false);
        ft.freeze();
        return ft;
    }

    private Chunk docToChunk(Document doc) {
        String updatedAtStr = doc.get(F_UPDATED_AT);
        Instant updatedAt = null;
        try { if (updatedAtStr != null) updatedAt = Instant.parse(updatedAtStr); }
        catch (Exception ignored) {}

        List<String> tags = new ArrayList<>();
        for (IndexableField f : doc.getFields(F_TAGS)) tags.add(f.stringValue());

        double importance = doc.getField(F_IMPORTANCE) != null
                ? doc.getField(F_IMPORTANCE).numericValue().doubleValue() : 1.0;
        String expiresAtStr = doc.get(F_EXPIRES_AT);
        Instant expiresAt = null;
        try { if (expiresAtStr != null) expiresAt = Instant.parse(expiresAtStr); }
        catch (Exception ignored) {}

        return Chunk.builder()
                .chunkId(doc.get(F_CHUNK_ID))
                .docId(doc.get(F_DOC_ID))
                .title(doc.get(F_TITLE + "_raw"))
                .sectionPath(doc.get(F_SECTION))
                .chunkText(doc.get(F_TEXT + "_raw"))
                .chunkIndex(doc.getField(F_CHUNK_INDEX) != null
                        ? doc.getField(F_CHUNK_INDEX).numericValue().intValue() : 0)
                .language(parseLanguage(doc.get(F_LANGUAGE)))
                .tags(tags)
                .updatedAt(updatedAt)
                .importance(importance)
                .expiresAt(expiresAt)
                .build();
    }

    private Language parseLanguage(String s) {
        try { return Language.valueOf(s); } catch (Exception e) { return Language.UNKNOWN; }
    }

    private Query buildQuery(String queryStr, Map<String, Object> filters) throws Exception {
        // 转义 Lucene 特殊字符
        String escaped = QueryParser.escape(queryStr);

        // 多字段 BM25 查询
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{F_TITLE, F_TEXT, F_TAGS},
                analyzer,
                FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);

        Query textQuery = escaped.isBlank()
                ? new MatchAllDocsQuery()
                : parser.parse(escaped);

        BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();
        boolBuilder.add(textQuery, BooleanClause.Occur.MUST);

        // 用户自定义 filters
        if (filters != null) {
            filters.forEach((field, value) -> {
                if (value instanceof List<?> list) {
                    BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
                    list.forEach(v -> orBuilder.add(
                            new TermQuery(new Term(field, v.toString())),
                            BooleanClause.Occur.SHOULD));
                    boolBuilder.add(orBuilder.build(), BooleanClause.Occur.FILTER);
                } else {
                    boolBuilder.add(
                            new TermQuery(new Term(field, value.toString())),
                            BooleanClause.Occur.FILTER);
                }
            });
        }

        return boolBuilder.build();
    }
}
