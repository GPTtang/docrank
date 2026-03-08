package com.memo.docrank.core.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecallCandidate {
    private Chunk chunk;
    private double score;
    private RecallSource source;

    public enum RecallSource {
        KEYWORD, VECTOR, HYBRID
    }
}
