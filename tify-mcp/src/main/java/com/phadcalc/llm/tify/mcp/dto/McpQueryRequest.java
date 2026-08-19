package com.phadcalc.llm.tify.mcp.dto;

import lombok.Data;

@Data
public class McpQueryRequest {
    private int page = 1;
    private int pageSize = 20;
    private Integer enabled;
}
