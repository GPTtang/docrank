package com.memo.docrank.core.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * LanceDB HTTP 后端
 * 依赖本地运行的 LanceDB 服务（默认端口 8181）
 *
 * 启动方式：pip install lancedb && lancedb --host 0.0.0.0 --port 8181
 */
@Slf4j
public class LanceDBBackend implements IndexBackend {

    private final String baseUrl;
    private final String tableName;
    private final int vectorDim;
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public LanceDBBackend(String host, int port, String tableName, int vectorDim) {
        this.baseUrl   = "http://" + host + ":" + port;
        this.tableName = tableName;
        this.vectorDim = vectorDim;
        this.http      = new RestTemplate();
        this.mapper    = new ObjectMapper();
    }

    // ---------------------------------------------------------------- 索引管理

    @Override
    public void createIndex() {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/create/";
            Map<String, Object> schema = buildSchema();
            post(url, schema);
            log.info("LanceDB 表 '{}' 创建成功", tableName);
        } catch (Exception e) {
            log.warn("LanceDB 表创建失败（可能已存在）: {}", e.getMessage());
        }
    }

    @Override
    public void deleteIndex() {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/";
            http.delete(url);
            log.info("LanceDB 表 '{}' 已删除", tableName);
        } catch (Exception e) {
            log.warn("LanceDB 表删除失败: {}", e.getMessage());
        }
    }

    // --------------------------------------------------------------- 文档写入

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        if (chunks.isEmpty()) return;
        List<Map<String, Object>> rows = chunks.stream()
                .map(this::toRow)
                .toList();
        String url = baseUrl + "/v1/table/" + tableName + "/insert/";
        post(url, Map.of("data", rows, "mode", "overwrite"));
        log.debug("LanceDB upsert {} 条记录", chunks.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        String url = baseUrl + "/v1/table/" + tableName + "/delete/";
        post(url, Map.of("predicate", "doc_id = '" + docId + "'"));
    }

    // ------------------------------------------------------------------ 检索

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK,
                                                Map<String, Object> filters) {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/query/";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("query_type", "fts");
            body.put("limit", topK);
            if (filters != null && !filters.isEmpty()) {
                body.put("filter", buildFilter(filters));
            }
            String resp = post(url, body);
            return parseResults(resp, RecallCandidate.RecallSource.KEYWORD);
        } catch (Exception e) {
            log.error("LanceDB 关键词检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK,
                                               Map<String, Object> filters) {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/query/";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", toList(queryVector));
            body.put("query_type", "vector");
            body.put("limit", topK);
            body.put("metric", "cosine");
            if (filters != null && !filters.isEmpty()) {
                body.put("filter", buildFilter(filters));
            }
            String resp = post(url, body);
            return parseResults(resp, RecallCandidate.RecallSource.VECTOR);
        } catch (Exception e) {
            log.error("LanceDB 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------- 状态

    @Override
    public boolean isHealthy() {
        try {
            http.getForObject(baseUrl + "/v1/table/", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long countChunks() {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/";
            String resp = http.getForObject(url, String.class);
            JsonNode node = mapper.readTree(resp);
            return node.path("num_rows").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<Chunk> listAllChunks(int offset, int limit) {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/query/";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_type", "scan");
            body.put("limit", limit);
            body.put("offset", offset);
            String resp = post(url, body);
            List<RecallCandidate> candidates = parseResults(resp, RecallCandidate.RecallSource.VECTOR);
            return candidates.stream()
                    .map(RecallCandidate::getChunk)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("LanceDB listAllChunks 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ----------------------------------------------------------------- private

    private Map<String, Object> buildSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("chunk_id",    "utf8",    true));
        fields.add(field("doc_id",      "utf8",    false));
        fields.add(field("title",       "utf8",    false));
        fields.add(field("section_path","utf8",    false));
        fields.add(field("chunk_text",  "utf8",    false));
        fields.add(field("chunk_index", "int32",   false));
        fields.add(field("language",    "utf8",    false));
        fields.add(field("updated_at",  "utf8",    false));
        fields.add(field("importance",  "float64", false));
        fields.add(field("expires_at",  "utf8",    true));
        // vec_chunk: float32[vectorDim]
        Map<String, Object> vecField = new LinkedHashMap<>();
        vecField.put("name", "vec_chunk");
        vecField.put("type", Map.of("type", "fixed_size_list",
                "child", Map.of("type", "float32"),
                "list_size", vectorDim));
        vecField.put("nullable", false);
        fields.add(vecField);
        return Map.of("schema", Map.of("fields", fields));
    }

    private Map<String, Object> field(String name, String type, boolean nullable) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", Map.of("type", type));
        f.put("nullable", nullable);
        return f;
    }

    private Map<String, Object> toRow(ChunkWithVectors cwv) {
        Chunk c = cwv.getChunk();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunk_id",     c.getChunkId());
        row.put("doc_id",       c.getDocId());
        row.put("title",        c.getTitle() != null ? c.getTitle() : "");
        row.put("section_path", c.getSectionPath() != null ? c.getSectionPath() : "");
        row.put("chunk_text",   c.getChunkText() != null ? c.getChunkText() : "");
        row.put("chunk_index",  c.getChunkIndex());
        row.put("language",     c.getLanguage() != null ? c.getLanguage().name() : Language.UNKNOWN.name());
        row.put("updated_at",   c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : "");
        row.put("importance",   c.getImportance());
        row.put("expires_at",   c.getExpiresAt() != null ? c.getExpiresAt().toString() : null);
        row.put("vec_chunk",    toList(cwv.getVecChunk()));
        return row;
    }

    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    private List<Float> toList(List<Float> src) {
        return src != null ? src : List.of();
    }

    private String buildFilter(Map<String, Object> filters) {
        List<String> parts = new ArrayList<>();
        filters.forEach((k, v) -> parts.add(k + " = '" + v + "'"));
        return String.join(" AND ", parts);
    }

    private List<RecallCandidate> parseResults(String json,
                                                RecallCandidate.RecallSource source) {
        try {
            JsonNode root = mapper.readTree(json);
            List<RecallCandidate> results = new ArrayList<>();
            for (JsonNode row : root) {
                Chunk chunk = Chunk.builder()
                        .chunkId(row.path("chunk_id").asText())
                        .docId(row.path("doc_id").asText())
                        .title(row.path("title").asText())
                        .sectionPath(row.path("section_path").asText())
                        .chunkText(row.path("chunk_text").asText())
                        .chunkIndex(row.path("chunk_index").asInt())
                        .language(parseLanguage(row.path("language").asText()))
                        .updatedAt(parseInstant(row.path("updated_at").asText()))
                        .importance(row.path("importance").asDouble(1.0))
                        .expiresAt(parseInstant(row.path("expires_at").asText()))
                        .build();
                double score = row.path("_relevance_score").asDouble(
                        row.path("_distance").asDouble(0.0));
                results.add(RecallCandidate.builder()
                        .chunk(chunk).score(score).source(source).build());
            }
            return results;
        } catch (Exception e) {
            log.error("解析 LanceDB 响应失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Language parseLanguage(String s) {
        try { return Language.valueOf(s); } catch (Exception e) { return Language.UNKNOWN; }
    }

    private Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private String post(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = http.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return resp.getBody();
    }
}
