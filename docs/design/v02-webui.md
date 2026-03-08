# 系统设计: Web 管理界面

> **对应 PRD**：`docs/prd/v02-webui.md`
> **状态**：已实现（Phase 2.1 MVP）
> **模块**：docrank-webui

---

## 1. 概述

Web UI 是一个纯前端单文件 HTML（Alpine.js + Tailwind CSS），内嵌于 Spring Boot jar 中静态资源目录，通过 `DocRankWebUiAutoConfiguration` 自动注册资源路径，访问 `/docrank-ui/` 即可使用。

---

## 2. 架构

```
浏览器 → GET /docrank-ui/ → Spring 静态资源服务
                            → index.html（Alpine.js SPA）
                                     │
                                     │ 调用 MCP REST API
                                     ▼
                            DocRankMcpServer（已有）
                            /mcp/kb_stats
                            /mcp/kb_search
                            /mcp/kb_ingest
                            /mcp/kb_ingest_file
                            /mcp/kb_delete
                            /mcp/kb_delete_scope
```

**无独立后端**：Web UI 直接调用 MCP Server 的现有端点，不新增任何 Java 端点。

---

## 3. 后端 Java 层（极简）

### 3.1 DocRankWebUiAutoConfiguration

```java
@Configuration
@ConditionalOnWebApplication
public class DocRankWebUiAutoConfiguration {

    @Bean
    public DocRankWebUiController docRankWebUiController() {
        return new DocRankWebUiController();
    }

    // 静态资源映射: /docrank-ui/** → classpath:/static/docrank-ui/
    // Spring Boot 默认已处理 /static/ 目录，无需额外配置
}
```

### 3.2 DocRankWebUiController

```java
@Controller
public class DocRankWebUiController {

    @GetMapping("/docrank-ui")
    public String uiRoot() {
        return "redirect:/docrank-ui/index.html";
    }
}
```

### 3.3 文件位置

```
docrank-webui/
  src/main/resources/
    META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    static/docrank-ui/
      index.html        ← 单文件 SPA
```

---

## 4. 前端设计

### 4.1 技术选型

| 技术 | 版本 | 选择理由 |
|------|------|---------|
| Alpine.js | 3.14.1 | 轻量响应式，无构建步骤，CDN 引入 |
| Tailwind CSS | CDN | 无需 Node.js 构建，内嵌 jar 友好 |

不使用 React/Vue/构建工具，保证单文件可直接内嵌 jar。

### 4.2 状态管理（Alpine.js app()）

```javascript
{
  activeTab: 'search',     // 当前 Tab
  health: false,           // 服务健康状态
  stats: {},               // { vector_chunks, bm25_chunks, healthy }

  search:   { query, scope, topK, results[], loading, error, done },
  ingest:   { title, content, scope, importance, tags, loading, result, error },
  upload:   { file, scope, tags, loading, result, error, dragging },
  del:      { docId, loading, result, error },
  delScope: { scope, loading, result, error }
}
```

### 4.3 各 Tab 调用的 API

| Tab | 操作 | API 端点 | 方法 |
|-----|------|---------|------|
| Search | 搜索 | `/mcp/kb_search` | POST |
| Ingest | 写入文本 | `/mcp/kb_ingest` | POST |
| Ingest | 上传文件 | `/mcp/kb_ingest_file` | POST（multipart）|
| Manage | 删除文档 | `/mcp/kb_delete` | POST |
| Manage | 清除 scope | `/mcp/kb_delete_scope` | POST |
| Stats | 刷新统计 | `/mcp/kb_stats` | GET |
| Header | 初始化 | `/mcp/kb_stats` | GET |

### 4.4 UI 布局

```
Header（固定顶部）
  ├── Logo + "DocRank Admin"
  ├── 健康指示灯（绿/红）+ 向量/BM25 chunk 数
  └── 刷新按钮

TabBar
  ├── 🔍 搜索
  ├── 📥 写入
  ├── 🗑 管理
  └── 📊 统计

TabContent（切换显示）
  Search:
    ├── [查询框] [scope框] [TopK下拉] [搜索按钮]
    └── 结果卡片列表（分数条 + title + content + score/lang/tags）

  Ingest:
    ├── 左：文本写入表单（title/content/scope/importance/tags）
    └── 右：文件上传（拖拽 + 文件选择器）

  Manage:
    ├── 左：按 doc_id 删除
    └── 右：按 scope 批量清除（橙色警告样式）

  Stats:
    ├── 卡片：健康状态 | 向量chunks | BM25chunks
    └── 系统信息表（版本/模型/检索策略/API链接）
```

---

## 5. 关键交互实现

### 5.1 文件拖拽上传

```javascript
onFileDrop(e) {
    this.upload.file = e.dataTransfer.files[0] || null;
    this.upload.dragging = false;
}

async doUpload() {
    const fd = new FormData();
    fd.append('file', this.upload.file);
    fd.append('scope', this.upload.scope || 'global');
    this.upload.tags.split(',').map(t => t.trim()).filter(Boolean)
        .forEach(t => fd.append('tags', t));
    const r = await fetch('/mcp/kb_ingest_file', { method: 'POST', body: fd });
    // ...
}
```

### 5.2 初始化加载统计

```javascript
async init() {
    await this.loadStats();
}

async loadStats() {
    const r = await fetch('/mcp/kb_stats');
    const data = await r.json();
    if (data.success) {
        this.stats = data.data;
        this.health = !!data.data.healthy;
    }
}
```

---

## 6. 技术决策

### 6.1 为什么用单 HTML 文件而非 React？

- 内嵌 jar 时无需 Maven frontend 插件和 Node.js 构建步骤
- Alpine.js + Tailwind CDN 满足管理界面的交互复杂度
- 部署零依赖（用户不需要配置任何额外服务）

### 6.2 为什么 Web UI 直接复用 MCP 端点？

- 避免重复 API 实现
- MCP 端点已经过 Agent 使用验证，稳定可靠
- 保持单一数据来源

### 6.3 当前 MVP 缺失的功能

| 功能 | 原因 | 计划 |
|------|------|------|
| 文档列表 | 缺少 List API（/mcp/kb_list）| Phase 2.1 后续 |
| 写入速率图表 | 缺少历史数据存储 | Phase 2.1 后续 |
| 高频查询 Top10 | 缺少查询日志 | Phase 2.1 后续 |
| 认证保护 | MCP Server 无认证 | 由外层基础设施处理 |
