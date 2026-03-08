package com.memo.docrank.core.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class Chunk {
    private String chunkId;
    private String docId;
    private String title;
    private String sectionPath;
    private String chunkText;
    private int chunkIndex;
    private List<String> tags;
    private String summary;
    private Language language;
    private Instant updatedAt;

    // Phase 1 新增字段

    /** 作用域隔离：global / agent:<id> / user:<id> / project:<id> */
    @Builder.Default
    private String scope = "global";

    /** 用户指定的重要度权重，范围 0.0 ~ 1.0，影响最终评分 */
    @Builder.Default
    private double importance = 1.0;

    /** TTL 过期时间，null 表示永不过期 */
    private Instant expiresAt;
}
