package com.phadcalc.llm.tify.workflow.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NodeConfigParser 按 type 分发解析，未知类型 / 非法 JSON 明确报错。
 */
class NodeConfigParserTest {

    private final NodeConfigParser parser = new NodeConfigParser(new ObjectMapper());

    @Test
    @DisplayName("LLM 类型解析为 LlmConfig")
    void shouldParseLlmConfig() {
        NodeConfigDef config = parser.parse("LLM",
                "{\"modelConfigId\": 1, \"prompt\": \"分类\", \"outputVariable\": \"intent\"}");

        assertThat(config).isInstanceOf(NodeConfigDef.LlmConfig.class);
        NodeConfigDef.LlmConfig llm = (NodeConfigDef.LlmConfig) config;
        assertThat(llm.modelConfigId()).isEqualTo(1L);
        assertThat(llm.prompt()).isEqualTo("分类");
        assertThat(llm.outputVariable()).isEqualTo("intent");
    }

    @Test
    @DisplayName("六种节点类型均可解析")
    void shouldParseAllNodeTypes() {
        assertThat(parser.parse("START", "{}")).isInstanceOf(NodeConfigDef.StartConfig.class);
        assertThat(parser.parse("CONDITION", "{\"expression\":\"a\",\"outputVariable\":\"r\"}"))
                .isInstanceOf(NodeConfigDef.ConditionConfig.class);
        assertThat(parser.parse("API_CALL", "{\"url\":\"http://x\",\"method\":\"GET\",\"outputVariable\":\"r\"}"))
                .isInstanceOf(NodeConfigDef.ApiCallConfig.class);
        assertThat(parser.parse("KNOWLEDGE", "{\"knowledgeBaseId\":3,\"query\":\"q\",\"topK\":5,\"outputVariable\":\"d\"}"))
                .isInstanceOf(NodeConfigDef.KnowledgeConfig.class);
        assertThat(parser.parse("END", "{\"outputVariable\":\"answer\"}"))
                .isInstanceOf(NodeConfigDef.EndConfig.class);
    }

    @Test
    @DisplayName("config 为 null 时按空对象解析不报错")
    void shouldTolerateNullConfig() {
        assertThat(parser.parse("START", null)).isInstanceOf(NodeConfigDef.StartConfig.class);
    }

    @Test
    @DisplayName("未知节点类型抛异常并带类型上下文")
    void shouldRejectUnknownType() {
        assertThatThrownBy(() -> parser.parse("FOO", "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("未知节点类型");
    }

    @Test
    @DisplayName("非法 JSON 抛 RuntimeException 并带节点类型上下文")
    void shouldRejectInvalidJson() {
        assertThatThrownBy(() -> parser.parse("LLM", "{bad json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM");
    }
}
