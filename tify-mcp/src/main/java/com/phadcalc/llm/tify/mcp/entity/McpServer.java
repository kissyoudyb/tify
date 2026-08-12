package com.phadcalc.llm.tify.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.phadcalc.llm.tify.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("mcp_server")
public class McpServer extends BaseEntity {

    private String name;
    private String endpoint;
    private String description;
    private Integer enabled;
}
