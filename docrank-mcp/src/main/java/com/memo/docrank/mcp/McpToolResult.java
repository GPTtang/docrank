package com.memo.docrank.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class McpToolResult {
    private boolean success;
    private Object data;
    private String error;

    public static McpToolResult ok(Object data) {
        return McpToolResult.builder().success(true).data(data).build();
    }

    public static McpToolResult fail(String error) {
        return McpToolResult.builder().success(false).error(error).build();
    }
}
