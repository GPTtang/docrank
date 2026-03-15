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
 * In-memory conversation history manager.
 */
@Slf4j
public class ConversationSessionManager {

    private final int maxTurns;
    /** sessionId -> chronological history (oldest first). */
    private final Map<String, Deque<ConversationTurn>> sessions = new ConcurrentHashMap<>();

    public ConversationSessionManager(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String newSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayDeque<>());
        log.debug("new session: {}", sessionId);
        return sessionId;
    }

    public List<ConversationTurn> getHistory(String sessionId) {
        Deque<ConversationTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void addTurn(String sessionId, String userMessage, String assistantMessage) {
        Deque<ConversationTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= maxTurns) {
                deque.pollFirst();
            }
            deque.addLast(new ConversationTurn(userMessage, assistantMessage));
        }
    }

    public void clearSession(String sessionId) {
        Deque<ConversationTurn> deque = sessions.get(sessionId);
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
            log.debug("cleared session history: {}", sessionId);
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("removed session: {}", sessionId);
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
