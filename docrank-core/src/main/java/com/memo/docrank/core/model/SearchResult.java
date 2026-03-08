package com.memo.docrank.core.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchResult {
    private Chunk chunk;
    private double score;
    private double sparseScore;
    private double vectorScore;
    private double rerankScore;
    private String snippet;
    private String boostReason;
}
