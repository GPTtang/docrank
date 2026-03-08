package com.memo.docrank.core.analyzer;

import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import com.memo.docrank.core.model.Language;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LanguageDetector {

    private final com.github.pemistahl.lingua.api.LanguageDetector detector;

    public LanguageDetector() {
        this.detector = LanguageDetectorBuilder
                .fromLanguages(
                        com.github.pemistahl.lingua.api.Language.CHINESE,
                        com.github.pemistahl.lingua.api.Language.JAPANESE,
                        com.github.pemistahl.lingua.api.Language.ENGLISH)
                .build();
        log.info("LanguageDetector 初始化完成（支持中文、日文、英文）");
    }

    public Language detect(String text) {
        if (text == null || text.isBlank()) return Language.UNKNOWN;
        com.github.pemistahl.lingua.api.Language detected = detector.detectLanguageOf(text);
        return switch (detected) {
            case CHINESE  -> Language.CHINESE;
            case JAPANESE -> Language.JAPANESE;
            case ENGLISH  -> Language.ENGLISH;
            default       -> Language.UNKNOWN;
        };
    }
}
