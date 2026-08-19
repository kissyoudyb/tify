package com.phadcalc.llm.tify.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNodeRun;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowNodeRunVO {
    private Long id;
    private Long workflowRunId;
    private String nodeKey;
    private String nodeType;
    /** RUNNING / SUCCESS / FAILED */
    private String status;
    /** 变量池快照 JSON */
    private JsonNode outputs;
    private String error;
    private Integer elapsedMs;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public static WorkflowNodeRunVO from(WorkflowNodeRun nodeRun, ObjectMapper mapper) {
        WorkflowNodeRunVO vo = new WorkflowNodeRunVO();
        vo.setId(nodeRun.getId());
        vo.setWorkflowRunId(nodeRun.getWorkflowRunId());
        vo.setNodeKey(nodeRun.getNodeKey());
        vo.setNodeType(nodeRun.getNodeType());
        vo.setStatus(nodeRun.getStatus());
        try {
            String out = nodeRun.getOutputs();
            vo.setOutputs(out != null && !out.isBlank()
                    ? mapper.readTree(out)
                    : mapper.createObjectNode());
        } catch (Exception e) {
            vo.setOutputs(mapper.createObjectNode());
        }
        vo.setError(nodeRun.getError());
        vo.setElapsedMs(nodeRun.getElapsedMs());
        vo.setCreatedAt(nodeRun.getCreatedAt());
        vo.setFinishedAt(nodeRun.getFinishedAt());
        return vo;
    }
}
