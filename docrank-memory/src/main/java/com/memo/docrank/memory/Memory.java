package com.memo.docrank.memory;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class Memory {
    private String id;
    private String content;
    private double importance;     // 0.0 ~ 1.0
    private List<String> tags;
    private Instant createdAt;
    private Instant updatedAt;
    private int accessCount;
}
