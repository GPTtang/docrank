package com.memo.docrank.core.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * 多语言路由分析器（基于 DelegatingAnalyzerWrapper）
 *
 * Lucene 的 Analyzer API 只在 createComponents(fieldName) 中路由，
 * 无法在运行时按文本内容切换。这里通过字段名前缀路由：
 *   - "zh_*" → SmartChineseAnalyzer（中文分词）
 *   - "ja_*" → JapaneseAnalyzer（日文 Kuromoji）
 *   - 其他   → SmartChineseAnalyzer（中英文混合文本表现最好）
 *
 * LuceneBM25Index 的字段（title/chunk_text/tags）均走 SmartChineseAnalyzer，
 * 可同时处理中文、英文，对日文文本产生字符 bigram。
 */
public class LanguageRoutingAnalyzer extends DelegatingAnalyzerWrapper {

    private final SmartChineseAnalyzer chineseAnalyzer  = new SmartChineseAnalyzer();
    private final JapaneseAnalyzer     japaneseAnalyzer = new JapaneseAnalyzer();
    private final StandardAnalyzer     standardAnalyzer = new StandardAnalyzer();

    public LanguageRoutingAnalyzer() {
        super(PER_FIELD_REUSE_STRATEGY);
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        if (fieldName != null && fieldName.startsWith("ja_")) {
            return japaneseAnalyzer;
        }
        if (fieldName != null && fieldName.startsWith("en_")) {
            return standardAnalyzer;
        }
        // 默认：SmartChineseAnalyzer（中英文混合最优）
        return chineseAnalyzer;
    }

    @Override
    public void close() {
        chineseAnalyzer.close();
        japaneseAnalyzer.close();
        standardAnalyzer.close();
        super.close();
    }
}
