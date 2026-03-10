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
 * Anthropic Claude API 实现。
 *
 * <p>使用 JDK 原生 HttpClient，无额外 SDK 依赖。
 * API 文档：<a href="https://docs.anthropic.com/en/api/messages">Messages API</a>
 */
@Slf4j
public class ClaudeProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final String baseUrl;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ClaudeProvider(String apiKey, String model, int maxTokens,
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
            throw new IllegalStateException("Anthropic API Key 未配置，请设置 docrank.agent.llm.api-key 或 ANTHROPIC_API_KEY 环境变量");
        }

        try {
            String body = buildRequestBody(messages);
            log.debug("Claude 请求: model={}, messages={}", model, messages.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Claude API 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Claude API 返回错误 " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Claude API 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(List<LlmMessage> messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        // Claude API 的 system 消息单独放，不在 messages 数组中
        String systemContent = messages.stream()
                .filter(m -> "system".equals(m.role()))
                .map(LlmMessage::content)
                .findFirst()
                .orElse(null);
        if (systemContent != null) {
            root.put("system", systemContent);
        }

        ArrayNode msgs = mapper.createArrayNode();
        messages.stream()
                .filter(m -> !"system".equals(m.role()))
                .forEach(m -> {
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
        JsonNode content = root.path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText();
        }
        throw new RuntimeException("Claude API 响应格式异常: " + json);
    }
}
