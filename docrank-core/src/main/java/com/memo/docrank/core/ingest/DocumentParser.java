package com.memo.docrank.core.ingest;

import java.io.InputStream;

/**
 * 文档解析器接口
 */
public interface DocumentParser {

    /**
     * 当前解析器支持的 MIME 类型，例如 "application/pdf"
     */
    String supportedMimeType();

    /**
     * 当前解析器支持的文件扩展名，例如 ".pdf"
     */
    String supportedExtension();

    /**
     * 将输入流解析为标准化文档
     *
     * @param input    文件输入流
     * @param filename 原始文件名（用于推断标题等）
     * @param docId    文档 ID
     */
    ParsedDocument parse(InputStream input, String filename, String docId) throws Exception;
}
