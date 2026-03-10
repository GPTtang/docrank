package com.memo.docrank.agent;

/**
 * 单轮对话记录（用户消息 + 助手回复）。
 *
 * @param userMessage      用户的问题
 * @param assistantMessage 助手的回答
 */
public record ConversationTurn(String userMessage, String assistantMessage) {}
