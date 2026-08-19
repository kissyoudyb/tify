package com.phadcalc.llm.tify.workflow.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phadcalc.llm.tify.app.TifyApplication;
import com.phadcalc.llm.tify.common.exception.BizException;
import com.phadcalc.llm.tify.provider.adapter.ProviderAdapter;
import com.phadcalc.llm.tify.provider.adapter.ProviderAdapterFactory;
import com.phadcalc.llm.tify.provider.dto.ChatRequest;
import com.phadcalc.llm.tify.provider.dto.ChatResponse;
import com.phadcalc.llm.tify.workflow.dto.WorkflowCreateRequest;
import com.phadcalc.llm.tify.workflow.dto.WorkflowDetailVO;
import com.phadcalc.llm.tify.workflow.dto.WorkflowEdgeReq;
import com.phadcalc.llm.tify.workflow.dto.WorkflowNodeReq;
import com.phadcalc.llm.tify.workflow.engine.WorkflowEngine;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNodeRun;
import com.phadcalc.llm.tify.workflow.entity.WorkflowRun;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowNodeRunMapper;
import com.phadcalc.llm.tify.workflow.mapper.WorkflowRunMapper;
import com.phadcalc.llm.tify.workflow.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流执行引擎集成测试（mock profile · H2 · mock LLM 调用）。
 * 覆盖 23 讲验收的异常分支：线性执行、条件分支、节点失败、死循环、条件不匹配。
 */
@SpringBootTest(classes = TifyApplication.class)
@ActiveProfiles("mock")
@Transactional
class WorkflowEngineIntegrationTest {

    @Autowired WorkflowService workflowService;
    @Autowired WorkflowEngine workflowEngine;
    @Autowired WorkflowRunMapper runMapper;
    @Autowired WorkflowNodeRunMapper nodeRunMapper;
    @Autowired ObjectMapper objectMapper;

    /** mock LLM 调用层，LlmNodeExecutor 本身保持真实 bean */
    @MockBean
    ProviderAdapterFactory adapterFactory;

    ProviderAdapter mockAdapter;

    /**
     * 按 system prompt 内容模拟模型回复：
     *  - 分类 prompt → "售后"
     *  - 售后 prompt → "售后回答:{用户消息}"
     *  - 售前 prompt → "售前回答:{用户消息}"
     *  - 其他（线性 echo）→ "回答:{用户消息}"
     */
    @BeforeEach
    void stubLlm() {
        mockAdapter = mock(ProviderAdapter.class);
        when(adapterFactory.get(any())).thenReturn(mockAdapter);
        doAnswer(inv -> {
            ChatRequest req = inv.getArgument(1);
            String sys = req.getMessages().get(0).getContent();
            String user = req.getMessages().get(1).getContent();
            if (sys.contains("分类")) return ChatResponse.of("售后", "stop", 5);
            if (sys.contains("售后")) return ChatResponse.of("售后回答:" + user, "stop", 5);
            if (sys.contains("售前")) return ChatResponse.of("售前回答:" + user, "stop", 5);
            return ChatResponse.of("回答:" + user, "stop", 5);
        }).when(mockAdapter).chat(any(), any());
    }

    private WorkflowNodeReq node(String key, String type, String name, Map<String, Object> config) {
        WorkflowNodeReq req = new WorkflowNodeReq();
        req.setNodeKey(key);
        req.setType(type);
        req.setName(name);
        req.setConfig(objectMapper.convertValue(config, JsonNode.class));
        return req;
    }

    private WorkflowEdgeReq edge(String from, String to, String condition) {
        WorkflowEdgeReq req = new WorkflowEdgeReq();
        req.setSourceNodeKey(from);
        req.setTargetNodeKey(to);
        req.setCondition(condition);
        return req;
    }

    private WorkflowDetailVO createLinearFlow() {
        WorkflowCreateRequest req = new WorkflowCreateRequest();
        req.setName("线性工作流");
        req.setDescription("test");
        req.setNodes(List.of(
                node("start", "START", "开始", Map.of()),
                node("echo", "LLM", "回显", Map.of("modelConfigId", 1L,
                        "prompt", "回显用户消息", "outputVariable", "answer")),
                node("end", "END", "结束", Map.of("outputVariable", "answer"))));
        req.setEdges(List.of(
                edge("start", "echo", null),
                edge("echo", "end", null)));
        return workflowService.create(req);
    }

    private WorkflowDetailVO createBranchFlow() {
        WorkflowCreateRequest req = new WorkflowCreateRequest();
        req.setName("分类工作流");
        req.setDescription("test");
        req.setNodes(List.of(
                node("start", "START", "开始", Map.of()),
                node("classify", "LLM", "问题分类", Map.of("modelConfigId", 1L,
                        "prompt", "你是意图分类器", "outputVariable", "intent")),
                node("router", "CONDITION", "路由分发", Map.of(
                        "expression", "{{classify.intent}}", "outputVariable", "route")),
                node("aftersale", "LLM", "售后服务", Map.of("modelConfigId", 1L,
                        "prompt", "你是售后客服", "outputVariable", "answer")),
                node("presale", "LLM", "售前咨询", Map.of("modelConfigId", 1L,
                        "prompt", "你是售前顾问", "outputVariable", "answer")),
                node("end", "END", "结束", Map.of("outputVariable", "answer"))));
        req.setEdges(List.of(
                edge("start", "classify", null),
                edge("classify", "router", null),
                edge("router", "aftersale", "售后"),
                edge("router", "presale", "售前"),
                edge("aftersale", "end", null),
                edge("presale", "end", null)));
        return workflowService.create(req);
    }

    @Test
    @DisplayName("线性执行：START→LLM→END，输出正确，run 与 nodeRun 均落库 SUCCESS")
    void linearFlow_executesAndRecords() {
        Long id = createLinearFlow().getId();

        String output = workflowEngine.execute(id, "你好");

        assertThat(output).isEqualTo("回答:你好");

        WorkflowRun run = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, id)).get(0);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(run.getOutput()).isEqualTo("回答:你好");
        assertThat(run.getInput()).isEqualTo("你好");

        List<WorkflowNodeRun> nodeRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, run.getId()));
        assertThat(nodeRuns).hasSize(1); // START 无独立记录，仅 echo
        assertThat(nodeRuns.get(0).getNodeKey()).isEqualTo("echo");
        assertThat(nodeRuns.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(nodeRuns.get(0).getElapsedMs()).isNotNull();
    }

    @Test
    @DisplayName("条件分支：intent=售后 走 aftersale 分支，输出售后回答")
    void branchFlow_routesByCondition() {
        Long id = createBranchFlow().getId();

        String output = workflowEngine.execute(id, "我买的耳机坏了");

        assertThat(output).startsWith("售后回答:");

        WorkflowRun run = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, id)).get(0);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");

        List<WorkflowNodeRun> nodeRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, run.getId()));
        // classify + router + aftersale 三条记录，presale 不执行
        assertThat(nodeRuns).hasSize(3);
        assertThat(nodeRuns.stream().map(WorkflowNodeRun::getNodeKey))
                .containsExactly("classify", "router", "aftersale");
    }

    @Test
    @DisplayName("节点失败：nodeRun 与 run 均 FAILED，外层抛 BizException")
    void nodeFailure_marksRunFailed() {
        Long id = createLinearFlow().getId();
        doAnswer(inv -> {
            throw new RuntimeException("LLM 超时");
        }).when(mockAdapter).chat(any(), any());

        assertThatThrownBy(() -> workflowEngine.execute(id, "hi"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("LLM 超时");

        WorkflowRun run = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, id)).get(0);
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getError()).contains("LLM 超时");

        List<WorkflowNodeRun> nodeRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, run.getId()));
        assertThat(nodeRuns).hasSize(1);
        assertThat(nodeRuns.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(nodeRuns.get(0).getError()).contains("LLM 超时");
    }

    @Test
    @DisplayName("死循环保护：自环节点触发 50 步上限，run FAILED")
    void cycle_hitsStepLimit() {
        WorkflowCreateRequest req = new WorkflowCreateRequest();
        req.setName("死循环工作流");
        req.setNodes(List.of(
                node("start", "START", "开始", Map.of()),
                node("loop", "LLM", "循环", Map.of("modelConfigId", 1L,
                        "prompt", "loop", "outputVariable", "x")),
                node("end", "END", "结束", Map.of())));
        req.setEdges(List.of(
                edge("start", "loop", null),
                edge("loop", "loop", null), // 自环
                edge("loop", "end", null)));
        Long id = workflowService.create(req).getId();

        assertThatThrownBy(() -> workflowEngine.execute(id, "hi"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("死循环");

        WorkflowRun run = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, id)).get(0);
        assertThat(run.getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("条件都不匹配且无默认边：流程结束，输出最后写入的变量")
    void noMatchingCondition_endsGracefully() {
        WorkflowCreateRequest req = new WorkflowCreateRequest();
        req.setName("无匹配工作流");
        req.setNodes(List.of(
                node("start", "START", "开始", Map.of()),
                node("classify", "LLM", "分类", Map.of("modelConfigId", 1L,
                        "prompt", "你是意图分类器", "outputVariable", "intent")),
                node("router", "CONDITION", "路由", Map.of(
                        "expression", "{{classify.intent}}", "outputVariable", "route")),
                node("presale", "LLM", "售前", Map.of("modelConfigId", 1L,
                        "prompt", "你是售前顾问", "outputVariable", "answer")),
                node("end", "END", "结束", Map.of("outputVariable", "answer"))));
        req.setEdges(List.of(
                edge("start", "classify", null),
                edge("classify", "router", null),
                edge("router", "presale", "售前"))); // 只匹配"售前"，classify 返回"售后"
        Long id = workflowService.create(req).getId();

        String output = workflowEngine.execute(id, "hi");

        // classify 写入了 intent=售后，流程停在 router，run SUCCESS，输出兜底为最后变量
        assertThat(output).isEqualTo("售后");
        WorkflowRun run = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, id)).get(0);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("END 节点读取 outputVariable 对应的 ctx 值作为最终输出")
    void endNode_resolvesOutputVariable() {
        Long id = createLinearFlow().getId();

        String output = workflowEngine.execute(id, "测试输出变量");

        assertThat(output).isEqualTo("回答:测试输出变量");
    }
}
