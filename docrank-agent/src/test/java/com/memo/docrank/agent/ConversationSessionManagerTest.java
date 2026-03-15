package com.memo.docrank.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        mgr.addTurn("s1", "闂1", "鍥炵瓟1");
        mgr.addTurn("s1", "闂2", "鍥炵瓟2");

        List<ConversationTurn> history = mgr.getHistory("s1");
        assertEquals(2, history.size());
        assertEquals("闂1", history.get(0).userMessage());
        assertEquals("闂2", history.get(1).userMessage());
    }

    @Test
    void addTurn_exceedsMaxTurns_dropsOldest() {
        ConversationSessionManager mgr = new ConversationSessionManager(3);
        mgr.addTurn("s1", "q1", "a1");
        mgr.addTurn("s1", "q2", "a2");
        mgr.addTurn("s1", "q3", "a3");
        mgr.addTurn("s1", "q4", "a4");

        List<ConversationTurn> history = mgr.getHistory("s1");
        assertEquals(3, history.size());
        assertEquals("q2", history.get(0).userMessage());
        assertEquals("q4", history.get(2).userMessage());
    }

    @Test
    void clearSession_removesHistory() {
        ConversationSessionManager mgr = new ConversationSessionManager(10);
        mgr.addTurn("s1", "闂", "鍥炵瓟");
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

    @Test
    void concurrentReadWrite_sameSession_noConcurrencyException() throws Exception {
        ConversationSessionManager mgr = new ConversationSessionManager(1000);
        String session = "s1";

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int t = 0; t < 4; t++) {
            tasks.add(() -> {
                for (int i = 0; i < 500; i++) {
                    mgr.addTurn(session, "q" + i, "a" + i);
                }
                return null;
            });
            tasks.add(() -> {
                for (int i = 0; i < 500; i++) {
                    mgr.getHistory(session);
                }
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (Future<Void> future : futures) {
            future.get();
        }
    }
}
