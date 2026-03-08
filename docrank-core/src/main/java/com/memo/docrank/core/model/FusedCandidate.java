package com.memo.docrank.core.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class FusedCandidate {
    private Chunk chunk;
    private double sparseScore;
    private double vectorScore;
    private double fusedScore;
    private int sparseRank;
    private int vectorRank;
    private RecallCandidate.RecallSource source;
}
