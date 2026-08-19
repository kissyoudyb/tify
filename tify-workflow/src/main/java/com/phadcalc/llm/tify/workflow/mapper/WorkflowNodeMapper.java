package com.phadcalc.llm.tify.workflow.mapper;

import com.phadcalc.llm.tify.common.mapper.BaseMapper;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {
}
