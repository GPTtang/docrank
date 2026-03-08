# DocRank Roadmap

**[English](#english) · [中文](#中文) · [日本語](#日本語)**

---

<a name="english"></a>
# Roadmap (English)

## Vision

**DocRank aims to be the best JVM-native offline multilingual RAG framework for AI Agents.**

While tools like *memory-lancedb-pro* require a cloud embedding API, lock you into a single runtime, and offer no visualization, DocRank takes the opposite approach:

- **Truly offline** — BGE-M3 + bge-reranker-v2-m3 run locally via ONNX Runtime. No API key, no network call, no data leakage.
- **CJK-first** — HanLP (Chinese) + Kuromoji (Japanese) built in, not bolted on.
- **JVM-native** — A first-class Spring Boot Starter and Java SDK, not a TypeScript plugin.
- **Both library and service** — Embed it in your app, or run it as a standalone MCP server.
- **Web UI** — Visual knowledge base management that competitors don't provide.

---

## Current State (v0.1 — Completed)

- [x] Core retrieval pipeline: Parser → Chunker → BGE-M3 Embed → Lucene BM25 + LanceDB → RRF → ONNX Reranker
- [x] 6 document parsers: PDF, Markdown, HTML, DOCX, JSON, TXT
- [x] MCP HTTP Server with 5 tools
- [x] Spring Boot Starter + `application.yml` configuration
- [x] 76 unit tests (zero model files required)
- [x] Multilingual: Chinese (HanLP) · Japanese (Kuromoji) · English (StandardAnalyzer)

---

## Phase 1 — Feature Parity & Beyond (v0.2 ~ v0.5)

> Goal: Match and surpass memory-lancedb-pro on core retrieval quality,
> while adding production-grade operability.

### 1.1 Multiple Vector Backends

| Backend | Status | Notes |
|---------|--------|-------|
| LanceDB (HTTP) | ✅ Done | Default |
| Qdrant | 🔲 Planned | REST API, cloud-ready |
| pgvector | 🔲 Planned | PostgreSQL extension |
| In-Memory | 🔲 Planned | Testing / small datasets |

All backends implement the same `IndexBackend` interface — swap with one config line.

### 1.2 Advanced Scoring Pipeline

Currently DocRank uses RRF + reranker score. Phase 1 adds:

- **Recency Boost** — newer documents score higher (configurable decay rate)
- **Importance Weight** — user-assignable priority per document
- **Time Decay** — exponential decay on `updated_at`
- **MMR Diversity** — Maximal Marginal Relevance to reduce redundant results
- **Hard Score Threshold** — filter out low-confidence results before returning

### 1.3 Multi-Scope Isolation

Partition the knowledge base by scope — essential for multi-agent systems:

```yaml
# Supported scopes
global            # shared across all agents
agent:<agent-id>  # per-agent private memory
user:<user-id>    # per-user knowledge
project:<id>      # project-level documents
```

Search and ingest operations are automatically scoped; data never leaks across boundaries.

### 1.4 Memory Management

- **Deduplication** — cosine similarity check before ingest; skip or merge near-duplicates
- **Re-embedding** — re-vectorize all documents after model upgrade (`/mcp/kb_reembed`)
- **GDPR Delete** — delete by filter expression, not just by document ID
- **TTL (Time-to-Live)** — auto-expire ephemeral memories

### 1.5 CLI Tools

```bash
docrank list   [--scope agent:001] [--tags java]
docrank search "query text" [--top-k 10]
docrank export --output backup.jsonl
docrank import --file backup.jsonl
docrank reembed --model /path/to/new-model
docrank stats
docrank delete --filter "updated_at < 2024-01-01"
```

### 1.6 Docker & Deployment

```bash
# Standalone MCP server (Docker)
docker run -p 8080:8080 \
  -v /opt/docrank/models:/models \
  -v /opt/docrank/data:/data \
  memo/docrank:latest

# docker-compose with LanceDB
docker compose up -d
```

- Official `Dockerfile` + `docker-compose.yml`
- Health check endpoint
- Graceful shutdown

### 1.7 Additional Parsers

| Format | Status |
|--------|--------|
| Excel (.xlsx) | 🔲 Planned |
| PowerPoint (.pptx) | 🔲 Planned |
| EPUB | 🔲 Planned |
| CSV | 🔲 Planned |

---

## Phase 2 — Web Management UI & Ecosystem (v0.6 ~ v0.9)

> Goal: Make DocRank accessible to non-developers.
> Provide the visualization layer that memory-lancedb-pro completely lacks.

### 2.1 Web Admin UI

A React-based management dashboard shipped as a static bundle inside the Spring Boot jar.

**Knowledge Base Panel**
- Document list with title, tags, chunk count, language, ingest date
- Full-text search within the dashboard
- One-click delete, re-embed, export

**Search Playground**
- Type a query and see BM25 / vector / hybrid results side by side
- Score breakdown: sparse score · vector score · rerank score · final score
- Chunk viewer with highlighted matches

**Statistics Dashboard**
- Total documents / chunks / languages
- Storage size (LanceDB + Lucene)
- Ingest rate over time (chart)
- Top queried terms

**Model Panel**
- Currently loaded embedding & reranker model info
- Dimension, quantization, inference time per request

### 2.2 LangChain4j Adapter

```java
// Use DocRank as a LangChain4j EmbeddingStore
EmbeddingStore<TextSegment> store = DocRankEmbeddingStore.builder()
        .baseUrl("http://localhost:8080")
        .build();
```

### 2.3 Spring AI Adapter

```java
// Use DocRank as a Spring AI VectorStore
VectorStore vectorStore = new DocRankVectorStore(kb);
```

### 2.4 Evaluation Module

Measure retrieval quality without manual inspection:

```bash
# Run evaluation against a labeled dataset
docrank eval --dataset eval_set.jsonl --metrics ndcg@5,recall@10,mrr
```

Metrics: NDCG@K · Recall@K · MRR · MAP · Latency (p50/p95/p99)

### 2.5 Streaming Search (SSE)

```bash
curl -N http://localhost:8080/mcp/kb_search_stream \
  -d '{"query": "...", "top_k": 10}'
# → chunked Server-Sent Events, one result per event
```

### 2.6 Maven Central Release

- Publish `docrank-spring-boot-starter` to Maven Central
- Semantic versioning + GitHub Releases
- CI/CD via GitHub Actions (build → test → publish)

---

## Phase 3 — Advanced AI & Community (v1.0+)

> Goal: Become the reference implementation for offline agentic RAG on the JVM.

### 3.1 GraphRAG

Augment chunk retrieval with entity-relation graph traversal:

- Named Entity Recognition (NER) on ingested text
- Build entity graph (Neo4j or in-memory)
- Graph-aware retrieval: entity neighbor expansion before ranking

### 3.2 Auto Memory (Agent Lifecycle Hooks)

```java
@DocRankMemory(autoRecall = true, autoCapture = true)
public class MyAgent {
    // Automatically recalls relevant context before each run
    // Automatically stores conversation output after each run
}
```

Inspired by memory-lancedb-pro's `before_agent_start` / `agent_end` hooks, but as a JVM annotation.

### 3.3 GPU Acceleration

- ONNX Runtime CUDA EP via existing `-Pgpu` Maven profile
- Automatic CPU/GPU fallback
- Benchmark: expected 5–10× speedup for batch ingest

### 3.4 Python SDK

```python
# pip install docrank-client
from docrank import DocRankClient

client = DocRankClient("http://localhost:8080")
client.ingest("My document content", title="Doc 1", tags=["demo"])
results = client.search("query", top_k=5)
```

REST-based thin client; DocRank still runs on JVM.

### 3.5 Public Benchmark

Publish reproducible benchmark comparing DocRank against:
- memory-lancedb-pro
- Chroma
- Qdrant built-in FTS

Datasets: BEIR · MIRACL (multilingual, CJK-heavy)

---

## Summary Timeline

```
v0.1  ████ (Done)         Core engine · MCP Server · Spring Starter · 76 tests
v0.2  ░░░░ Phase 1 start  Qdrant backend · Advanced scoring · Multi-scope
v0.3  ░░░░                pgvector backend · Deduplication · TTL
v0.4  ░░░░                CLI tools · Re-embedding · GDPR delete
v0.5  ░░░░ Phase 1 end    Docker image · More parsers · Maven Central prep
v0.6  ░░░░ Phase 2 start  Web Admin UI MVP (doc list + search playground)
v0.7  ░░░░                Statistics dashboard · LangChain4j adapter
v0.8  ░░░░                Spring AI adapter · Evaluation module · Streaming
v0.9  ░░░░ Phase 2 end    Maven Central release · Full CI/CD
v1.0  ░░░░ Phase 3 start  GraphRAG · GPU acceleration · Auto Memory
v1.x  ░░░░                Python SDK · Public Benchmark
```

---

## Contributing

Contributions are welcome at any phase. See `CONTRIBUTING.md` (coming soon).

Issues labeled `good first issue` are a great starting point.

---

<a name="中文"></a>
# 路线图（中文）

## 总目标

**DocRank 的目标是成为 JVM 生态中最好用的离线多语言 RAG 框架。**

对比竞品 *memory-lancedb-pro*，DocRank 的差异化优势：

| 对比项 | memory-lancedb-pro | DocRank |
|--------|-------------------|---------|
| Embedding 来源 | 必须调用 OpenAI API | BGE-M3 本地 ONNX，零 API |
| 运行时 | TypeScript / Node.js | JVM（Java 17+）|
| 中文分词 | 无专项支持 | HanLP 精确分词 |
| 日文分词 | 无专项支持 | Kuromoji（Lucene 内置）|
| Web 管理界面 | ❌ 无 | ✅ 计划（Phase 2）|
| 部署方式 | 依赖 OpenClaw 生态 | 独立 Spring Boot / Docker |
| 向量后端 | 仅 LanceDB | LanceDB + Qdrant + pgvector |

---

## 当前状态（v0.1 — 已完成）

- [x] 完整检索流水线：解析 → 分块 → BGE-M3 向量化 → Lucene BM25 + LanceDB → RRF → ONNX 重排序
- [x] 6 种文档解析器：PDF、Markdown、HTML、DOCX、JSON、TXT
- [x] MCP HTTP Server（5 个工具端点）
- [x] Spring Boot Starter + `application.yml` 配置
- [x] 76 个单元测试（无需模型文件）
- [x] 多语言：中文（HanLP）· 日文（Kuromoji）· 英文（StandardAnalyzer）

---

## Phase 1 — 功能对齐与超越（v0.2 ~ v0.5）

> 目标：在核心检索质量上追上并超越 memory-lancedb-pro，同时提升生产可用性。

### 1.1 多向量后端

所有后端实现相同的 `IndexBackend` 接口，配置文件一行切换：

- ✅ **LanceDB**（默认，已完成）
- 🔲 **Qdrant** — REST API，支持云部署
- 🔲 **pgvector** — PostgreSQL 扩展，适合已有 PG 的项目
- 🔲 **内存后端** — 用于测试或小型数据集

### 1.2 高级评分流水线

在现有 RRF + 重排序基础上新增：

- **时效性加权（Recency Boost）** — 越新的文档得分越高，衰减率可配置
- **重要度权重（Importance Weight）** — 写入时为文档指定优先级
- **时间衰减（Time Decay）** — 基于 `updated_at` 的指数衰减
- **MMR 多样性** — Maximal Marginal Relevance，减少冗余结果
- **最低分阈值** — 过滤低置信度结果，不返回噪声

### 1.3 多租户作用域隔离

```yaml
# 支持的作用域
global            # 全局共享
agent:<agent-id>  # 单个 Agent 私有
user:<user-id>    # 用户级知识
project:<id>      # 项目级文档
```

写入和检索自动按作用域隔离，数据不跨边界泄漏。

### 1.4 记忆管理

- **去重** — 写入前相似度检测，跳过或合并近似重复内容
- **重新向量化** — 更换模型后批量重建向量（`/mcp/kb_reembed`）
- **GDPR 删除** — 按过滤条件批量删除，不仅限于文档 ID
- **TTL 过期** — 短期记忆自动过期清理

### 1.5 命令行工具

```bash
docrank list   [--scope agent:001] [--tags java]
docrank search "查询文本" [--top-k 10]
docrank export --output backup.jsonl
docrank import --file backup.jsonl
docrank reembed --model /path/to/new-model
docrank stats
docrank delete --filter "updated_at < 2024-01-01"
```

### 1.6 Docker 部署

```bash
# 独立 MCP Server
docker run -p 8080:8080 \
  -v /opt/docrank/models:/models \
  -v /opt/docrank/data:/data \
  memo/docrank:latest

# docker-compose 含 LanceDB
docker compose up -d
```

### 1.7 更多解析格式

Excel (.xlsx) · PowerPoint (.pptx) · EPUB · CSV

---

## Phase 2 — Web 管理界面 & 生态集成（v0.6 ~ v0.9）

> 目标：让非开发者也能直接使用 DocRank。提供竞品完全缺失的可视化管理层。

### 2.1 Web 管理界面

基于 React 的管理后台，打包进 Spring Boot jar，访问 `http://localhost:8080` 即可使用。

**知识库面板**
- 文档列表：标题、标签、分块数、语言、写入时间
- 仪表板内全文搜索
- 一键删除、重新向量化、导出

**搜索测试台**
- 输入查询词，并排显示 BM25 / 向量 / 混合结果
- 得分明细：稀疏分 · 向量分 · 重排序分 · 最终分
- 分块查看器 + 匹配词高亮

**数据统计仪表板**
- 文档数 / 分块数 / 语言分布
- 存储占用（LanceDB + Lucene）
- 写入速率折线图
- 高频查询词 Top 10

**模型信息面板**
- 当前加载的 Embedding 和重排序模型信息
- 维度、量化精度、单次推理耗时

### 2.2 LangChain4j 适配器

```java
EmbeddingStore<TextSegment> store = DocRankEmbeddingStore.builder()
        .baseUrl("http://localhost:8080")
        .build();
```

### 2.3 Spring AI 适配器

```java
VectorStore vectorStore = new DocRankVectorStore(kb);
```

### 2.4 检索质量评估模块

```bash
docrank eval --dataset eval_set.jsonl --metrics ndcg@5,recall@10,mrr
```

指标：NDCG@K · Recall@K · MRR · MAP · 延迟（p50/p95/p99）

### 2.5 流式搜索（SSE）

```bash
curl -N http://localhost:8080/mcp/kb_search_stream \
  -d '{"query": "...", "top_k": 10}'
```

### 2.6 发布到 Maven Central

- 正式发布 `docrank-spring-boot-starter`
- GitHub Actions CI/CD（构建 → 测试 → 发布）
- 语义化版本 + GitHub Releases

---

## Phase 3 — 高级 AI 能力 & 社区建设（v1.0+）

> 目标：成为 JVM 上离线 Agentic RAG 的参考实现。

### 3.1 GraphRAG

- 对写入文本做命名实体识别（NER）
- 构建实体关系图（Neo4j 或内存图）
- 检索时做图邻域扩展，提升召回的上下文完整性

### 3.2 自动记忆（Agent 生命周期钩子）

```java
@DocRankMemory(autoRecall = true, autoCapture = true)
public class MyAgent {
    // 每次执行前自动召回相关记忆
    // 每次执行后自动存储对话内容
}
```

参考 memory-lancedb-pro 的 `before_agent_start` / `agent_end` 钩子，以 JVM 注解方式实现。

### 3.3 GPU 加速

- 通过现有 Maven `-Pgpu` Profile 启用 ONNX Runtime CUDA EP
- 自动 CPU/GPU 回退
- 预期批量写入速度提升 5~10 倍

### 3.4 Python 客户端 SDK

```python
# pip install docrank-client
from docrank import DocRankClient

client = DocRankClient("http://localhost:8080")
client.ingest("文档内容", title="文档1", tags=["示例"])
results = client.search("查询", top_k=5)
```

基于 REST 的轻量客户端，DocRank 仍运行在 JVM 上。

### 3.5 公开 Benchmark

对比竞品发布可复现的评测结果：
- 对比对象：memory-lancedb-pro · Chroma · Qdrant 内置 FTS
- 评测集：BEIR · MIRACL（多语言，含大量 CJK 文本）

---

## 进度一览

```
v0.1  ████ 已完成        核心引擎 · MCP Server · Spring Starter · 76 个测试
v0.2  ░░░░ Phase 1 开始  Qdrant 后端 · 高级评分 · 多作用域隔离
v0.3  ░░░░               pgvector 后端 · 去重 · TTL
v0.4  ░░░░               CLI 工具 · 重新向量化 · GDPR 删除
v0.5  ░░░░ Phase 1 结束  Docker 镜像 · 更多解析器 · Maven Central 准备
v0.6  ░░░░ Phase 2 开始  Web 管理界面 MVP（文档列表 + 搜索测试台）
v0.7  ░░░░               统计仪表板 · LangChain4j 适配器
v0.8  ░░░░               Spring AI 适配器 · 评估模块 · 流式搜索
v0.9  ░░░░ Phase 2 结束  Maven Central 发布 · 完整 CI/CD
v1.0  ░░░░ Phase 3 开始  GraphRAG · GPU 加速 · 自动记忆
v1.x  ░░░░               Python SDK · 公开 Benchmark
```

---

<a name="日本語"></a>
# ロードマップ（日本語）

## ビジョン

**DocRank は、JVMネイティブなオフライン多言語RAGフレームワークとして最高の選択肢を目指します。**

競合の *memory-lancedb-pro* と比較した DocRank の優位性：

| 比較項目 | memory-lancedb-pro | DocRank |
|---------|-------------------|---------|
| Embedding | OpenAI API必須 | BGE-M3ローカルONNX、APIキー不要 |
| ランタイム | TypeScript / Node.js | JVM（Java 17+）|
| 中国語形態素解析 | 未対応 | HanLP 精密分詞 |
| 日本語形態素解析 | 未対応 | Kuromoji（Lucene内蔵）|
| Web管理UI | ❌ なし | ✅ 予定（Phase 2）|
| デプロイ | OpenClawエコシステム依存 | 独立Spring Boot / Docker |
| ベクトルバックエンド | LanceDBのみ | LanceDB + Qdrant + pgvector |

---

## 現在の状態（v0.1 — 完成）

- [x] 完全な検索パイプライン：パーサー → チャンク分割 → BGE-M3 Embedding → Lucene BM25 + LanceDB → RRF → ONNX リランク
- [x] 6種類のドキュメントパーサー：PDF・Markdown・HTML・DOCX・JSON・TXT
- [x] MCP HTTP Server（5ツール）
- [x] Spring Boot Starter + `application.yml` 設定
- [x] 76個のユニットテスト（モデルファイル不要）
- [x] 多言語：中国語（HanLP）· 日本語（Kuromoji）· 英語（StandardAnalyzer）

---

## Phase 1 — 機能追従と超越（v0.2 〜 v0.5）

> 目標：コアの検索品質で memory-lancedb-pro に追いつき追い越す。
> 同時に本番環境での運用性を高める。

### 1.1 複数ベクトルバックエンド

すべてのバックエンドは同一の `IndexBackend` インターフェースを実装。設定1行で切替可能：

- ✅ **LanceDB**（デフォルト、実装済み）
- 🔲 **Qdrant** — REST API、クラウド対応
- 🔲 **pgvector** — PostgreSQL拡張
- 🔲 **インメモリ** — テスト・小規模データ向け

### 1.2 高度なスコアリングパイプライン

RRF + リランクに加えて：

- **Recency Boost** — 新しいドキュメントほど高スコア（減衰率設定可能）
- **重要度ウェイト** — 取り込み時にドキュメント優先度を指定
- **Time Decay** — `updated_at` に基づく指数的減衰
- **MMR多様性** — Maximal Marginal Relevanceで冗長な結果を削減
- **最低スコア閾値** — 低信頼度の結果をフィルタリング

### 1.3 マルチスコープ分離

```yaml
# サポートするスコープ
global            # 全エージェント共有
agent:<agent-id>  # エージェント専用
user:<user-id>    # ユーザー専用
project:<id>      # プロジェクト専用
```

### 1.4 メモリ管理

- **重複排除** — 取り込み前にコサイン類似度チェック
- **再Embedding** — モデル更新後に全ドキュメントを再ベクトル化
- **GDPRに対応した削除** — フィルタ条件での一括削除
- **TTL（有効期限）** — 一時的なメモリの自動失効

### 1.5 CLIツール

```bash
docrank list   [--scope agent:001] [--tags java]
docrank search "クエリテキスト" [--top-k 10]
docrank export --output backup.jsonl
docrank import --file backup.jsonl
docrank reembed --model /path/to/new-model
docrank stats
docrank delete --filter "updated_at < 2024-01-01"
```

### 1.6 Docker デプロイ

```bash
docker run -p 8080:8080 \
  -v /opt/docrank/models:/models \
  -v /opt/docrank/data:/data \
  memo/docrank:latest
```

### 1.7 追加パーサー

Excel (.xlsx) · PowerPoint (.pptx) · EPUB · CSV

---

## Phase 2 — Web管理UI & エコシステム統合（v0.6 〜 v0.9）

> 目標：非エンジニアでも使えるようにする。
> 競合が提供していないビジュアル管理レイヤーを実現する。

### 2.1 Web管理UI

ReactベースのダッシュボードをSpring Boot jarに同梱。`http://localhost:8080` でアクセス可能。

**ナレッジベースパネル**
- ドキュメント一覧：タイトル・タグ・チャンク数・言語・取り込み日時
- ダッシュボード内全文検索
- 削除・再Embedding・エクスポートのワンクリック操作

**検索プレイグラウンド**
- クエリを入力し、BM25 / ベクトル / ハイブリッドの結果を並べて表示
- スコア内訳：スパーススコア · ベクトルスコア · リランクスコア · 最終スコア
- マッチ箇所ハイライト付きチャンクビューア

**統計ダッシュボード**
- ドキュメント数 / チャンク数 / 言語分布
- ストレージ使用量（LanceDB + Lucene）
- 取り込み速度グラフ
- 高頻度クエリ Top 10

### 2.2 LangChain4j アダプター

```java
EmbeddingStore<TextSegment> store = DocRankEmbeddingStore.builder()
        .baseUrl("http://localhost:8080")
        .build();
```

### 2.3 Spring AI アダプター

```java
VectorStore vectorStore = new DocRankVectorStore(kb);
```

### 2.4 検索品質評価モジュール

```bash
docrank eval --dataset eval_set.jsonl --metrics ndcg@5,recall@10,mrr
```

指標：NDCG@K · Recall@K · MRR · MAP · レイテンシ（p50/p95/p99）

### 2.5 ストリーミング検索（SSE）

```bash
curl -N http://localhost:8080/mcp/kb_search_stream \
  -d '{"query": "...", "top_k": 10}'
```

### 2.6 Maven Central への公開

- `docrank-spring-boot-starter` を Maven Central に正式公開
- GitHub Actions CI/CD（ビルド → テスト → 公開）

---

## Phase 3 — 高度なAI機能 & コミュニティ（v1.0+）

> 目標：JVM上のオフライン Agentic RAG のリファレンス実装になる。

### 3.1 GraphRAG

- 取り込みテキストに対する固有表現認識（NER）
- エンティティグラフの構築（Neo4j またはインメモリ）
- 検索時にグラフ隣接ノードを展開して文脈を豊かにする

### 3.2 自動メモリ（エージェントライフサイクルフック）

```java
@DocRankMemory(autoRecall = true, autoCapture = true)
public class MyAgent {
    // 実行前に関連メモリを自動召還
    // 実行後に会話内容を自動保存
}
```

### 3.3 GPU アクセラレーション

- 既存の `-Pgpu` Maven プロファイルで ONNX Runtime CUDA EP を有効化
- CPU/GPU 自動フォールバック
- バッチ取り込みで5〜10倍の高速化を見込む

### 3.4 Python クライアント SDK

```python
# pip install docrank-client
from docrank import DocRankClient

client = DocRankClient("http://localhost:8080")
client.ingest("ドキュメント内容", title="ドキュメント1", tags=["サンプル"])
results = client.search("クエリ", top_k=5)
```

### 3.5 公開ベンチマーク

memory-lancedb-pro · Chroma · Qdrant との比較ベンチマークを公開。
評価データセット：BEIR · MIRACL（多言語・CJK重視）

---

## 進捗サマリー

```
v0.1  ████ 完成          コアエンジン · MCP Server · Spring Starter · 76テスト
v0.2  ░░░░ Phase 1 開始  Qdrantバックエンド · 高度なスコアリング · マルチスコープ
v0.3  ░░░░               pgvectorバックエンド · 重複排除 · TTL
v0.4  ░░░░               CLIツール · 再Embedding · GDPR削除
v0.5  ░░░░ Phase 1 終了  Dockerイメージ · 追加パーサー · Maven Central準備
v0.6  ░░░░ Phase 2 開始  Web管理UI MVP（ドキュメント一覧 + 検索プレイグラウンド）
v0.7  ░░░░               統計ダッシュボード · LangChain4jアダプター
v0.8  ░░░░               Spring AIアダプター · 評価モジュール · ストリーミング
v0.9  ░░░░ Phase 2 終了  Maven Central公開 · 完全CI/CD
v1.0  ░░░░ Phase 3 開始  GraphRAG · GPUアクセラレーション · 自動メモリ
v1.x  ░░░░               Python SDK · 公開ベンチマーク
```

---

## コントリビュート

あらゆるフェーズでの貢献を歓迎します。`CONTRIBUTING.md`（近日公開）をご参照ください。

`good first issue` ラベルの Issue からスタートするのがおすすめです。
