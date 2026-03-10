package com.memo.docrank.agent;

import java.util.List;

/**
 * LLM 提供商抽象接口。
 *
 * <p>实现类：{@link ClaudeProvider}（Anthropic Claude API）、
 * {@link OpenAiProvider}（OpenAI / 兼容协议）。
 */
public interface LlmProvider {

    /**
     * 发送多轮消息并返回 LLM 的文字回复。
     *
     * @param messages 消息列表，顺序为 [system?, user, assistant?, user, ...]
     * @return LLM 生成的文本
     * @throws RuntimeException 调用失败时抛出
     */
    String chat(List<LlmMessage> messages);
}
