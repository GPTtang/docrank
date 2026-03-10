package com.memo.docrank.agent;

import com.memo.docrank.core.model.SearchResult;
import com.memo.docrank.memory.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * AI Agent 核心服务。
 *
 * <p>实现 RAG 问答闭环：检索 → Prompt 构建 → LLM 生成 → 会话历史记录。
 *
 * <p>使用示例：
 * <pre>
 *   AgentChatResult result = agentService.chat("session-123", "DocRank 支持哪些向量后端？");
 *   System.out.println(result.answer());
 * </pre>
 */
@Slf4j
public class AgentService {

    private final KnowledgeBaseService kb;
    private final LlmProvider llmProvider;
    private final ConversationSessionManager sessionManager;
    private final PromptBuilder promptBuilder;
    private final int contextTopK;

    public AgentService(KnowledgeBaseService kb,
                        LlmProvider llmProvider,
                        int contextTopK,
                        int maxHistoryTurns,
                        String systemPrompt) {
        this.kb             = kb;
        this.llmProvider    = llmProvider;
        this.contextTopK    = contextTopK;
        this.sessionManager = new ConversationSessionManager(maxHistoryTurns);
        this.promptBuilder  = new PromptBuilder(systemPrompt);
    }

    /**
     * 基于知识库的 RAG 问答（带会话历史）。
     *
     * <p>若 sessionId 不存在则自动创建。
     *
     * @param sessionId 会话 ID（可任意字符串，用于区分不同用户/对话）
     * @param question  用户问题
     * @return 包含答案和知识库来源的结果
     */
    public AgentChatResult chat(String sessionId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        log.info("Agent chat: session={}, question={}", sessionId, question);

        // 1. 从知识库检索相关内容
        List<SearchResult> sources = kb.search(question, contextTopK, Map.of());
        log.debug("检索到 {} 条相关内容", sources.size());

        // 2. 获取历史对话
        List<ConversationTurn> history = sessionManager.getHistory(sessionId);

        // 3. 构建 Prompt
        List<LlmMessage> messages = promptBuilder.build(question, history, sources);

        // 4. 调用 LLM
        String answer = llmProvider.chat(messages);
        log.debug("LLM 回答: {}", answer.length() > 100 ? answer.substring(0, 100) + "..." : answer);

        // 5. 保存对话历史
        sessionManager.addTurn(sessionId, question, answer);

        return new AgentChatResult(answer, sources);
    }

    /**
     * 创建新会话，返回生成的 sessionId。
     */
    public String newSession() {
        return sessionManager.newSession();
    }

    /**
     * 清空指定会话的历史记录（保留 sessionId）。
     */
    public void clearSession(String sessionId) {
        sessionManager.clearSession(sessionId);
    }

    /**
     * 判断 sessionId 是否存在。
     */
    public boolean sessionExists(String sessionId) {
        return sessionManager.exists(sessionId);
    }
}
