package com.memo.docrank.core.analyzer;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import com.memo.docrank.core.model.Language;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多语言分析器工厂：中文用 HanLP 分词，日文用 Kuromoji，英文用 StandardAnalyzer
 */
@Slf4j
public class MultiLingualAnalyzerFactory {

    private final Analyzer chineseAnalyzer = new SmartChineseAnalyzer();
    private final Analyzer japaneseAnalyzer = new JapaneseAnalyzer();
    private final Analyzer defaultAnalyzer  = new StandardAnalyzer();

    public Analyzer getAnalyzer(Language lang) {
        return switch (lang) {
            case CHINESE  -> chineseAnalyzer;
            case JAPANESE -> japaneseAnalyzer;
            default       -> defaultAnalyzer;
        };
    }

    /**
     * 使用 HanLP 对中文文本精确分词，返回词条列表
     */
    public List<String> segmentChinese(String text) {
        List<Term> terms = HanLP.segment(text);
        return terms.stream()
                .map(t -> t.word)
                .filter(w -> !w.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 通用分词入口，根据语言自动路由
     */
    public List<String> tokenize(String text, Language lang) {
        if (lang == Language.CHINESE) {
            return segmentChinese(text);
        }
        // 日文和英文通过 Lucene Analyzer 处理，此处返回简单空格切分作为备用
        return List.of(text.split("[\\s　]+"));
    }
}
