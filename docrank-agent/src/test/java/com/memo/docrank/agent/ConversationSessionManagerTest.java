package com.memo.docrank.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSessionManagerTest {

    @Test
    void newSession_returnsNonNullId() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        String id = mgr.newSession();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    void getHistory_newSession_returnsEmpty() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        List<ConversationTurn> history = mgr.getHistory("unknown-session");
        assertTrue(history.isEmpty());
    }

    @Test
    void addTurn_andGetHistory_returnsCorrectOrder() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        mgr.addTurn("s1", "问题1", "回答1");
        mgr.addTurn("s1", "问题2", "回答2");

        List<ConversationTurn> history = mgr.getHistory("s1");
        assertEquals(2, history.size());
        assertEquals("问题1", history.get(0).userMessage());
        assertEquals("问题2", history.get(1).userMessage());
    }

    @Test
    void addTurn_exceedsMaxTurns_dropsOldest() {
        ConversationSessionManager mgr = new ConversationSessionManager(3);
        mgr.addTurn("s1", "q1", "a1");
        mgr.addTurn("s1", "q2", "a2");
        mgr.addTurn("s1", "q3", "a3");
        mgr.addTurn("s1", "q4", "a4"); // 超出，应丢弃 q1

        List<ConversationTurn> history = mgr.getHistory("s1");
        assertEquals(3, history.size());
        assertEquals("q2", history.get(0).userMessage());
        assertEquals("q4", history.get(2).userMessage());
    }

    @Test
    void clearSession_removesHistory() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        mgr.addTurn("s1", "问题", "回答");
        mgr.clearSession("s1");

        assertTrue(mgr.getHistory("s1").isEmpty());
    }

    @Test
    void exists_returnsTrueAfterAddTurn() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        mgr.addTurn("s1", "q", "a");
        assertTrue(mgr.exists("s1"));
        assertFalse(mgr.exists("s-not-exist"));
    }
}
