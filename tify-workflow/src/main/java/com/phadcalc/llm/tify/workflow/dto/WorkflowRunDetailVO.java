package com.phadcalc.llm.tify.workflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class WorkflowRunDetailVO {
    private WorkflowRunVO run;
    private List<WorkflowNodeRunVO> nodeRuns;
}
