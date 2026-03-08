package com.memo.docrank.core.embedding;

import java.util.List;

public interface EmbeddingProvider {

    /**
     * 批量编码，返回顺序与输入一致
     */
    List<float[]> encode(List<String> texts);

    default float[] encodeSingle(String text) {
        return encode(List.of(text)).get(0);
    }

    int dimension();
}
