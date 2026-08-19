package com.phadcalc.llm.tify.workflow.controller;

import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;
import com.phadcalc.llm.tify.workflow.dto.*;
import com.phadcalc.llm.tify.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public Result<WorkflowDetailVO> create(@RequestBody @Valid WorkflowCreateRequest request) {
        return Result.ok(workflowService.create(request));
    }

    @GetMapping
    public Result<PageResult<WorkflowListItem>> list(WorkflowQueryRequest request) {
        return workflowService.list(request);
    }

    @GetMapping("/{id}")
    public Result<WorkflowDetailVO> getDetail(@PathVariable Long id) {
        return Result.ok(workflowService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<WorkflowDetailVO> update(@PathVariable Long id,
                                           @RequestBody @Valid WorkflowUpdateRequest request) {
        return Result.ok(workflowService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        workflowService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/runs")
    public Result<PageResult<WorkflowRunVO>> listRuns(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        return workflowService.listRuns(id, page, pageSize);
    }

    @GetMapping("/{id}/runs/latest")
    public Result<WorkflowRunDetailVO> getLatestRun(@PathVariable Long id) {
        return Result.ok(workflowService.getLatestRun(id));
    }

    @GetMapping("/{id}/runs/{runId}")
    public Result<WorkflowRunDetailVO> getRun(@PathVariable Long id, @PathVariable Long runId) {
        return Result.ok(workflowService.getRun(id, runId));
    }
}
