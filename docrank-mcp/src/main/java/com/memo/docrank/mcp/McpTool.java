package com.memo.docrank.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class McpTool {
    private String              name;
    private String              description;
    private Map<String, Object> parameters;

    public static McpTool of(String name, String description, Map<String, Object> parameters) {
        return McpTool.builder().name(name).description(description).parameters(parameters).build();
    }
}
