# DocRank

**[English](#english) · [中文](#中文) · [日本語](#日本語)**

---

<a name="english"></a>
# DocRank — Offline Multilingual Semantic Search for AI Agents

> Plug-and-play local knowledge base with hybrid retrieval (BM25 + Vector + Reranker).
> Zero cloud dependency. Chinese · Japanese · English, all offline.

## Features

- **Hybrid retrieval** — Lucene BM25 + LanceDB vector search fused with Reciprocal Rank Fusion (RRF)
- **Local AI inference** — BGE-M3 embedding + bge-reranker-v2-m3 reranker via ONNX Runtime (CPU / GPU)
- **Multilingual** — Chinese (HanLP), Japanese (Kuromoji), English (StandardAnalyzer), auto-detected
- **Multi-format ingest** — PDF, Markdown, HTML, DOCX, JSON, TXT
- **MCP-native** — Ships as an MCP (Model Context Protocol) HTTP server; AI agents discover tools automatically
- **Spring Boot Starter** — One `@Bean` away from integration; configurable via `application.yml`
- **Fully offline** — No OpenAI, no cloud vector DB, no data leaves your machine

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   AI Agent / LLM                     │
│                  MCP HTTP Client                     │
└─────────────────────┬────────────────────────────────┘
                      │  REST  /mcp/*
┌─────────────────────▼────────────────────────────────┐
│              DocRank MCP Server                      │
│  kb_search · kb_ingest · kb_ingest_file · kb_delete  │
└──────┬───────────────────────────────┬───────────────┘
       │                               │
┌──────▼──────┐               ┌────────▼────────┐
│  Lucene BM25│  ──── RRF ──▶ │ ONNX Reranker  │
│  (on disk)  │               │ bge-reranker-   │
└─────────────┘               │ v2-m3           │
┌─────────────┐               └────────▲────────┘
│  LanceDB    │  vectorSearch ─────────┘
│  (HTTP API) │
└─────────────┘
       ▲
┌──────┴───────────────────────────┐
│  Ingest Pipeline                 │
│  Parser → Chunker → BGE-M3 ONNX │
└──────────────────────────────────┘
```

## Quick Start

### Prerequisites

| Component | Version |
|-----------|---------|
| Java | 17+ |
| Maven | 3.6+ |
| LanceDB | latest (`pip install lancedb`) |
| BGE-M3 ONNX model | [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) |
| bge-reranker-v2-m3 ONNX | [BAAI/bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) |

### 1. Start LanceDB

```bash
pip install lancedb
lancedb --host 0.0.0.0 --port 8181
```

### 2. Download ONNX Models

```bash
# Install huggingface-hub CLI
pip install huggingface-hub

# Download BGE-M3 (embedding)
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-m3

# Download bge-reranker-v2-m3 (reranker)
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-reranker-v2-m3
```

Expected model directory layout:
```
/opt/docrank/models/
  bge-m3/
    model.onnx
    tokenizer.json
    tokenizer_config.json
  bge-reranker-v2-m3/
    model.onnx
    tokenizer.json
```

### 3. Add Dependency

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4. Configure `application.yml`

```yaml
docrank:
  backend:
    lancedb:
      host: localhost
      port: 8181
      table-name: my_knowledge_base
  embedding:
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
  chunk:
    size: 512
    overlap: 64
```

### 5. Use via MCP API

```bash
# Ingest a document
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title": "Spring Boot Guide", "content": "Spring Boot makes ...", "tags": ["java"]}'

# Search
curl -X POST http://localhost:8080/mcp/kb_search \
  -H "Content-Type: application/json" \
  -d '{"query": "how to configure Spring Boot", "top_k": 5}'

# Upload a file
curl -X POST http://localhost:8080/mcp/kb_ingest_file \
  -F "file=@document.pdf" \
  -F "tags=java,spring"
```

### 6. Use via Java API

```java
@Autowired
KnowledgeBaseService kb;

// Ingest
kb.ingestText("My Doc", "content here...", List.of("tag1"), Map.of());

// Search
List<SearchResult> results = kb.search("your query", 5, Map.of());
results.forEach(r -> System.out.println(r.getChunk().getChunkText()));
```

## MCP Tool Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp/tools` | GET | List all tools (agent auto-discovery) |
| `/mcp/kb_search` | POST | Hybrid search (BM25 + Vector + Reranker) |
| `/mcp/kb_ingest` | POST | Ingest plain text |
| `/mcp/kb_ingest_file` | POST | Upload file (PDF/MD/HTML/DOCX/TXT/JSON) |
| `/mcp/kb_delete` | POST | Delete document by ID |
| `/mcp/kb_stats` | GET | Index statistics |

## Modules

| Module | Description |
|--------|-------------|
| `docrank-core` | Core engine: parsing, chunking, embedding, BM25, vector, reranking |
| `docrank-memory` | High-level knowledge base service |
| `docrank-mcp` | MCP HTTP server (Spring REST Controller) |
| `docrank-spring-boot-starter` | Spring Boot auto-configuration |

## Build & Test

```bash
# Build all modules
mvn clean install -DskipTests

# Run tests (docrank-core, no model files required)
mvn test -pl docrank-core
```

## Configuration Reference

```yaml
docrank:
  backend.lancedb:
    host: localhost          # LanceDB host
    port: 8181               # LanceDB port
    table-name: docrank_memories
  embedding:
    dimension: 1024          # BGE-M3 output dimension
    batch-size: 32           # Embedding batch size
    onnx.model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    top-n: 20                # Candidates fed to reranker
    onnx.model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64
  chunk:
    size: 512                # CJK: chars, English: words
    overlap: 64
  language:
    default-lang: auto       # auto | zh | ja | en
```

## License

Apache 2.0

---

<a name="中文"></a>
# DocRank — 面向 AI Agent 的离线多语言语义搜索引擎

> 即插即用的本地知识库，混合检索（BM25 + 向量 + 重排序）。
> 零云依赖，完全离线，支持中文 · 日文 · 英文。

## 特性

- **混合检索** — Lucene BM25 与 LanceDB 向量检索并行召回，RRF 融合排序
- **本地 AI 推理** — BGE-M3 Embedding + bge-reranker-v2-m3 重排序，基于 ONNX Runtime 完全离线（支持 CPU / GPU）
- **多语言** — 中文（HanLP 分词）、日文（Kuromoji）、英文（StandardAnalyzer），自动语言检测
- **多格式写入** — 支持 PDF、Markdown、HTML、DOCX、JSON、TXT
- **MCP 原生** — 内置 MCP（Model Context Protocol）HTTP Server，AI Agent 可自动发现工具
- **Spring Boot Starter** — 一行配置即可集成，通过 `application.yml` 灵活配置
- **完全离线** — 无需 OpenAI、无需云向量库，数据不出本机

## 架构

```
┌────────────────────────────────────────────────────────┐
│                  AI Agent / LLM                        │
│               MCP HTTP 客户端                          │
└──────────────────────┬─────────────────────────────────┘
                       │  REST  /mcp/*
┌──────────────────────▼─────────────────────────────────┐
│              DocRank MCP Server                        │
│  kb_search · kb_ingest · kb_ingest_file · kb_delete    │
└──────┬────────────────────────────────┬────────────────┘
       │                                │
┌──────▼──────┐                ┌────────▼────────┐
│ Lucene BM25 │  ──── RRF ───▶ │  ONNX 重排序   │
│  （磁盘索引）│                │ bge-reranker-   │
└─────────────┘                │ v2-m3           │
┌─────────────┐                └────────▲────────┘
│  LanceDB    │  向量检索 ──────────────┘
│ （HTTP API）│
└─────────────┘
       ▲
┌──────┴──────────────────────────────┐
│  写入流水线                          │
│  解析器 → 分块 → BGE-M3 ONNX 向量化 │
└─────────────────────────────────────┘
```

## 快速开始

### 环境要求

| 组件 | 版本要求 |
|------|---------|
| Java | 17+ |
| Maven | 3.6+ |
| LanceDB | 最新版（`pip install lancedb`） |
| BGE-M3 ONNX 模型 | [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) |
| bge-reranker-v2-m3 ONNX | [BAAI/bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) |

### 1. 启动 LanceDB

```bash
pip install lancedb
lancedb --host 0.0.0.0 --port 8181
```

### 2. 下载 ONNX 模型

```bash
pip install huggingface-hub

# 下载 BGE-M3 Embedding 模型
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-m3

# 下载 bge-reranker-v2-m3 重排序模型
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-reranker-v2-m3
```

模型目录结构：
```
/opt/docrank/models/
  bge-m3/
    model.onnx
    tokenizer.json
    tokenizer_config.json
  bge-reranker-v2-m3/
    model.onnx
    tokenizer.json
```

### 3. 添加依赖

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4. 配置 `application.yml`

```yaml
docrank:
  backend:
    lancedb:
      host: localhost
      port: 8181
      table-name: my_knowledge_base
  embedding:
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
  chunk:
    size: 512    # 中日文按字符数，英文按词数
    overlap: 64
```

### 5. 通过 MCP API 使用

```bash
# 写入文本
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title": "Spring Boot 指南", "content": "Spring Boot 是...", "tags": ["java"]}'

# 混合语义搜索
curl -X POST http://localhost:8080/mcp/kb_search \
  -H "Content-Type: application/json" \
  -d '{"query": "如何配置 Spring Boot", "top_k": 5}'

# 上传文件
curl -X POST http://localhost:8080/mcp/kb_ingest_file \
  -F "file=@文档.pdf" \
  -F "tags=java,spring"
```

### 6. 通过 Java API 使用

```java
@Autowired
KnowledgeBaseService kb;

// 写入
kb.ingestText("我的文档", "内容...", List.of("tag1"), Map.of());

// 搜索
List<SearchResult> results = kb.search("你的查询", 5, Map.of());
results.forEach(r -> System.out.println(r.getChunk().getChunkText()));
```

## MCP 接口说明

| 接口 | 方法 | 说明 |
|------|------|------|
| `/mcp/tools` | GET | 工具清单（Agent 自动发现） |
| `/mcp/kb_search` | POST | 混合语义搜索（BM25 + 向量 + 重排序） |
| `/mcp/kb_ingest` | POST | 写入纯文本 |
| `/mcp/kb_ingest_file` | POST | 上传文件（PDF/MD/HTML/DOCX/TXT/JSON） |
| `/mcp/kb_delete` | POST | 按文档 ID 删除 |
| `/mcp/kb_stats` | GET | 索引状态统计 |

## 模块说明

| 模块 | 说明 |
|------|------|
| `docrank-core` | 核心引擎：解析、分块、向量化、BM25、向量检索、重排序 |
| `docrank-memory` | 高层知识库服务（KnowledgeBaseService） |
| `docrank-mcp` | MCP HTTP Server（Spring REST Controller） |
| `docrank-spring-boot-starter` | Spring Boot 自动配置 |

## 构建与测试

```bash
# 构建全部模块
mvn clean install -DskipTests

# 运行测试（docrank-core，无需模型文件）
mvn test -pl docrank-core
```

## 配置项说明

```yaml
docrank:
  backend.lancedb:
    host: localhost          # LanceDB 主机
    port: 8181               # LanceDB 端口
    table-name: docrank_memories
  embedding:
    dimension: 1024          # BGE-M3 输出维度
    batch-size: 32           # 向量化批次大小
    onnx.model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    top-n: 20                # 送入重排序的候选数
    onnx.model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64        # IndexWriter 内存缓冲区（MB）
  chunk:
    size: 512                # 中日文：字符数；英文：词数
    overlap: 64              # 滑窗重叠长度
  language:
    default-lang: auto       # auto | zh | ja | en
```

## 开源协议

Apache 2.0

---

<a name="日本語"></a>
# DocRank — AIエージェント向けオフライン多言語セマンティック検索エンジン

> ハイブリッド検索（BM25 + ベクトル + リランキング）によるローカル知識ベース。
> クラウド不要・完全オフライン。中国語・日本語・英語に対応。

## 特徴

- **ハイブリッド検索** — Lucene BM25 と LanceDB ベクトル検索を並列実行し、RRF（Reciprocal Rank Fusion）で統合
- **ローカルAI推論** — BGE-M3 Embedding + bge-reranker-v2-m3 リランカーを ONNX Runtime で完全オフライン実行（CPU / GPU 対応）
- **多言語対応** — 中国語（HanLP）、日本語（Kuromoji）、英語（StandardAnalyzer）、言語自動検出
- **マルチフォーマット取り込み** — PDF・Markdown・HTML・DOCX・JSON・TXT
- **MCP ネイティブ** — MCP（Model Context Protocol）HTTP サーバーとして動作。AIエージェントがツールを自動検出
- **Spring Boot Starter** — `application.yml` で設定するだけで即使用可能
- **完全オフライン** — OpenAI 不要・クラウドベクターDB 不要・データが外部に出ない

## アーキテクチャ

```
┌─────────────────────────────────────────────────────────┐
│               AI Agent / LLM                            │
│            MCP HTTP クライアント                         │
└──────────────────────┬──────────────────────────────────┘
                       │  REST  /mcp/*
┌──────────────────────▼──────────────────────────────────┐
│             DocRank MCP Server                          │
│  kb_search · kb_ingest · kb_ingest_file · kb_delete     │
└──────┬────────────────────────────────┬─────────────────┘
       │                                │
┌──────▼──────┐                ┌────────▼────────┐
│ Lucene BM25 │  ──── RRF ───▶ │ ONNX リランカー │
│  （ディスク）│                │ bge-reranker-   │
└─────────────┘                │ v2-m3           │
┌─────────────┐                └────────▲────────┘
│  LanceDB    │  ベクトル検索 ───────────┘
│ (HTTP API)  │
└─────────────┘
       ▲
┌──────┴──────────────────────────────────┐
│  取り込みパイプライン                    │
│  パーサー → チャンク分割 → BGE-M3 ONNX  │
└─────────────────────────────────────────┘
```

## クイックスタート

### 前提条件

| コンポーネント | バージョン |
|--------------|-----------|
| Java | 17以上 |
| Maven | 3.6以上 |
| LanceDB | 最新版（`pip install lancedb`） |
| BGE-M3 ONNX モデル | [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) |
| bge-reranker-v2-m3 ONNX | [BAAI/bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) |

### 1. LanceDB の起動

```bash
pip install lancedb
lancedb --host 0.0.0.0 --port 8181
```

### 2. ONNX モデルのダウンロード

```bash
pip install huggingface-hub

# BGE-M3 Embedding モデル
huggingface-cli download BAAI/bge-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-m3

# bge-reranker-v2-m3 リランキングモデル
huggingface-cli download BAAI/bge-reranker-v2-m3 --include "onnx/*" "tokenizer*" \
    --local-dir /opt/docrank/models/bge-reranker-v2-m3
```

モデルディレクトリ構成：
```
/opt/docrank/models/
  bge-m3/
    model.onnx
    tokenizer.json
    tokenizer_config.json
  bge-reranker-v2-m3/
    model.onnx
    tokenizer.json
```

### 3. 依存関係の追加

```xml
<dependency>
    <groupId>com.memo</groupId>
    <artifactId>docrank-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4. `application.yml` の設定

```yaml
docrank:
  backend:
    lancedb:
      host: localhost
      port: 8181
      table-name: my_knowledge_base
  embedding:
    onnx:
      model-path: /opt/docrank/models/bge-m3
  reranker:
    onnx:
      model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
  chunk:
    size: 512
    overlap: 64
```

### 5. MCP API の利用

```bash
# テキストの取り込み
curl -X POST http://localhost:8080/mcp/kb_ingest \
  -H "Content-Type: application/json" \
  -d '{"title": "Spring Bootガイド", "content": "Spring Bootは...", "tags": ["java"]}'

# ハイブリッド意味検索
curl -X POST http://localhost:8080/mcp/kb_search \
  -H "Content-Type: application/json" \
  -d '{"query": "Spring Bootの設定方法", "top_k": 5}'

# ファイルのアップロード
curl -X POST http://localhost:8080/mcp/kb_ingest_file \
  -F "file=@document.pdf" \
  -F "tags=java,spring"
```

### 6. Java API の利用

```java
@Autowired
KnowledgeBaseService kb;

// 取り込み
kb.ingestText("マイドキュメント", "内容...", List.of("tag1"), Map.of());

// 検索
List<SearchResult> results = kb.search("クエリ文字列", 5, Map.of());
results.forEach(r -> System.out.println(r.getChunk().getChunkText()));
```

## MCP ツール一覧

| エンドポイント | メソッド | 説明 |
|--------------|---------|------|
| `/mcp/tools` | GET | ツール一覧（エージェント自動検出用） |
| `/mcp/kb_search` | POST | ハイブリッド検索（BM25 + ベクトル + リランク） |
| `/mcp/kb_ingest` | POST | テキスト取り込み |
| `/mcp/kb_ingest_file` | POST | ファイルアップロード（PDF/MD/HTML/DOCX/TXT/JSON） |
| `/mcp/kb_delete` | POST | ドキュメントIDで削除 |
| `/mcp/kb_stats` | GET | インデックス統計情報 |

## モジュール構成

| モジュール | 説明 |
|-----------|------|
| `docrank-core` | コアエンジン：パース・チャンク分割・Embedding・BM25・ベクトル検索・リランク |
| `docrank-memory` | ハイレベル知識ベースサービス（KnowledgeBaseService） |
| `docrank-mcp` | MCP HTTP サーバー（Spring REST Controller） |
| `docrank-spring-boot-starter` | Spring Boot 自動設定 |

## ビルドとテスト

```bash
# 全モジュールのビルド
mvn clean install -DskipTests

# テスト実行（モデルファイル不要）
mvn test -pl docrank-core
```

## 設定リファレンス

```yaml
docrank:
  backend.lancedb:
    host: localhost          # LanceDB ホスト
    port: 8181               # LanceDB ポート
    table-name: docrank_memories
  embedding:
    dimension: 1024          # BGE-M3 出力次元数
    batch-size: 32           # Embedding バッチサイズ
    onnx.model-path: /opt/docrank/models/bge-m3
  reranker:
    enabled: true
    top-n: 20                # リランカーに渡す候補数
    onnx.model-path: /opt/docrank/models/bge-reranker-v2-m3
  lucene:
    index-path: /opt/docrank/data/lucene-index
    ram-buffer-mb: 64        # IndexWriter メモリバッファ（MB）
  chunk:
    size: 512                # CJK：文字数、英語：単語数
    overlap: 64              # スライディングウィンドウ重複長
  language:
    default-lang: auto       # auto | zh | ja | en
```

## ライセンス

Apache 2.0
