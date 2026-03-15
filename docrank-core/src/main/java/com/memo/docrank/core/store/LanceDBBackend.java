package com.memo.docrank.core.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.ChunkWithVectors;
import com.memo.docrank.core.model.Language;
import com.memo.docrank.core.model.RecallCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LanceDB HTTP backend.
 */
@Slf4j
public class LanceDBBackend implements IndexBackend {

    private static final Set<String> ALLOWED_FILTER_FIELDS = Set.of(
            "chunk_id", "doc_id", "title", "section_path", "chunk_text",
            "chunk_index", "language", "updated_at", "importance", "expires_at"
    );

    private final String baseUrl;
    private final String tableName;
    private final int vectorDim;
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public LanceDBBackend(String host, int port, String tableName, int vectorDim) {
        this.baseUrl = "http://" + host + ":" + port;
        this.tableName = tableName;
        this.vectorDim = vectorDim;
        this.http = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    @Override
    public void createIndex() {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/create/";
            post(url, buildSchema());
            log.info("LanceDB table '{}' created", tableName);
        } catch (Exception e) {
            log.warn("LanceDB table create failed (maybe already exists): {}", e.getMessage());
        }
    }

    @Override
    public void deleteIndex() {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/";
            http.delete(url);
            log.info("LanceDB table '{}' deleted", tableName);
        } catch (Exception e) {
            log.warn("LanceDB table delete failed: {}", e.getMessage());
        }
    }

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        // Simulate upsert safely: delete existing chunk_id rows, then append incoming rows.
        List<String> chunkIds = chunks.stream()
                .map(cwv -> cwv.getChunk() != null ? cwv.getChunk().getChunkId() : null)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (!chunkIds.isEmpty()) {
            deleteByPredicate(buildOrEqualsPredicate("chunk_id", chunkIds));
        }

        List<Map<String, Object>> rows = chunks.stream().map(this::toRow).toList();
        String url = baseUrl + "/v1/table/" + tableName + "/insert/";
        post(url, Map.of("data", rows, "mode", "append"));
        log.debug("LanceDB upsert {} rows", chunks.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        deleteByPredicate(buildEqualsPredicate("doc_id", docId));
    }

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters) {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/query/";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("query_type", "fts");
            body.put("limit", topK);

            String filterExpr = buildFilter(filters);
            if (!filterExpr.isBlank()) {
                body.put("filter", filterExpr);
            }

            String resp = post(url, body);
            return parseResults(resp, RecallCandidate.RecallSource.KEYWORD);
        } catch (Exception e) {
            log.error("LanceDB keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters) {
        try {
            String url = baseUrl + "/v1/table/" + tableName + "/query/";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", toList(queryVector));
            body.put("query_type", "vector");
            body.put("limit", topK);
            body.put("metric", "cosine");

            String filterExpr = buildFilter(filters);
            if (!filterExpr.isBlank()) {
                body.put("filter", filterExpr);
            }

            String resp = post(url, body);
            return parseResults(resp, RecallCandidate.RecallSource.VECTOR);
        } catch (Exception e) {
            log.error("LanceDB vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

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
            return candidates.stream().map(RecallCandidate::getChunk).toList();
        } catch (Exception e) {
            log.error("LanceDB listAllChunks failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("chunk_id", "utf8", true));
        fields.add(field("doc_id", "utf8", false));
        fields.add(field("title", "utf8", false));
        fields.add(field("section_path", "utf8", false));
        fields.add(field("chunk_text", "utf8", false));
        fields.add(field("chunk_index", "int32", false));
        fields.add(field("language", "utf8", false));
        fields.add(field("updated_at", "utf8", false));
        fields.add(field("importance", "float64", false));
        fields.add(field("expires_at", "utf8", true));

        Map<String, Object> vecField = new LinkedHashMap<>();
        vecField.put("name", "vec_chunk");
        vecField.put("type", Map.of(
                "type", "fixed_size_list",
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
        row.put("chunk_id", c.getChunkId());
        row.put("doc_id", c.getDocId());
        row.put("title", c.getTitle() != null ? c.getTitle() : "");
        row.put("section_path", c.getSectionPath() != null ? c.getSectionPath() : "");
        row.put("chunk_text", c.getChunkText() != null ? c.getChunkText() : "");
        row.put("chunk_index", c.getChunkIndex());
        row.put("language", c.getLanguage() != null ? c.getLanguage().name() : Language.UNKNOWN.name());
        row.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : "");
        row.put("importance", c.getImportance());
        row.put("expires_at", c.getExpiresAt() != null ? c.getExpiresAt().toString() : null);
        row.put("vec_chunk", toList(cwv.getVecChunk()));
        return row;
    }

    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private List<Float> toList(List<Float> src) {
        return src != null ? src : List.of();
    }

    private void deleteByPredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return;
        }
        String url = baseUrl + "/v1/table/" + tableName + "/delete/";
        post(url, Map.of("predicate", predicate));
    }

    private String buildFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        filters.forEach((key, value) -> {
            String normalized = normalizeFilterField(key);
            if (normalized == null) {
                return;
            }
            String clause = buildClause(normalized, value);
            if (clause != null && !clause.isBlank()) {
                parts.add(clause);
            }
        });

        return String.join(" AND ", parts);
    }

    private String normalizeFilterField(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String normalized = field.trim().toLowerCase();
        if (!ALLOWED_FILTER_FIELDS.contains(normalized)) {
            log.debug("Ignore unsupported LanceDB filter field: {}", field);
            return null;
        }
        return normalized;
    }

    private String buildClause(String field, Object value) {
        if (value instanceof List<?> list) {
            List<String> clauses = list.stream()
                    .map(v -> buildEqualsPredicate(field, v))
                    .filter(c -> c != null && !c.isBlank())
                    .toList();
            if (clauses.isEmpty()) {
                return "";
            }
            return clauses.size() == 1 ? clauses.get(0) : "(" + String.join(" OR ", clauses) + ")";
        }
        return buildEqualsPredicate(field, value);
    }

    private String buildOrEqualsPredicate(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> clauses = values.stream()
                .map(v -> buildEqualsPredicate(field, v))
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (clauses.isEmpty()) {
            return "";
        }
        return clauses.size() == 1 ? clauses.get(0) : "(" + String.join(" OR ", clauses) + ")";
    }

    private String buildEqualsPredicate(String field, Object value) {
        if (value == null) {
            return field + " IS NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return field + " = " + value;
        }
        return field + " = '" + escapeLiteral(String.valueOf(value)) + "'";
    }

    private String escapeLiteral(String raw) {
        return raw.replace("'", "''");
    }

    private List<RecallCandidate> parseResults(String json, RecallCandidate.RecallSource source) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode rows = root.isArray() ? root : root.path("data");
            if (!rows.isArray()) {
                return List.of();
            }

            List<RecallCandidate> results = new ArrayList<>();
            for (JsonNode row : rows) {
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

                double score = row.path("_relevance_score").asDouble(row.path("_distance").asDouble(0.0));
                results.add(RecallCandidate.builder().chunk(chunk).score(score).source(source).build());
            }
            return results;
        } catch (Exception e) {
            log.error("Parse LanceDB response failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Language parseLanguage(String s) {
        try {
            return Language.valueOf(s);
        } catch (Exception e) {
            return Language.UNKNOWN;
        }
    }

    private Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String post(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return resp.getBody();
    }
}
