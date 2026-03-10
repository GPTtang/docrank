package com.memo.docrank.core.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程 Embedding API 实现（OpenAI 兼容协议）。
 *
 * <p>兼容服务：OpenAI、Azure OpenAI、智谱 AI、阿里百炼、Ollama、
 * vLLM 以及任何实现了 {@code POST /v1/embeddings} 接口的服务。
 *
 * <p>通过 {@code docrank.embedding.type: remote} 启用。
 */
@Slf4j
public class RemoteEmbeddingProvider implements EmbeddingProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int dim;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public RemoteEmbeddingProvider(String apiKey, String model, String baseUrl, int dimension) {
        this.apiKey   = apiKey;
        this.model    = model;
        this.baseUrl  = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.dim      = dimension;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
        log.info("RemoteEmbeddingProvider 初始化: baseUrl={}, model={}, dim={}", this.baseUrl, model, dimension);
    }

    @Override
    public List<float[]> encode(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Remote Embedding API Key 未配置，请设置 docrank.embedding.remote.api-key 或对应环境变量");
        }
        if (texts.isEmpty()) return List.of();

        try {
            String body = buildRequestBody(texts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding API 返回错误 " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body(), texts.size());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Embedding API 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimension() {
        return dim;
    }

    private String buildRequestBody(List<String> texts) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode input = mapper.createArrayNode();
        texts.forEach(input::add);
        root.set("input", input);
        return mapper.writeValueAsString(root);
    }

    private List<float[]> parseResponse(String json, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new RuntimeException("Embedding API 响应格式异常: " + json);
        }

        // data 数组按 index 排序
        List<float[]> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) result.add(null);

        for (JsonNode item : data) {
            int idx = item.path("index").asInt();
            JsonNode embNode = item.path("embedding");
            float[] vec = new float[embNode.size()];
            for (int j = 0; j < vec.length; j++) {
                vec[j] = (float) embNode.get(j).asDouble();
            }
            result.set(idx, vec);
        }
        return result;
    }
}
