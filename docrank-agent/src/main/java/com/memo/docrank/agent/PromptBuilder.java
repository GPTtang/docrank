package com.memo.docrank.agent;

import com.memo.docrank.core.model.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 构建器：将检索结果、对话历史和用户问题组合成 LLM 消息列表。
 *
 * <p>Prompt 结构：
 * <pre>
 *   [system]    系统提示 + 检索到的上下文
 *   [user]      历史用户消息 1
 *   [assistant] 历史助手消息 1
 *   ...
 *   [user]      当前用户问题
 * </pre>
 */
public class PromptBuilder {

    static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个专业的知识库助手。请根据下方「参考资料」中提供的内容回答用户问题。\n" +
            "- 只使用参考资料中的信息作答，不要编造内容。\n" +
            "- 如果参考资料中没有相关信息，请诚实说明'当前知识库中未找到相关内容'。\n" +
            "- 回答时可以适当引用来源文档标题。\n" +
            "- 使用与用户提问相同的语言回答。";

    private final String systemPrompt;

    public PromptBuilder(String systemPrompt) {
        this.systemPrompt = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 构建发送给 LLM 的消息列表。
     *
     * @param question      当前用户问题
     * @param history       历史对话（按时间顺序，oldest first）
     * @param searchResults 知识库检索结果
     * @return LLM 消息列表
     */
    public List<LlmMessage> build(String question,
                                  List<ConversationTurn> history,
                                  List<SearchResult> searchResults) {
        List<LlmMessage> messages = new ArrayList<>();

        // 1. System 消息（含检索上下文）
        String contextSection = buildContextSection(searchResults);
        messages.add(LlmMessage.system(systemPrompt + "\n\n" + contextSection));

        // 2. 历史对话
        for (ConversationTurn turn : history) {
            messages.add(LlmMessage.user(turn.userMessage()));
            messages.add(LlmMessage.assistant(turn.assistantMessage()));
        }

        // 3. 当前问题
        messages.add(LlmMessage.user(question));

        return messages;
    }

    private String buildContextSection(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "【参考资料】\n（未检索到相关内容）";
        }

        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String title = r.getChunk().getTitle();
            String text  = r.getChunk().getChunkText();
            sb.append(String.format("[%d] %s\n%s\n\n",
                    i + 1,
                    title != null && !title.isBlank() ? title : "无标题",
                    text != null ? text : ""));
        }
        return sb.toString().trim();
    }
}
