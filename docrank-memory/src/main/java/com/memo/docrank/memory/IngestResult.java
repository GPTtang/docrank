package com.memo.docrank.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IngestResult {
    private String  docId;
    private String  title;
    private int     chunkCount;
    private int     skippedChunks;
    private boolean success;
    private String  error;

    public static IngestResult ok(String docId, String title, int chunkCount) {
        return IngestResult.builder()
                .docId(docId).title(title).chunkCount(chunkCount).success(true).build();
    }

    public static IngestResult ok(String docId, String title, int chunkCount, int skippedChunks) {
        return IngestResult.builder()
                .docId(docId).title(title).chunkCount(chunkCount)
                .skippedChunks(skippedChunks).success(true).build();
    }

    public static IngestResult fail(String docId, String error) {
        return IngestResult.builder().docId(docId).success(false).error(error).build();
    }
}
