package com.phadcalc.llm.tify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.phadcalc.llm.tify.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("workflow")
public class Workflow extends BaseEntity {
    private String name;
    private String description;
    /** DRAFT / PUBLISHED / DISABLED */
    private String status;
}
