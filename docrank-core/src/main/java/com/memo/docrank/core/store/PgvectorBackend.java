package com.memo.docrank.core.store;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * pgvector 向量数据库后端
 *
 * 依赖 PostgreSQL + pgvector 扩展。
 * 通过 JDBC 直接操作，向量以字符串 "[0.1,0.2,...]" 形式传参。
 */
@Slf4j
public class PgvectorBackend implements IndexBackend {

    private final DataSource dataSource;
    private final String tableName;
    private final int dimension;

    public PgvectorBackend(String jdbcUrl, String username, String password,
                           String tableName, int dimension) {
        this.tableName = tableName;
        this.dimension = dimension;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setPoolName("docrank-pgvector-pool");
        this.dataSource = new HikariDataSource(config);

        createIndex();
    }

    // ---------------------------------------------------------------- 索引管理

    @Override
    public void createIndex() {
        // 尝试创建 pgvector 扩展（需要超级用户权限，失败时忽略——用户可能已预先创建）
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            log.warn("CREATE EXTENSION vector 失败（可能已存在或权限不足，忽略）: {}", e.getMessage());
        }

        // 建表
        String createTable = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "chunk_id    TEXT PRIMARY KEY," +
                "doc_id      TEXT NOT NULL," +
                "title       TEXT," +
                "section_path TEXT," +
                "chunk_text  TEXT NOT NULL," +
                "vec_chunk   vector(" + dimension + ")," +
                "language    TEXT," +
                "updated_at  TIMESTAMPTZ," +
                "importance  DOUBLE PRECISION DEFAULT 1.0," +
                "expires_at  TIMESTAMPTZ" +
                ")";

        String createDocIdIdx = "CREATE INDEX IF NOT EXISTS " + tableName + "_doc_id_idx" +
                " ON " + tableName + " (doc_id)";

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createTable);
            st.execute(createDocIdIdx);
            log.info("pgvector 表 '{}' 初始化完成 (dim={})", tableName, dimension);
        } catch (Exception e) {
            log.warn("pgvector 建表失败（可能已存在）: {}", e.getMessage());
        }
    }

    @Override
    public void deleteIndex() {
        String sql = "DROP TABLE IF EXISTS " + tableName;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            log.info("pgvector 表 '{}' 已删除", tableName);
        } catch (Exception e) {
            log.warn("pgvector 表删除失败: {}", e.getMessage());
        }
    }

    // --------------------------------------------------------------- 文档写入

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        String sql = "INSERT INTO " + tableName +
                " (chunk_id, doc_id, title, section_path, chunk_text," +
                "  vec_chunk, language, updated_at, importance, expires_at)" +
                " VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?)" +
                " ON CONFLICT (chunk_id) DO UPDATE SET" +
                "  doc_id = EXCLUDED.doc_id," +
                "  title = EXCLUDED.title," +
                "  section_path = EXCLUDED.section_path," +
                "  chunk_text = EXCLUDED.chunk_text," +
                "  vec_chunk = EXCLUDED.vec_chunk," +
                "  updated_at = EXCLUDED.updated_at," +
                "  importance = EXCLUDED.importance," +
                "  expires_at = EXCLUDED.expires_at";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (ChunkWithVectors cwv : chunks) {
                Chunk c = cwv.getChunk();
                ps.setString(1, c.getChunkId());
                ps.setString(2, c.getDocId());
                ps.setString(3, c.getTitle());
                ps.setString(4, c.getSectionPath());
                ps.setString(5, c.getChunkText() != null ? c.getChunkText() : "");
                ps.setString(6, vecToString(cwv.getVecChunk()));
                ps.setString(7, c.getLanguage() != null ? c.getLanguage().name() : Language.UNKNOWN.name());
                if (c.getUpdatedAt() != null) {
                    ps.setTimestamp(8, Timestamp.from(c.getUpdatedAt()));
                } else {
                    ps.setNull(8, Types.TIMESTAMP);
                }
                ps.setDouble(9, c.getImportance());
                if (c.getExpiresAt() != null) {
                    ps.setTimestamp(10, Timestamp.from(c.getExpiresAt()));
                } else {
                    ps.setNull(10, Types.TIMESTAMP);
                }
                ps.addBatch();
            }
            ps.executeBatch();
            log.debug("pgvector upsert {} 条记录", chunks.size());
        } catch (SQLException e) {
            log.error("pgvector upsertChunks 失败: {}", e.getMessage(), e);
            throw new RuntimeException("pgvector upsertChunks 失败", e);
        }
    }

    @Override
    public void deleteByDocId(String docId) {
        String sql = "DELETE FROM " + tableName + " WHERE doc_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            int deleted = ps.executeUpdate();
            log.debug("pgvector 删除 doc_id='{}' 共 {} 条", docId, deleted);
        } catch (SQLException e) {
            log.error("pgvector deleteByDocId 失败: {}", e.getMessage(), e);
            throw new RuntimeException("pgvector deleteByDocId 失败", e);
        }
    }

    // ------------------------------------------------------------------ 检索

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK,
                                               Map<String, Object> filters) {
        // pgvector 不支持全文检索，返回空列表（BM25 由 Lucene 负责）
        return Collections.emptyList();
    }

    @Override
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK,
                                              Map<String, Object> filters) {
        String vecStr = vecToString(queryVector);
        String sql = "SELECT chunk_id, doc_id, title, section_path, chunk_text, language," +
                "       updated_at, importance, expires_at," +
                "       1 - (vec_chunk <=> ?::vector) AS score" +
                " FROM " + tableName +
                " WHERE (expires_at IS NULL OR expires_at > NOW())" +
                " ORDER BY vec_chunk <=> ?::vector" +
                " LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vecStr);
            ps.setString(2, vecStr);
            ps.setInt(3, topK);

            List<RecallCandidate> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Chunk chunk = toChunk(rs);
                    double score = rs.getDouble("score");
                    results.add(RecallCandidate.builder()
                            .chunk(chunk)
                            .score(score)
                            .source(RecallCandidate.RecallSource.VECTOR)
                            .build());
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("pgvector vectorSearch 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------- 状态

    @Override
    public boolean isHealthy() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            log.warn("pgvector 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public long countChunks() {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            log.error("pgvector countChunks 失败: {}", e.getMessage(), e);
            return -1L;
        }
    }

    @Override
    public List<Chunk> listAllChunks(int offset, int limit) {
        String sql = "SELECT chunk_id, doc_id, title, section_path, chunk_text, language," +
                "       updated_at, importance, expires_at" +
                " FROM " + tableName +
                " ORDER BY chunk_id" +
                " LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);

            List<Chunk> chunks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunks.add(toChunk(rs));
                }
            }
            return chunks;
        } catch (SQLException e) {
            log.error("pgvector listAllChunks 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ----------------------------------------------------------------- private

    /**
     * 将 List<Float> 向量转成 "[0.1,0.2,...]" 格式字符串
     */
    private String vecToString(List<Float> vec) {
        if (vec == null || vec.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vec.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 将 float[] 向量转成 "[0.1,0.2,...]" 格式字符串
     */
    private String vecToString(float[] vec) {
        if (vec == null || vec.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 从 ResultSet 构建 Chunk 对象
     */
    private Chunk toChunk(ResultSet rs) throws SQLException {
        String langStr = rs.getString("language");
        Language language;
        try {
            language = langStr != null ? Language.valueOf(langStr) : Language.UNKNOWN;
        } catch (IllegalArgumentException e) {
            language = Language.UNKNOWN;
        }

        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedAtTs != null ? updatedAtTs.toInstant() : null;

        Timestamp expiresAtTs = rs.getTimestamp("expires_at");
        Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;

        return Chunk.builder()
                .chunkId(rs.getString("chunk_id"))
                .docId(rs.getString("doc_id"))
                .title(rs.getString("title"))
                .sectionPath(rs.getString("section_path"))
                .chunkText(rs.getString("chunk_text"))
                .language(language)
                .updatedAt(updatedAt)
                .importance(rs.getDouble("importance"))
                .expiresAt(expiresAt)
                .build();
    }
}
