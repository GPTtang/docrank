# PRD: AI Agent 模块

> **状态**：已批准
> **版本**：v0.4
> **作者**：DocRank Team
> **日期**：2026-03-10

---

## 1. 背景与目标

### 1.1 问题陈述

DocRank 目前提供了优秀的混合检索能力，但用户需要自行集成 LLM 来完成"检索-生成"的完整 RAG 闭环。
现有问题：
- 用户必须在 DocRank 外部手工拼接 Prompt + 检索结果
- 没有内置的多轮对话管理（会话历史）
- MCP Server 只暴露了检索工具，没有问答工具
- AI Agent 开发者无法直接用 DocRank 构建对话式问答应用

### 1.2 目标

新增 `docrank-agent` 模块，提供开箱即用的 RAG 问答 Agent：
- **一行调用**即可完成"检索 + LLM 生成"完整链路
- 支持多轮会话（ConversationSession）
- 支持多 LLM 提供商（Claude、OpenAI、兼容 OpenAI 协议的本地模型）
- 在 MCP Server 中新增 `agent_chat` 工具，供 AI Agent 调用

### 1.3 非目标（Out of Scope）

- 不做自主规划（Autonomous Planning）和工具调用（Tool Use）Agent
- 不做流式输出（Streaming）
- 不做多租户会话隔离
- 不内置 LLM 模型文件（只做 HTTP API 调用）

---

## 2. 用户故事

```
作为 Java 开发者，
我希望通过 AgentService.chat(sessionId, question) 直接得到基于知识库的回答，
以便不再手工拼接 Prompt 和检索结果。

作为 AI Agent 开发者（使用 MCP 协议），
我希望通过 POST /mcp/agent_chat 调用对话接口，
以便将 DocRank 知识库接入我的 Agent 工作流。

作为应用开发者，
我希望支持多轮对话并保留历史上下文，
以便实现连续问答场景（如客服 Bot、文档助手）。
```

---

## 3. 功能需求

### 3.1 核心功能（P0）

- [x] `AgentService.chat(sessionId, question)` — 单方法完成 RAG 问答
- [x] `LlmProvider` 抽象接口，支持插件化 LLM 后端
- [x] `ClaudeProvider` — Anthropic Claude API 实现
- [x] `OpenAiProvider` — OpenAI / 兼容 OpenAI 协议实现
- [x] `ConversationSession` — 会话历史管理（内存存储，可配置最大轮数）
- [x] MCP 新增 `agent_chat` 端点（POST `/mcp/agent_chat`）
- [x] Spring Boot 自动配置集成

### 3.2 扩展功能（P1/P2）

- [ ] `agent_new_session` / `agent_clear_session` MCP 端点（P1）
- [ ] 自定义 System Prompt 支持（P1）
- [ ] Prompt 模板配置（P2）
- [ ] 会话持久化（Redis / 数据库）（P2）

---

## 4. 非功能需求

| 维度 | 要求 |
|------|------|
| 性能 | chat() 端到端延迟 ≤ LLM 延迟 + 检索延迟，不额外引入超过 50ms 开销 |
| 可靠性 | LLM 调用失败时返回明确错误信息，不崩溃 |
| 安全性 | API Key 通过配置属性注入，不硬编码 |
| 兼容性 | Java 17+，不依赖特定 LLM SDK，仅用 JDK HttpClient |

---

## 5. 验收标准（AC）

- [ ] AC1：调用 `AgentService.chat("s1", "什么是BM25？")` 返回非空的包含知识库内容的回答
- [ ] AC2：同一 sessionId 多次调用，后续问题可感知前面的对话历史
- [ ] AC3：POST `/mcp/agent_chat` 返回 `{success: true, result: {answer: "...", sources: [...]}}`
- [ ] AC4：LLM API Key 未配置时，启动时打印 WARN 日志，调用时返回明确错误
- [ ] AC5：切换 `docrank.agent.llm.provider: openai` 后，使用 OpenAI 协议调用

---

## 6. 依赖与风险

### 依赖

- `docrank-memory`（KnowledgeBaseService）
- `docrank-mcp`（新增端点）
- Anthropic / OpenAI 外部 HTTP API

### 风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| LLM API 变更 | 低 | 高 | LlmProvider 接口隔离，易替换 |
| 网络不稳定 | 中 | 中 | 超时配置 + 明确错误信息 |
| 会话内存占用 | 低 | 低 | 配置 max-history-turns 自动截断 |

---

## 7. 参考资料

- ROADMAP.md Phase 2（Web UI + LangChain4j/Spring AI 适配）
- Anthropic Claude API 文档
- OpenAI Chat Completions API 文档
