package com.phadcalc.llm.tify.workflow.engine;

import com.phadcalc.llm.tify.workflow.dto.NodeConfigDef;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNode;

/**
 * 节点执行器统一接口。
 * 每种节点类型一个实现，Spring 自动注册到 NodeExecutorRegistry。
 */
public interface NodeExecutor {

    /** 执行节点逻辑，结果写入 ctx。 */
    void execute(WorkflowNode node, NodeConfigDef config, ExecutionContext ctx);

    /** 对应的节点类型，如 "LLM" / "CONDITION" / "API_CALL" / "KNOWLEDGE"。 */
    String nodeType();
}
