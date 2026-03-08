package com.memo.docrank.core.analyzer;

import com.memo.docrank.core.model.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageDetectorTest {

    static LanguageDetector detector;

    @BeforeAll
    static void setup() {
        detector = new LanguageDetector();
    }

    @Test
    void detectChinese() {
        Language lang = detector.detect("人工智能是计算机科学的一个分支，它企图了解智能的实质。");
        assertEquals(Language.CHINESE, lang);
    }

    @Test
    void detectJapanese() {
        Language lang = detector.detect("人工知能は、コンピュータサイエンスの分野の一つです。");
        assertEquals(Language.JAPANESE, lang);
    }

    @Test
    void detectEnglish() {
        Language lang = detector.detect("Artificial intelligence is a branch of computer science.");
        assertEquals(Language.ENGLISH, lang);
    }

    @Test
    void detectEmpty() {
        assertEquals(Language.UNKNOWN, detector.detect(""));
    }

    @Test
    void detectNull() {
        assertEquals(Language.UNKNOWN, detector.detect(null));
    }

    @Test
    void detectBlank() {
        assertEquals(Language.UNKNOWN, detector.detect("   "));
    }
}
