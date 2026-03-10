package com.memo.docrank.agent;

import com.memo.docrank.core.model.SearchResult;

import java.util.List;

/**
 * Agent 问答结果。
 *
 * @param answer  LLM 生成的回答
 * @param sources 检索到的知识库片段（用于溯源）
 */
public record AgentChatResult(String answer, List<SearchResult> sources) {}
