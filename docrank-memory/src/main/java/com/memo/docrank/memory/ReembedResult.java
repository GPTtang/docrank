package com.memo.docrank.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReembedResult {
    private int  chunkCount;
    private long elapsedMs;
}
