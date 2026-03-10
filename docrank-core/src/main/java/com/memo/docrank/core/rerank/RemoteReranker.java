package com.memo.docrank.core.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memo.docrank.core.model.FusedCandidate;
import com.memo.docrank.core.model.SearchResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程 Rerank API 实现。
 *
 * <p>支持 Cohere 和 Jina AI（两者协议相同）：
 * <ul>
 *   <li>Cohere: {@code https://api.cohere.com}，模型如 {@code rerank-multilingual-v3.0}</li>
 *   <li>Jina AI: {@code https://api.jina.ai}，模型如 {@code jina-reranker-v2-base-multilingual}</li>
 * </ul>
 *
 * <p>通过 {@code docrank.reranker.type: remote} 启用。
 */
@Slf4j
public class RemoteReranker implements Reranker {

    private static final String COHERE_BASE_URL = "https://api.cohere.com";
    private static final String JINA_BASE_URL   = "https://api.jina.ai";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * @param provider  "cohere" 或 "jina"，决定默认 base-url
     * @param apiKey    API Key
     * @param model     模型 ID
     * @param baseUrl   自定义地址，留空则使用 provider 默认值
     */
    public RemoteReranker(String provider, String apiKey, String model, String baseUrl) {
        this.apiKey  = apiKey;
        this.model   = model;
        this.baseUrl = resolveBaseUrl(provider, baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
        log.info("RemoteReranker 初始化: provider={}, baseUrl={}, model={}", provider, this.baseUrl, model);
    }

    @Override
    public List<SearchResult> rerank(String query, List<FusedCandidate> candidates, int topN) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Remote Reranker API Key 未配置，请设置 docrank.reranker.remote.api-key 或对应环境变量");
        }
        if (candidates.isEmpty()) return List.of();

        try {
            String body = buildRequestBody(query, candidates, topN);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Rerank API 返回错误 " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body(), candidates);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Rerank API 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String query, List<FusedCandidate> candidates, int topN) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("query", query);
        root.put("top_n", topN);

        ArrayNode docs = mapper.createArrayNode();
        candidates.forEach(c -> {
            String text = c.getChunk().getChunkText();
            docs.add(text != null ? text : "");
        });
        root.set("documents", docs);

        return mapper.writeValueAsString(root);
    }

    private List<SearchResult> parseResponse(String json, List<FusedCandidate> candidates) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new RuntimeException("Rerank API 响应格式异常: " + json);
        }

        List<SearchResult> output = new ArrayList<>();
        for (JsonNode item : results) {
            int idx = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();

            if (idx >= candidates.size()) continue;
            FusedCandidate c = candidates.get(idx);
            output.add(SearchResult.builder()
                    .chunk(c.getChunk())
                    .score(score)
                    .sparseScore(c.getSparseScore())
                    .vectorScore(c.getVectorScore())
                    .rerankScore(score)
                    .build());
        }
        return output;
    }

    private String resolveBaseUrl(String provider, String customBaseUrl) {
        if (customBaseUrl != null && !customBaseUrl.isBlank()) return customBaseUrl;
        if ("jina".equalsIgnoreCase(provider)) return JINA_BASE_URL;
        return COHERE_BASE_URL;
    }
}
