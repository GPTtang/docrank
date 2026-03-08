package com.memo.docrank.core.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChunkWithVectors {
    private Chunk chunk;
    /** chunk_text 向量，必须有 */
    private List<Float> vecChunk;
    /** title 向量，可为 null */
    private List<Float> vecTitle;
}
