package com.memo.docrank.agent;

/**
 * 发送给 LLM API 的消息单元。
 *
 * @param role    消息角色：system | user | assistant
 * @param content 消息内容
 */
public record LlmMessage(String role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}
