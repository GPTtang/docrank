# DocRank

**[English](#english) · [中文](#中文) · [日本語](#日本語)**

---

<a name="english"></a>
# DocRank — Offline Multilingual RAG Framework for AI Agents

> Plug-and-play local knowledge base with hybrid retrieval (BM25 + Vector + Reranker) and built-in AI Agent.
> Zero cloud dependency. Chinese · Japanese · English, all offline.

## Features

- **Hybrid retrieval** — Lucene BM25 + vector search fused with Reciprocal Rank Fusion (RRF)
- **Built-in AI Agent** — `AgentService.chat(sessionId, question)` completes the full RAG loop (retrieve → prompt → LLM); supports Claude and OpenAI
- **Multi-turn conversation** — Session history management with configurable max turns
- **Local AI inference** — BGE-M3 embedding + bge-reranker-v2-m3 reranker via ONNX Runtime (CPU / GPU)
- **Multilingual** — Chinese (HanLP), Japanese (Kuromoji), English (StandardAnalyzer), auto-detected
- **Multi-format ingest** — PDF, Markdown, HTML, DOCX, JSON, TXT, XLSX, PPTX, EPUB, CSV
- **MCP-native** — Ships as an MCP HTTP server; AI agents discover 9 tools automatically
- **Swagger UI** — Interactive API docs at `/swagger-ui/index.html`
- **Spring Boot Starter** — One dependency away from integration; configurable via `application.yml`
- **Fully offline** — No cloud vector DB, no data leaves your machine

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    AI Agent / LLM                        │
│                   MCP HTTP Client                        │
└──────────────────────┬───────────────────────────────────┘
                       │  REST  /mcp/*
┌──────────────────────▼───────────────────────────────────┐
│                DocRank MCP Server                        │
│  kb_search · kb_ingest · kb_ingest_file · kb_delete      │
│  agent_chat · agent_new_session · agent_clear_session    │
└──────┬───────────────────────────────┬────────────────────┘
       │                               │
┌──────▼──────────────────────────┐   │
│         docrank-agent           │   │
│  AgentService                   │   │
│    ├─ KnowledgeBaseService      │◀──┘
│    ├─ ConversationSession       │
│    ├─ PromptBuilder             │
│    └─ LlmProvider               │
│         ├─ ClaudeProvider       │
│         └─ OpenAiProvider       │
└──────┬──────────────────────────┘
       │ search
┌──────▼──────┐               ┌─────────────────┐
│ Lucene BM25 │  ── RRF ────▶ │  ONNX Reranker  │
│  (on disk)  │               │ bge-reranker-v2  │
└─────────────┘               └────────▲────────┘
┌─────────────┐                        │
│  LanceDB /  │  vectorSearch ─────────┘
│  Qdrant /   │
│  pgvector   │
└─────────────┘
       ▲
┌──────┴───────────────────────────┐
│  Ingest Pipeline                 │
│  Parser → Chunker → BGE-M3 ONNX │
└──────────────────────────────────┘
```

## Quick Start

### Option A — Docker (no model files required)

```bash
# Clone
git clone https://github.com/GPTtang/docrank && cd docrank

# Set your LLM API key (Claude or OpenAI)
export ANTHROPIC_API_KEY=sk-ant-xxx

# Start (InMemory backend + lightweight embedding, ready in ~5s)
docker compose up -d

# Swagger UI
open http://localhost:8080/swagger-ui/index.html
```

Ingest a document and ask a question:

```bash
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title":"My Doc","content":"DocRank supports LanceDB, Qdrant, pgvector backends."}'

curl -X POST http://localhost:8080/mcp/agent_chat \
  -H "Content-Type: application/json" \
  -d '{"question":"What backends are supported?","session_id":"s1"}'
```

### Option B — Production Docker (LanceDB + ONNX models)

```bash
# Download ONNX models first (see "ONNX Models" section below)
export ANTHROPIC_API_KEY=sk-ant-xxx
docker compose -f docker-compose.prod.yml up -d
```

### Option C — Spring Boot Starter

**1. Add dependency**

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**2. Configure `application.yml`**

```yaml
docrank:
  backend:
    type: lancedb           # lancedb | qdrant | pgvector | memory
    lancedb:
      host: localhost
      port: 8181
  embedding:
    type: onnx              # onnx | random (random = no model files, demo only)
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
  agent:
    enabled: true
    llm:
      provider: claude      # claude | openai
      model: claude-sonnet-4-6
      api-key: ${ANTHROPIC_API_KEY}
```

**3. Use via Java API**

```java
// Knowledge base
@Autowired KnowledgeBaseService kb;
kb.ingestText("My Doc", "content...", List.of("tag1"), Map.of());
List<SearchResult> results = kb.search("your query", 5, Map.of());

// AI Agent (RAG Q&A)
@Autowired AgentService agent;
AgentChatResult result = agent.chat("session-1", "What is DocRank?");
System.out.println(result.answer());
result.sources().forEach(s -> System.out.println(s.getChunk().getTitle()));
```

## ONNX Models

```bash
pip install huggingface-hub

# BGE-M3 (embedding, 1024-dim)
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-m3

# bge-reranker-v2-m3 (reranker)
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-reranker-v2-m3
```

```
models/
  bge-m3/
    model.onnx
    tokenizer.json
  bge-reranker-v2-m3/
    model.onnx
    tokenizer.json
```

## API Reference

### Swagger UI

```
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

### MCP Endpoints

**Knowledge Base**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp/tools` | GET | List all tools (agent auto-discovery) |
| `/mcp/kb_search` | POST | Hybrid search (BM25 + Vector + Reranker) |
| `/mcp/kb_ingest` | POST | Ingest plain text |
| `/mcp/kb_ingest_file` | POST | Upload file (PDF/MD/HTML/DOCX/TXT/JSON/XLSX/PPTX/EPUB/CSV) |
| `/mcp/kb_delete` | POST | Delete document by ID |
| `/mcp/kb_stats` | GET | Index statistics |
| `/mcp/kb_reembed` | POST | Re-vectorize all chunks (after model upgrade) |

**AI Agent**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp/agent_chat` | POST | RAG Q&A with session history |
| `/mcp/agent_new_session` | POST | Create a new conversation session |
| `/mcp/agent_clear_session` | POST | Clear session history |

## Modules

| Module | Description |
|--------|-------------|
| `docrank-core` | Core engine: parsing, chunking, embedding, BM25, vector, reranking |
| `docrank-memory` | High-level knowledge base service (`KnowledgeBaseService`) |
| `docrank-agent` | AI Agent: RAG Q&A, LLM providers, session management |
| `docrank-mcp` | MCP HTTP server + Swagger UI |
| `docrank-spring-boot-starter` | Spring Boot auto-configuration |
| `docrank-langchain4j` | LangChain4j adapter |
| `docrank-spring-ai` | Spring AI adapter |
| `docrank-eval` | Retrieval evaluation (NDCG, MRR, MAP) |

## Docker Compose

| File | Containers | Use Case |
|------|-----------|----------|
| `docker-compose.yml` | 1 (docrank) | Quick start, no model files needed |
| `docker-compose.prod.yml` | 2 (docrank + lancedb) | Production, persistent storage |

## Full Configuration Reference

```yaml
docrank:
  backend:
    type: lancedb            # lancedb | qdrant | pgvector | memory
    lancedb:
      host: localhost
      port: 8181
      table-name: docrank_memories
    qdrant:
      host: localhost
      port: 6333
      collection-name: docrank_memories
    pgvector:
      jdbc-url: jdbc:postgresql://localhost:5432/docrank
      username: postgres
      password: ""
  embedding:
    type: onnx               # onnx | random
    dimension: 1024
    batch-size: 32
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    top-n: 20
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64
  chunk:
    size: 512                # CJK: chars, English: words
    overlap: 64
  language:
    default-lang: auto       # auto | zh | ja | en
  scoring:
    recency-lambda: 0.005
    min-score: 0.0
    mmr-enabled: true
    mmr-penalty: 0.85
  ingest:
    dedup-enabled: false
    dedup-threshold: 0.95
  agent:
    enabled: false
    context-top-k: 5
    max-history-turns: 10
    system-prompt: ""        # leave empty for built-in default
    llm:
      provider: claude       # claude | openai
      model: claude-sonnet-4-6
      api-key: ${ANTHROPIC_API_KEY:}
      base-url: ""           # custom base URL for OpenAI-compatible local models
      max-tokens: 2048
      temperature: 0.7
```

## Build & Test

```bash
mvn clean install -DskipTests
mvn test -pl docrank-core,docrank-agent
```

## License

Apache 2.0

---

<a name="中文"></a>
# DocRank — 面向 AI Agent 的离线多语言 RAG 框架

> 即插即用的本地知识库，混合检索（BM25 + 向量 + 重排序）+ 内置 AI Agent 问答。
> 零云依赖，完全离线，支持中文 · 日文 · 英文。

## 特性

- **混合检索** — Lucene BM25 与向量检索并行召回，RRF 融合排序
- **内置 AI Agent** — `AgentService.chat(sessionId, question)` 完成完整 RAG 闭环（检索 → Prompt → LLM 生成），支持 Claude 和 OpenAI
- **多轮对话** — 会话历史管理，可配置最大轮数
- **本地 AI 推理** — BGE-M3 Embedding + bge-reranker-v2-m3 重排序，基于 ONNX Runtime 完全离线（支持 CPU / GPU）
- **多语言** — 中文（HanLP 分词）、日文（Kuromoji）、英文（StandardAnalyzer），自动语言检测
- **多格式写入** — PDF、Markdown、HTML、DOCX、JSON、TXT、XLSX、PPTX、EPUB、CSV
- **MCP 原生** — 内置 MCP HTTP Server，AI Agent 可自动发现 9 个工具
- **Swagger UI** — 交互式接口文档，访问 `/swagger-ui/index.html`
- **Spring Boot Starter** — 一行依赖即可集成，通过 `application.yml` 灵活配置
- **完全离线** — 无需云向量库，数据不出本机

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                    AI Agent / LLM                        │
│                   MCP HTTP 客户端                         │
└──────────────────────┬───────────────────────────────────┘
                       │  REST  /mcp/*
┌──────────────────────▼───────────────────────────────────┐
│                DocRank MCP Server                        │
│  kb_search · kb_ingest · kb_ingest_file · kb_delete      │
│  agent_chat · agent_new_session · agent_clear_session    │
└──────┬───────────────────────────────┬────────────────────┘
       │                               │
┌──────▼──────────────────────────┐   │
│         docrank-agent           │   │
│  AgentService                   │   │
│    ├─ KnowledgeBaseService      │◀──┘
│    ├─ ConversationSession       │
│    ├─ PromptBuilder             │
│    └─ LlmProvider               │
│         ├─ ClaudeProvider       │
│         └─ OpenAiProvider       │
└──────┬──────────────────────────┘
       │ 检索
┌──────▼──────┐               ┌─────────────────┐
│ Lucene BM25 │  ── RRF ────▶ │  ONNX 重排序    │
│  （磁盘索引）│               │ bge-reranker-v2  │
└─────────────┘               └────────▲────────┘
┌─────────────┐                        │
│  LanceDB /  │  向量检索 ─────────────┘
│  Qdrant /   │
│  pgvector   │
└─────────────┘
       ▲
┌──────┴──────────────────────────────┐
│  写入流水线                          │
│  解析器 → 分块 → BGE-M3 ONNX 向量化 │
└─────────────────────────────────────┘
```

## 快速开始

### 方式 A — Docker（无需模型文件）

```bash
git clone https://github.com/GPTtang/docrank && cd docrank

# 设置 LLM API Key（Claude 或 OpenAI）
export ANTHROPIC_API_KEY=sk-ant-xxx

# 启动（InMemory 后端 + 轻量 Embedding，约 5 秒就绪）
docker compose up -d

# 访问 Swagger UI
open http://localhost:8080/swagger-ui/index.html
```

写入文档并提问：

```bash
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title":"介绍","content":"DocRank 支持 LanceDB、Qdrant、pgvector 三种向量后端。"}'

curl -X POST http://localhost:8080/mcp/agent_chat \
  -H "Content-Type: application/json" \
  -d '{"question":"支持哪些向量后端？","session_id":"s1"}'
```

### 方式 B — 生产 Docker（LanceDB + ONNX 模型）

```bash
# 先下载 ONNX 模型（见下方"ONNX 模型"章节）
export ANTHROPIC_API_KEY=sk-ant-xxx
docker compose -f docker-compose.prod.yml up -d
```

### 方式 C — Spring Boot Starter

**1. 添加依赖**

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**2. 配置 `application.yml`**

```yaml
docrank:
  backend:
    type: lancedb           # lancedb | qdrant | pgvector | memory
    lancedb:
      host: localhost
      port: 8181
  embedding:
    type: onnx              # onnx | random（random = 无需模型文件，仅演示）
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
  agent:
    enabled: true
    llm:
      provider: claude      # claude | openai
      model: claude-sonnet-4-6
      api-key: ${ANTHROPIC_API_KEY}
```

**3. Java API 使用**

```java
// 知识库操作
@Autowired KnowledgeBaseService kb;
kb.ingestText("文档标题", "内容...", List.of("tag1"), Map.of());
List<SearchResult> results = kb.search("查询语句", 5, Map.of());

// AI Agent 问答
@Autowired AgentService agent;
AgentChatResult result = agent.chat("session-1", "DocRank 是什么？");
System.out.println(result.answer());
result.sources().forEach(s -> System.out.println(s.getChunk().getTitle()));
```

## ONNX 模型下载

```bash
pip install huggingface-hub

# BGE-M3（向量化，1024 维）
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-m3

# bge-reranker-v2-m3（重排序）
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir ./models/bge-reranker-v2-m3
```

## API 文档

### Swagger UI

```
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

### MCP 接口说明

**知识库**

| 接口 | 方法 | 说明 |
|------|------|------|
| `/mcp/tools` | GET | 工具清单（Agent 自动发现） |
| `/mcp/kb_search` | POST | 混合语义搜索（BM25 + 向量 + 重排序） |
| `/mcp/kb_ingest` | POST | 写入纯文本 |
| `/mcp/kb_ingest_file` | POST | 上传文件（PDF/MD/HTML/DOCX/TXT/JSON/XLSX/PPTX/EPUB/CSV） |
| `/mcp/kb_delete` | POST | 按文档 ID 删除 |
| `/mcp/kb_stats` | GET | 索引状态统计 |
| `/mcp/kb_reembed` | POST | 重新向量化（升级模型后使用） |

**AI Agent**

| 接口 | 方法 | 说明 |
|------|------|------|
| `/mcp/agent_chat` | POST | RAG 问答（含会话历史） |
| `/mcp/agent_new_session` | POST | 创建新会话 |
| `/mcp/agent_clear_session` | POST | 清空会话历史 |

## 模块说明

| 模块 | 说明 |
|------|------|
| `docrank-core` | 核心引擎：解析、分块、向量化、BM25、向量检索、重排序 |
| `docrank-memory` | 高层知识库服务（`KnowledgeBaseService`） |
| `docrank-agent` | AI Agent：RAG 问答、LLM 提供商、会话管理 |
| `docrank-mcp` | MCP HTTP Server + Swagger UI |
| `docrank-spring-boot-starter` | Spring Boot 自动配置 |
| `docrank-langchain4j` | LangChain4j 适配器 |
| `docrank-spring-ai` | Spring AI 适配器 |
| `docrank-eval` | 检索质量评估（NDCG、MRR、MAP） |

## Docker Compose 说明

| 文件 | 容器数 | 适用场景 |
|------|--------|---------|
| `docker-compose.yml` | 1（docrank） | 快速体验，无需模型文件 |
| `docker-compose.prod.yml` | 2（docrank + lancedb） | 生产部署，持久化存储 |

## 完整配置项

```yaml
docrank:
  backend:
    type: lancedb            # lancedb | qdrant | pgvector | memory
    lancedb:
      host: localhost
      port: 8181
      table-name: docrank_memories
  embedding:
    type: onnx               # onnx | random
    dimension: 1024
    batch-size: 32
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    top-n: 20
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64
  chunk:
    size: 512                # 中日文：字符数；英文：词数
    overlap: 64
  language:
    default-lang: auto       # auto | zh | ja | en
  scoring:
    recency-lambda: 0.005
    min-score: 0.0
    mmr-enabled: true
    mmr-penalty: 0.85
  ingest:
    dedup-enabled: false
    dedup-threshold: 0.95
  agent:
    enabled: false
    context-top-k: 5
    max-history-turns: 10
    system-prompt: ""        # 留空使用内置默认值
    llm:
      provider: claude       # claude | openai
      model: claude-sonnet-4-6
      api-key: ${ANTHROPIC_API_KEY:}
      base-url: ""           # 兼容 OpenAI 协议的本地模型（Ollama 等）可自定义
      max-tokens: 2048
      temperature: 0.7
```

## 构建与测试

```bash
mvn clean install -DskipTests
mvn test -pl docrank-core,docrank-agent
```

## 开源协议

Apache 2.0

---

<a name="日本語"></a>
# DocRank — AIエージェント向けオフライン多言語RAGフレームワーク

> ハイブリッド検索（BM25 + ベクトル + リランク）+ 内蔵AIエージェントによるローカル知識ベース。
> クラウド不要・完全オフライン。中国語・日本語・英語に対応。

## 特徴

- **ハイブリッド検索** — Lucene BM25 + ベクトル検索を並列実行し、RRF で統合
- **内蔵AIエージェント** — `AgentService.chat(sessionId, question)` でRAGの全工程を完結（検索 → Prompt → LLM生成）。Claude / OpenAI 対応
- **マルチターン会話** — セッション履歴管理（最大ターン数設定可能）
- **ローカルAI推論** — BGE-M3 + bge-reranker-v2-m3 を ONNX Runtime で完全オフライン実行（CPU / GPU 対応）
- **多言語対応** — 中国語（HanLP）、日本語（Kuromoji）、英語（StandardAnalyzer）、言語自動検出
- **マルチフォーマット** — PDF・Markdown・HTML・DOCX・JSON・TXT・XLSX・PPTX・EPUB・CSV
- **MCP ネイティブ** — MCP HTTP サーバーとして動作、AIエージェントが9つのツールを自動検出
- **Swagger UI** — `/swagger-ui/index.html` でインタラクティブなAPIドキュメント
- **Spring Boot Starter** — 依存関係を1つ追加するだけで統合完了

## クイックスタート

### 方法 A — Docker（モデルファイル不要）

```bash
git clone https://github.com/GPTtang/docrank && cd docrank

export ANTHROPIC_API_KEY=sk-ant-xxx

docker compose up -d

open http://localhost:8080/swagger-ui/index.html
```

```bash
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title":"DocRank","content":"DocRank supports LanceDB, Qdrant, pgvector."}'

curl -X POST http://localhost:8080/mcp/agent_chat \
  -H "Content-Type: application/json" \
  -d '{"question":"What backends are supported?","session_id":"s1"}'
```

### 方法 B — 本番 Docker（LanceDB + ONNXモデル）

```bash
export ANTHROPIC_API_KEY=sk-ant-xxx
docker compose -f docker-compose.prod.yml up -d
```

### 方法 C — Spring Boot Starter

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
@Autowired AgentService agent;
AgentChatResult result = agent.chat("session-1", "DocRankとは何ですか？");
System.out.println(result.answer());
```

## MCP ツール一覧

**知識ベース**

| エンドポイント | メソッド | 説明 |
|--------------|---------|------|
| `/mcp/kb_search` | POST | ハイブリッド検索（BM25 + ベクトル + リランク） |
| `/mcp/kb_ingest` | POST | テキスト取り込み |
| `/mcp/kb_ingest_file` | POST | ファイルアップロード（PDF/MD/HTML/DOCX 他） |
| `/mcp/kb_delete` | POST | ドキュメント削除 |
| `/mcp/kb_stats` | GET | インデックス統計 |
| `/mcp/kb_reembed` | POST | 全チャンク再ベクトル化 |

**AIエージェント**

| エンドポイント | メソッド | 説明 |
|--------------|---------|------|
| `/mcp/agent_chat` | POST | RAG 問答（会話履歴付き） |
| `/mcp/agent_new_session` | POST | 新しいセッションを作成 |
| `/mcp/agent_clear_session` | POST | セッション履歴をクリア |

## モジュール構成

| モジュール | 説明 |
|-----------|------|
| `docrank-core` | コアエンジン：パース・チャンク・Embedding・BM25・ベクトル検索・リランク |
| `docrank-memory` | 高レベル知識ベースサービス |
| `docrank-agent` | AIエージェント：RAG問答・LLMプロバイダー・セッション管理 |
| `docrank-mcp` | MCP HTTP サーバー + Swagger UI |
| `docrank-spring-boot-starter` | Spring Boot 自動設定 |

## ライセンス

Apache 2.0
