package com.memo.docrank.core.chunking;

import com.memo.docrank.core.analyzer.LanguageDetector;
import com.memo.docrank.core.model.Chunk;
import com.memo.docrank.core.model.Language;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CJK 感知的文本分块服务
 * - 中文：按句号/感叹号/问号切分（。！？）
 * - 日文：按句号（。）切分
 * - 英文：按标准句号切分
 * - 全局：按 chunkSize 控制长度，overlap 做滑窗
 */
@Slf4j
@RequiredArgsConstructor
public class ChunkingService {

    private static final Pattern ZH_SENT = Pattern.compile("(?<=[。！？!?])");
    private static final Pattern JA_SENT = Pattern.compile("(?<=[。！？])");
    private static final Pattern EN_SENT = Pattern.compile("(?<=[.!?])\\s+");

    private final LanguageDetector languageDetector;
    private final int chunkSize;
    private final int overlap;

    public List<Chunk> chunk(String docId, String title, String text) {
        if (text == null || text.isBlank()) return List.of();

        Language lang = languageDetector.detect(text);
        List<String> sentences = splitSentences(text, lang);
        List<String> windows   = buildWindows(sentences, lang);

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            chunks.add(Chunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .docId(docId)
                    .title(title)
                    .chunkText(windows.get(i))
                    .chunkIndex(i)
                    .language(lang)
                    .updatedAt(Instant.now())
                    .build());
        }
        log.debug("文档 {} 分块完成：语言={}, 共 {} 块", docId, lang, chunks.size());
        return chunks;
    }

    private List<String> splitSentences(String text, Language lang) {
        Pattern pattern = switch (lang) {
            case CHINESE -> ZH_SENT;
            case JAPANESE -> JA_SENT;
            default -> EN_SENT;
        };
        String[] parts = pattern.split(text);
        List<String> sentences = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) sentences.add(trimmed);
        }
        return sentences;
    }

    private List<String> buildWindows(List<String> sentences, Language lang) {
        List<String> windows = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (textLength(current.toString(), lang) + textLength(sentence, lang) > chunkSize
                    && !current.isEmpty()) {
                windows.add(current.toString().strip());
                // overlap: 保留末尾若干字符
                String tail = current.substring(Math.max(0, current.length() - overlap));
                current = new StringBuilder(tail);
            }
            current.append(sentence);
        }

        if (!current.isEmpty()) {
            windows.add(current.toString().strip());
        }
        return windows;
    }

    // CJK 每个字算一个单位，英文按词算
    private int textLength(String text, Language lang) {
        if (lang == Language.CHINESE || lang == Language.JAPANESE) {
            return text.length();
        }
        return text.split("\\s+").length;
    }
}
