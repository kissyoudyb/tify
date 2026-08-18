package com.phadcalc.llm.tify.workflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phadcalc.llm.tify.app.TifyApplication;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowEdgeMapper;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowMapper;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowNodeMapper;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowNodeRunMapper;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作流 CRUD 全链路集成测试（mock profile · H2 内存库）。
 * 覆盖：创建（拆三表）→ 详情还原 → 更新（先删后插）→ 删除（级联逻辑删）→ 执行记录接口。
 */
@SpringBootTest(classes = TifyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class WorkflowCrudApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkflowMapper workflowMapper;
    @Autowired WorkflowNodeMapper nodeMapper;
    @Autowired WorkflowEdgeMapper edgeMapper;
    @Autowired WorkflowRunMapper runMapper;
    @Autowired WorkflowNodeRunMapper nodeRunMapper;

    @BeforeEach
    void clean() {
        // 清空上一轮数据（逻辑删除 + 过滤器生效，测试间互不污染）
        nodeRunMapper.delete(null);
        runMapper.delete(null);
        edgeMapper.delete(null);
        nodeMapper.delete(null);
        workflowMapper.delete(null);
    }

    private String createBody() {
        return """
                {
                  "name": "智能客服分类工作流",
                  "description": "意图分发",
                  "nodes": [
                    {"nodeKey": "start", "type": "START", "name": "开始", "config": {}},
                    {"nodeKey": "classify", "type": "LLM", "name": "问题分类",
                     "config": {"modelConfigId": 1, "prompt": "分类", "outputVariable": "intent"}},
                    {"nodeKey": "router", "type": "CONDITION", "name": "路由分发",
                     "config": {"expression": "{{classify.intent}}", "outputVariable": "route"}},
                    {"nodeKey": "aftersale", "type": "LLM", "name": "售后服务",
                     "config": {"modelConfigId": 1, "prompt": "售后", "outputVariable": "answer"}},
                    {"nodeKey": "end", "type": "END", "name": "结束", "config": {"outputVariable": "answer"}}
                  ],
                  "edges": [
                    {"sourceNodeKey": "start", "targetNodeKey": "classify", "condition": null},
                    {"sourceNodeKey": "classify", "targetNodeKey": "router", "condition": null},
                    {"sourceNodeKey": "router", "targetNodeKey": "aftersale", "condition": "售后"},
                    {"sourceNodeKey": "aftersale", "targetNodeKey": "end", "condition": null}
                  ]
                }
                """;
    }

    @Test
    @DisplayName("创建：拆三表写入，返回 id，三张表数据齐全")
    void create_writesThreeTables() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        assertThat(workflowMapper.selectById(id)).isNotNull();
        assertThat(nodeMapper.selectList(null)).hasSize(5);
        assertThat(edgeMapper.selectList(null)).hasSize(4);
    }

    @Test
    @DisplayName("详情：节点和连线完整还原，config 字段不丢失")
    void detail_restoresNodesAndEdges() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult result = mockMvc.perform(get("/api/v1/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("智能客服分类工作流"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("nodes")).hasSize(5);
        assertThat(data.path("edges")).hasSize(4);
        assertThat(data.path("nodes").get(1).path("config").path("outputVariable").asText())
                .isEqualTo("intent");
        assertThat(data.path("nodes").get(1).path("config").path("modelConfigId").asLong())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("更新：先删后插，名称与节点集合被替换")
    void update_replacesNodesAndEdges() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String updateBody = """
                {
                  "name": "改名后的工作流",
                  "description": "",
                  "status": "PUBLISHED",
                  "nodes": [
                    {"nodeKey": "start", "type": "START", "name": "开始", "config": {}},
                    {"nodeKey": "end", "type": "END", "name": "结束", "config": {"outputVariable": "answer"}}
                  ],
                  "edges": [
                    {"sourceNodeKey": "start", "targetNodeKey": "end", "condition": null}
                  ]
                }
                """;

        MvcResult result = mockMvc.perform(put("/api/v1/workflows/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("改名后的工作流"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("nodes")).hasSize(2);
        assertThat(data.path("edges")).hasSize(1);
        // 旧节点被逻辑删除，selectList 只看到新节点
        assertThat(nodeMapper.selectList(null)).hasSize(2);
        assertThat(edgeMapper.selectList(null)).hasSize(1);
    }

    @Test
    @DisplayName("删除：workflow 与关联 nodes/edges 一起逻辑删除")
    void delete_logicallyDeletesAll() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(delete("/api/v1/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(workflowMapper.selectById(id)).isNull();
        assertThat(nodeMapper.selectList(null)).isEmpty();
        assertThat(edgeMapper.selectList(null)).isEmpty();
    }

    @Test
    @DisplayName("执行记录：无记录时 runs 分页为空、runs/latest 返回 null")
    void runs_emptyWhenNoExecutions() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/workflows/{id}/runs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());

        mockMvc.perform(get("/api/v1/workflows/{id}/runs/latest", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("不存在的工作流：详情 / 删除 / runs 均返回 WORKFLOW_NOT_FOUND(6000)")
    void missingWorkflow_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6000));
        mockMvc.perform(delete("/api/v1/workflows/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6000));
        mockMvc.perform(get("/api/v1/workflows/99999/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6000));
    }
}
