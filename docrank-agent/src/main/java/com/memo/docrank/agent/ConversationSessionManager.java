package com.memo.docrank.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史管理器（内存实现）。
 *
 * <p>每个 sessionId 对应一个双端队列，超过 maxTurns 时自动丢弃最早的对话轮。
 * 线程安全（每个 session 独立锁，使用 ConcurrentHashMap）。
 */
@Slf4j
public class ConversationSessionManager {

    private final int maxTurns;
    /** sessionId → 对话历史（按时间顺序，oldest first） */
    private final Map<String, Deque<ConversationTurn>> sessions = new ConcurrentHashMap<>();

    public ConversationSessionManager(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    /**
     * 创建新会话，返回生成的 sessionId。
     */
    public String newSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayDeque<>());
        log.debug("新建会话: {}", sessionId);
        return sessionId;
    }

    /**
     * 获取会话历史（按时间顺序）。若 sessionId 不存在则自动创建。
     */
    public List<ConversationTurn> getHistory(String sessionId) {
        Deque<ConversationTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        return new ArrayList<>(deque);
    }

    /**
     * 追加一轮对话记录，超出 maxTurns 时丢弃最早的。
     */
    public void addTurn(String sessionId, String userMessage, String assistantMessage) {
        Deque<ConversationTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= maxTurns) {
                deque.pollFirst();
            }
            deque.addLast(new ConversationTurn(userMessage, assistantMessage));
        }
    }

    /**
     * 清空指定会话历史（保留 session，但清空对话记录）。
     */
    public void clearSession(String sessionId) {
        Deque<ConversationTurn> deque = sessions.get(sessionId);
        if (deque != null) {
            deque.clear();
            log.debug("清空会话历史: {}", sessionId);
        }
    }

    /**
     * 删除会话（完全移除）。
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("删除会话: {}", sessionId);
    }

    /**
     * 判断 sessionId 是否存在。
     */
    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
