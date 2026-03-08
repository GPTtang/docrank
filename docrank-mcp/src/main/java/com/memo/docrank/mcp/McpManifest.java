package com.memo.docrank.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class McpManifest {
    private String       name;
    private String       version;
    private String       description;
    private List<McpTool> tools;
}
