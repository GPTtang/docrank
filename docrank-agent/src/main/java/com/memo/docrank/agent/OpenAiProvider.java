package com.memo.docrank.agent;

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
import java.util.List;

/**
 * OpenAI Chat Completions API 实现。
 *
 * <p>兼容所有实现 OpenAI 协议的服务：OpenAI 官方、Azure OpenAI、
 * Ollama（本地）、vLLM、LM Studio 等。
 *
 * <p>使用 JDK 原生 HttpClient，无额外 SDK 依赖。
 */
@Slf4j
public class OpenAiProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final String baseUrl;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiProvider(String apiKey, String model, int maxTokens,
                          double temperature, String baseUrl) {
        this.apiKey      = apiKey;
        this.model       = model;
        this.maxTokens   = maxTokens;
        this.temperature = temperature;
        this.baseUrl     = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.httpClient  = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String chat(List<LlmMessage> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API Key 未配置，请设置 docrank.agent.llm.api-key 或 OPENAI_API_KEY 环境变量");
        }

        try {
            String body = buildRequestBody(messages);
            log.debug("OpenAI 请求: model={}, messages={}", model, messages.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI API 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("OpenAI API 返回错误 " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenAI API 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(List<LlmMessage> messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        ArrayNode msgs = mapper.createArrayNode();
        messages.forEach(m -> {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", m.role());
            msg.put("content", m.content());
            msgs.add(msg);
        });
        root.set("messages", msgs);

        return mapper.writeValueAsString(root);
    }

    private String parseResponse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText();
        }
        throw new RuntimeException("OpenAI API 响应格式异常: " + json);
    }
}
