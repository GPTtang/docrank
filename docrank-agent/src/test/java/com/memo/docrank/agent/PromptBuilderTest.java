package com.memo.docrank.agent;

import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PromptBuilder(null); // 使用默认 system prompt
    }

    @Test
    void build_withNoHistory_returnsSystemAndUser() {
        List<LlmMessage> messages = builder.build("什么是BM25？", List.of(), List.of());

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user",   messages.get(1).role());
        assertEquals("什么是BM25？", messages.get(1).content());
    }

    @Test
    void build_systemPromptContainsContextSection() {
        SearchResult result = mockSearchResult("BM25 算法简介", "BM25 是一种词袋检索函数");
        List<LlmMessage> messages = builder.build("什么是BM25？", List.of(), List.of(result));

        String system = messages.get(0).content();
        assertTrue(system.contains("参考资料"), "System prompt 应包含参考资料标题");
        assertTrue(system.contains("BM25 算法简介"), "System prompt 应包含文档标题");
        assertTrue(system.contains("BM25 是一种词袋检索函数"), "System prompt 应包含文档内容");
    }

    @Test
    void build_withHistory_interleavesTurns() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("第一个问题", "第一个回答"),
                new ConversationTurn("第二个问题", "第二个回答")
        );

        List<LlmMessage> messages = builder.build("第三个问题", history, List.of());

        // system + user + assistant + user + assistant + user（当前）
        assertEquals(6, messages.size());
        assertEquals("system",    messages.get(0).role());
        assertEquals("user",      messages.get(1).role());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("user",      messages.get(3).role());
        assertEquals("assistant", messages.get(4).role());
        assertEquals("user",      messages.get(5).role());
        assertEquals("第三个问题", messages.get(5).content());
    }

    @Test
    void build_emptyResults_showsNoContentMessage() {
        List<LlmMessage> messages = builder.build("问题", List.of(), List.of());
        String system = messages.get(0).content();
        assertTrue(system.contains("未检索到相关内容"));
    }

    @Test
    void customSystemPrompt_isUsed() {
        PromptBuilder custom = new PromptBuilder("你是测试助手");
        List<LlmMessage> messages = custom.build("问题", List.of(), List.of());
        assertTrue(messages.get(0).content().startsWith("你是测试助手"));
    }

    // ---- helpers ----

    private SearchResult mockSearchResult(String title, String text) {
        Chunk chunk = Chunk.builder()
                .title(title)
                .chunkText(text)
                .docId("doc-1")
                .build();
        return SearchResult.builder()
                .chunk(chunk)
                .score(0.9)
                .build();
    }
}
