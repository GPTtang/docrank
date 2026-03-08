package com.memo.docrank.memory;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档元信息
 * 对应一次 ingest 操作写入的原始文档
 */
@Data
@Builder
public class KnowledgeDocument {
    private String              docId;
    private String              title;
    /** 原始文件名或来源标识 */
    private String              source;
    /** 文档类型：text / pdf / markdown / html / docx / json */
    private String              mimeType;
    private List<String>        tags;
    private Map<String, String> metadata;
    private int                 chunkCount;
    private Instant             ingestedAt;
}
