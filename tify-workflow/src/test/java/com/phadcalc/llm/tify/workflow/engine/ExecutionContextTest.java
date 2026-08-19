package com.phadcalc.llm.tify.workflow.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 23 讲验收用例：ExecutionContext 变量池模板替换。
 */
class ExecutionContextTest {

    @Test
    @DisplayName("set + resolve：模板变量替换为实际值")
    void shouldResolveTemplateVariable_whenValueWritten() {
        ExecutionContext ctx = new ExecutionContext(1L, "我买的耳机坏了");
        ctx.set("classify", "intent", "售后");

        String output = ctx.resolve("你好，{{classify.intent}}客服为您服务");

        assertThat(output).isEqualTo("你好，售后客服为您服务");
    }

    @Test
    @DisplayName("构造时预写 start.userMessage，所有节点默认可读")
    void shouldPreloadUserMessage() {
        ExecutionContext ctx = new ExecutionContext(1L, "你们最新的蓝牙耳机有什么功能");

        assertThat(ctx.get("start", "userMessage")).isEqualTo("你们最新的蓝牙耳机有什么功能");
        assertThat(ctx.resolve("用户问题：{{start.userMessage}}"))
                .isEqualTo("用户问题：你们最新的蓝牙耳机有什么功能");
    }

    @Test
    @DisplayName("变量不存在时保留原始占位符，不报错")
    void shouldKeepPlaceholder_whenVariableMissing() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");

        String output = ctx.resolve("你好，{{unknown.var}}客服为您服务");

        assertThat(output).isEqualTo("你好，{{unknown.var}}客服为您服务");
    }

    @Test
    @DisplayName("key 格式 = nodeKey.varName，写入只增不覆盖")
    void shouldUseNodeKeyPrefix_andAppendOnly() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        ctx.set("classify", "intent", "售前");
        ctx.set("presale", "answer", "回答A");
        ctx.set("aftersale", "answer", "回答B");

        assertThat(ctx.get("classify", "intent")).isEqualTo("售前");
        assertThat(ctx.get("presale", "answer")).isEqualTo("回答A");
        assertThat(ctx.get("aftersale", "answer")).isEqualTo("回答B");
        assertThat(ctx.snapshot()).hasSize(4); // start.userMessage + 3 个变量
    }

    @Test
    @DisplayName("snapshot 返回只读视图，写入抛 UnsupportedOperationException")
    void shouldReturnUnmodifiableSnapshot() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        ctx.set("classify", "intent", "售后");
        Map<String, Object> snapshot = ctx.snapshot();

        assertThatThrownBy(() -> snapshot.put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("set null 值转为空串，resolve 不抛 NPE")
    void shouldHandleNullValue() {
        ExecutionContext ctx = new ExecutionContext(1L, "hi");
        ctx.set("kb", "docs", null);

        assertThat(ctx.resolve("内容：{{kb.docs}}")).isEqualTo("内容：");
    }
}
