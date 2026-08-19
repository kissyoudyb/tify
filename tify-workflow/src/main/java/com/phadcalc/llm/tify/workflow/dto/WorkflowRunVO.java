package com.phadcalc.llm.tify.workflow.dto;

import com.phadcalc.llm.tify.workflow.entity.WorkflowRun;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowRunVO {
    private Long id;
    private Long workflowId;
    /** RUNNING / SUCCESS / FAILED */
    private String status;
    private String input;
    private String output;
    private String error;
    private Integer elapsedMs;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public static WorkflowRunVO from(WorkflowRun run) {
        WorkflowRunVO vo = new WorkflowRunVO();
        vo.setId(run.getId());
        vo.setWorkflowId(run.getWorkflowId());
        vo.setStatus(run.getStatus());
        vo.setInput(run.getInput());
        vo.setOutput(run.getOutput());
        vo.setError(run.getError());
        vo.setElapsedMs(run.getElapsedMs());
        vo.setCreatedAt(run.getCreatedAt());
        vo.setFinishedAt(run.getFinishedAt());
        return vo;
    }
}
