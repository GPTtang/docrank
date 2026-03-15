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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant vector backend.
 */
@Slf4j
public class QdrantBackend implements IndexBackend {

    private static final String COLLECTION_PATH = "/collections/";

    private final String baseUrl;
    private final String collectionName;
    private final int vectorDim;
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public QdrantBackend(String host, int port, String collectionName, int vectorDim) {
        this.baseUrl = "http://" + host + ":" + port;
        this.collectionName = collectionName;
        this.vectorDim = vectorDim;
        this.http = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    @Override
    public void createIndex() {
        try {
            String url = baseUrl + COLLECTION_PATH + collectionName;
            Map<String, Object> body = Map.of(
                    "vectors", Map.of("size", vectorDim, "distance", "Cosine")
            );
            put(url, body);
            log.info("Qdrant collection '{}' created (dim={})", collectionName, vectorDim);
        } catch (Exception e) {
            log.warn("Qdrant collection create failed (maybe already exists): {}", e.getMessage());
        }
    }

    @Override
    public void deleteIndex() {
        try {
            String url = baseUrl + COLLECTION_PATH + collectionName;
            http.delete(url);
            log.info("Qdrant collection '{}' deleted", collectionName);
        } catch (Exception e) {
            log.warn("Qdrant collection delete failed: {}", e.getMessage());
        }
    }

    @Override
    public void upsertChunks(List<ChunkWithVectors> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (ChunkWithVectors cwv : chunks) {
            Chunk c = cwv.getChunk();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunk_id", c.getChunkId());
            payload.put("doc_id", c.getDocId());
            payload.put("title", c.getTitle() != null ? c.getTitle() : "");
            payload.put("section_path", c.getSectionPath() != null ? c.getSectionPath() : "");
            payload.put("chunk_text", c.getChunkText() != null ? c.getChunkText() : "");
            payload.put("chunk_index", c.getChunkIndex());
            payload.put("language", c.getLanguage() != null ? c.getLanguage().name() : Language.UNKNOWN.name());
            payload.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : "");
            payload.put("importance", c.getImportance());
            payload.put("expires_at", c.getExpiresAt() != null ? c.getExpiresAt().toString() : null);

            String pointId = toUuidString(c.getChunkId());
            points.add(Map.of(
                    "id", pointId,
                    "vector", cwv.getVecChunk(),
                    "payload", payload
            ));
        }

        String url = baseUrl + COLLECTION_PATH + collectionName + "/points";
        put(url, Map.of("points", points));
        log.debug("Qdrant upsert {} rows", chunks.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        String url = baseUrl + COLLECTION_PATH + collectionName + "/points/delete";
        Map<String, Object> body = Map.of(
                "filter", Map.of(
                        "must", List.of(Map.of("key", "doc_id", "match", Map.of("value", docId)))
                )
        );
        post(url, body);
    }

    @Override
    public List<RecallCandidate> keywordSearch(String query, int topK, Map<String, Object> filters) {
        return List.of();
    }

    @Override
    public List<RecallCandidate> vectorSearch(float[] queryVector, int topK, Map<String, Object> filters) {
        try {
            String url = baseUrl + COLLECTION_PATH + collectionName + "/points/search";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", toList(queryVector));
            body.put("limit", topK);
            body.put("with_payload", true);
            if (filters != null && !filters.isEmpty()) {
                body.put("filter", buildFilter(filters));
            }

            String resp = post(url, body);
            return parseResults(resp);
        } catch (Exception e) {
            log.error("Qdrant vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            http.getForObject(baseUrl + "/collections", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long countChunks() {
        try {
            String url = baseUrl + COLLECTION_PATH + collectionName;
            String resp = http.getForObject(url, String.class);
            JsonNode node = mapper.readTree(resp);
            return node.path("result").path("points_count").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<Chunk> listAllChunks(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        try {
            String url = baseUrl + COLLECTION_PATH + collectionName + "/points/scroll";
            List<Chunk> chunks = new ArrayList<>(limit);
            int skip = Math.max(0, offset);
            Object nextPageOffset = null;
            int batchSize = Math.max(limit, 256);

            while (chunks.size() < limit) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", batchSize);
                body.put("with_payload", true);
                body.put("with_vector", false);
                if (nextPageOffset != null) {
                    body.put("offset", nextPageOffset);
                }

                String resp = post(url, body);
                JsonNode root = mapper.readTree(resp);
                JsonNode resultNode = root.path("result");
                JsonNode pointsNode = resultNode.path("points");
                if (!pointsNode.isArray() || pointsNode.isEmpty()) {
                    break;
                }

                for (JsonNode point : pointsNode) {
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    chunks.add(payloadToChunk(point.path("payload")));
                    if (chunks.size() >= limit) {
                        break;
                    }
                }

                nextPageOffset = parseNextPageOffset(resultNode.path("next_page_offset"));
                if (nextPageOffset == null) {
                    break;
                }
            }

            return chunks;
        } catch (Exception e) {
            log.error("Qdrant listAllChunks failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildFilter(Map<String, Object> filters) {
        List<Map<String, Object>> must = new ArrayList<>();
        filters.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                List<Map<String, Object>> should = new ArrayList<>();
                list.forEach(v -> should.add(Map.of("key", key, "match", Map.of("value", v.toString()))));
                must.add(Map.of("should", should));
            } else {
                must.add(Map.of("key", key, "match", Map.of("value", value.toString())));
            }
        });
        return Map.of("must", must);
    }

    private List<RecallCandidate> parseResults(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<RecallCandidate> results = new ArrayList<>();
            for (JsonNode hit : root.path("result")) {
                Chunk chunk = payloadToChunk(hit.path("payload"));
                double score = hit.path("score").asDouble(0.0);
                results.add(RecallCandidate.builder()
                        .chunk(chunk)
                        .score(score)
                        .source(RecallCandidate.RecallSource.VECTOR)
                        .build());
            }
            return results;
        } catch (Exception e) {
            log.error("Parse Qdrant response failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Chunk payloadToChunk(JsonNode payload) {
        return Chunk.builder()
                .chunkId(payload.path("chunk_id").asText())
                .docId(payload.path("doc_id").asText())
                .title(payload.path("title").asText())
                .sectionPath(payload.path("section_path").asText())
                .chunkText(payload.path("chunk_text").asText())
                .chunkIndex(payload.path("chunk_index").asInt())
                .language(parseLanguage(payload.path("language").asText()))
                .updatedAt(parseInstant(payload.path("updated_at").asText()))
                .importance(payload.path("importance").asDouble(1.0))
                .expiresAt(parseInstant(payload.path("expires_at").asText()))
                .build();
    }

    private Object parseNextPageOffset(JsonNode offsetNode) {
        if (offsetNode == null || offsetNode.isNull() || offsetNode.isMissingNode()) {
            return null;
        }
        if (offsetNode.isIntegralNumber()) {
            return offsetNode.longValue();
        }
        if (offsetNode.isTextual()) {
            return offsetNode.textValue();
        }
        return mapper.convertValue(offsetNode, Object.class);
    }

    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
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

    private void put(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private String toUuidString(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
