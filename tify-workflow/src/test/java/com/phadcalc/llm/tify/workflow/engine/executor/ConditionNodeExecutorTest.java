package com.phadcalc.llm.tify.workflow.engine.executor;

import com.phadcalc.llm.tify.workflow.dto.NodeConfigDef;
import com.phadcalc.llm.tify.workflow.engine.ExecutionContext;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 27 讲单测边界：纯函数逻辑（条件判断）值得写单测。
 */
class ConditionNodeExecutorTest {

    private final ConditionNodeExecutor executor = new ConditionNodeExecutor();

    private WorkflowNode node() {
        WorkflowNode node = new WorkflowNode();
        node.setNodeKey("router");
        node.setType("CONDITION");
        node.setConfig("{}");
        return node;
    }

    @Test
    @DisplayName("expression 为字面量字符串（如 售后）时原样写入 ctx")
    void shouldWriteLiteral_whenNotBoolean() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        ctx.set("classify", "intent", "售后");
        NodeConfigDef.ConditionConfig config =
                new NodeConfigDef.ConditionConfig("{{classify.intent}}", "route");

        executor.execute(node(), config, ctx);

        assertThat(ctx.get("router", "route")).isEqualTo("售后");
    }

    @Test
    @DisplayName("expression 替换后为 true/false 时写入布尔串")
    void shouldWriteBoolean_whenResolvedTrue() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        ctx.set("check", "ok", "true");
        NodeConfigDef.ConditionConfig config =
                new NodeConfigDef.ConditionConfig("{{check.ok}}", "route");

        executor.execute(node(), config, ctx);

        assertThat(ctx.get("router", "route")).isEqualTo("true");
    }

    @Test
    @DisplayName("outputVariable 缺省时写入默认 key 'result'")
    void shouldUseDefaultOutputVariable() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        NodeConfigDef.ConditionConfig config =
                new NodeConfigDef.ConditionConfig("售后", null);

        executor.execute(node(), config, ctx);

        assertThat(ctx.get("router", "result")).isEqualTo("售后");
    }

    @Test
    @DisplayName("大小写不敏感：TRUE 归一为 true")
    void shouldNormalizeBooleanCase() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        NodeConfigDef.ConditionConfig config =
                new NodeConfigDef.ConditionConfig("TRUE", "route");

        executor.execute(node(), config, ctx);

        assertThat(ctx.get("router", "route")).isEqualTo("true");
    }
}
