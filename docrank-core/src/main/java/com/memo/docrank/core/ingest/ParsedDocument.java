package com.memo.docrank.core.ingest;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 解析完成后的标准化文档
 */
@Data
@Builder
public class ParsedDocument {
    private String docId;
    private String title;
    /** 纯文本正文（去除格式标记后） */
    private String text;
    /** 按标题切分的段落，key = 标题路径，value = 段落文本 */
    private List<Section> sections;
    /** 文档元数据（作者、创建时间等） */
    private Map<String, String> metadata;
    /** 原始文件类型 */
    private String mimeType;

    @Data
    @Builder
    public static class Section {
        private String heading;
        private String path;
        private String content;
        private int level;
    }
}
