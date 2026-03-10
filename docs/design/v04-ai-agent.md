# 系统设计: AI Agent 模块

> **对应 PRD**：`docs/prd/v04-ai-agent.md`
> **状态**：已批准
> **作者**：DocRank Team
> **日期**：2026-03-10

---

## 1. 概述

新增 `docrank-agent` Maven 模块，提供 `AgentService` 作为 RAG 问答入口。
内部流程：接收用户问题 → 从 KnowledgeBaseService 检索相关 Chunk → 拼装 Prompt（含历史对话）→ 调用 LLM → 返回结构化答案。
LLM 调用通过 `LlmProvider` 接口抽象，默认提供 Claude 和 OpenAI 两个实现，用 JDK 原生 HttpClient 实现，无额外 SDK 依赖。

---

## 2. 架构设计

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                   docrank-agent                         │
│                                                         │
│  AgentService                                           │
│    ├─ ConversationSessionManager  (会话历史管理)          │
│    ├─ KnowledgeBaseService        (检索，来自 docrank-memory)│
│    ├─ PromptBuilder               (Prompt 拼装)           │
│    └─ LlmProvider (接口)                                 │
│         ├─ ClaudeProvider         (Anthropic API)        │
│         └─ OpenAiProvider         (OpenAI / 兼容协议)     │
│                                                         │
│  AgentChatResult                                        │
│    ├─ answer: String                                     │
│    └─ sources: List<SearchResult>                        │
└─────────────────────────────────────────────────────────┘
         ↑ 调用
┌────────────────────┐    ┌──────────────────────────────┐
│  DocRankMcpServer  │    │  用户 Java 代码               │
│  POST /mcp/agent_chat   │  agentService.chat(sid, q)   │
└────────────────────┘    └──────────────────────────────┘
```

### 2.2 数据流

```
user question
    │
    ├─ ConversationSessionManager.getHistory(sessionId)
    │        └─ List<ConversationTurn>
    │
    ├─ KnowledgeBaseService.search(question, topK)
    │        └─ List<SearchResult>  (context chunks)
    │
    ├─ PromptBuilder.build(question, history, searchResults, systemPrompt)
    │        └─ List<LlmMessage>  [system, ...history, user]
    │
    ├─ LlmProvider.chat(messages)
    │        └─ String  (LLM answer)
    │
    ├─ ConversationSessionManager.addTurn(sessionId, question, answer)
    │
    └─ AgentChatResult {answer, sources}
```

---

## 3. 模块设计

### 3.1 新增 / 修改的模块

| 模块 | 变更类型 | 说明 |
|------|----------|------|
| `docrank-agent` | 新增 | 核心 Agent 模块 |
| `docrank-mcp` | 修改 | 新增 `agent_chat`、`agent_new_session`、`agent_clear_session` 端点 |
| `docrank-spring-boot-starter` | 修改 | 新增 AgentService、LlmProvider 自动配置 |
| `pom.xml` (parent) | 修改 | 新增 docrank-agent 子模块声明 |

### 3.2 核心接口

```java
// LLM 提供商抽象
public interface LlmProvider {
    String chat(List<LlmMessage> messages);
}

// 消息角色
public record LlmMessage(String role, String content) {
    // role: "system" | "user" | "assistant"
}

// Agent 主服务
public class AgentService {
    public AgentChatResult chat(String sessionId, String question);
    public void newSession(String sessionId);
    public void clearSession(String sessionId);
}

// 对话结果
public record AgentChatResult(String answer, List<SearchResult> sources) {}

// 单轮对话历史
public record ConversationTurn(String userMessage, String assistantMessage) {}
```

### 3.3 关键类设计

```
AgentService
  ├─ ConversationSessionManager
  │    └─ Map<sessionId, Deque<ConversationTurn>>  (内存，LRU可选)
  ├─ KnowledgeBaseService  (注入，来自 docrank-memory)
  ├─ PromptBuilder  (无状态，静态工具类)
  └─ LlmProvider  (注入，由 Spring 配置选择实现)
       ├─ ClaudeProvider
       │    └─ JDK HttpClient → api.anthropic.com/v1/messages
       └─ OpenAiProvider
            └─ JDK HttpClient → api.openai.com/v1/chat/completions
                               (或自定义 baseUrl)
```

---

## 4. 数据模型

### 4.1 核心数据类

```java
// 对话历史单元
public record ConversationTurn(String userMessage, String assistantMessage) {}

// LLM 消息（发送给 LLM API）
public record LlmMessage(String role, String content) {}

// Agent 返回结果
public record AgentChatResult(
    String answer,
    List<SearchResult> sources  // 检索到的知识片段
) {}
```

### 4.2 配置项（application.yml）

```yaml
docrank:
  agent:
    enabled: true
    context-top-k: 5           # 检索 chunk 数量
    max-history-turns: 10      # 保留的最大对话轮数
    system-prompt: |           # 可覆盖默认 System Prompt
      你是一个专业的知识库助手，请根据提供的上下文回答问题。
      如果上下文中没有相关信息，请诚实说明。
    llm:
      provider: claude         # claude | openai
      model: claude-sonnet-4-6 # 模型 ID
      api-key: ${ANTHROPIC_API_KEY:}
      base-url:                # openai 兼容模式可自定义，默认空
      max-tokens: 2048
      temperature: 0.7
```

---

## 5. API 设计

### 5.1 新增 MCP 端点

| 端点 | 方法 | 请求体 | 响应体 | 说明 |
|------|------|--------|--------|------|
| `/mcp/agent_chat` | POST | `{session_id?, question}` | `{success, result:{answer, sources}}` | RAG 问答，session_id 可选 |
| `/mcp/agent_new_session` | POST | `{session_id}` | `{success, result:{session_id}}` | 创建新会话 |
| `/mcp/agent_clear_session` | POST | `{session_id}` | `{success, result:{cleared}}` | 清空会话历史 |

### 5.2 请求/响应示例

```json
// POST /mcp/agent_chat 请求
{
  "session_id": "user-123",
  "question": "DocRank 支持哪些向量后端？"
}

// 响应
{
  "success": true,
  "result": {
    "answer": "DocRank 目前支持 LanceDB、Qdrant 和 pgvector 三种向量后端...",
    "sources": [
      {
        "chunk": {
          "id": "chunk-001",
          "docId": "doc-readme",
          "title": "README",
          "text": "DocRank 支持以下向量后端：LanceDB（默认）、Qdrant、pgvector..."
        },
        "score": 0.92
      }
    ]
  }
}
```

---

## 6. 技术决策

### 6.1 方案选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| JDK 原生 HttpClient | 零额外依赖，Java 11+ 标准库 | 需手动 JSON 序列化 | ✅ 采用 |
| OkHttp / RestTemplate | 更友好的 API | 增加依赖 | ❌ 放弃 |
| Anthropic Java SDK | 官方支持 | 绑定 Claude，且增加依赖 | ❌ 放弃 |
| 内存 Map 存会话 | 简单，零依赖 | 重启丢失，不支持多实例 | ✅ 采用（v0.4 够用） |

### 6.2 关键决策记录（ADR）

**决策**：LLM HTTP 请求使用 JDK 原生 HttpClient + Jackson 序列化
**理由**：保持 docrank-agent 依赖最小化，只引入 Jackson（项目已有）
**权衡**：代码量略多，但无隐式依赖冲突风险

**决策**：System Prompt 内置默认值，支持配置覆盖
**理由**：降低使用门槛，同时保留灵活性
**权衡**：默认 Prompt 可能不适合所有场景，通过配置解决

---

## 7. 测试策略

### 7.1 单元测试

- `PromptBuilderTest`：验证 Prompt 拼装格式（含历史、含 sources）
- `ConversationSessionManagerTest`：验证历史轮数截断、清空操作
- `ClaudeProvider` / `OpenAiProvider`：Mock HttpClient 验证请求格式

### 7.2 集成测试

- `AgentServiceIntegrationTest`：使用 InMemoryBackend + Mock LlmProvider，端到端验证 chat() 返回非空答案

### 7.3 性能测试

- 基准：chat() 耗时 = 检索耗时 + LLM 调用耗时
- 目标：Agent 自身 overhead < 50ms

---

## 8. 实现计划

| 阶段 | 任务 |
|------|------|
| Phase 1 | 创建 docrank-agent 模块，定义接口和数据模型 |
| Phase 2 | 实现 ConversationSessionManager、PromptBuilder |
| Phase 3 | 实现 ClaudeProvider、OpenAiProvider |
| Phase 4 | 实现 AgentService |
| Phase 5 | 修改 DocRankMcpServer 新增 3 个端点 |
| Phase 6 | 修改 DocRankAutoConfiguration 新增 Agent 自动配置 |
| Phase 7 | 修改 DocRankProperties 新增 agent 配置项 |
| Phase 8 | 单元测试 |

---

## 9. 风险与未解决问题

- [ ] 待确认：Claude API 的 `max_tokens` 参数名在不同版本是否一致
- [ ] 待确认：OpenAI 兼容模式的本地模型是否支持 `temperature` 参数
- [ ] 潜在风险：会话历史过长时 token 超限，需在 PromptBuilder 中做 token 估算截断（v0.5 处理）
