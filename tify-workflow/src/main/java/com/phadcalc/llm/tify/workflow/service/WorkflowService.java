package com.phadcalc.llm.tify.workflow.service;

import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;
import com.phadcalc.llm.tify.workflow.dto.*;

public interface WorkflowService {

    WorkflowDetailVO create(WorkflowCreateRequest request);

    Result<PageResult<WorkflowListItem>> list(WorkflowQueryRequest request);

    WorkflowDetailVO getDetail(Long id);

    WorkflowDetailVO update(Long id, WorkflowUpdateRequest request);

    void delete(Long id);

    /** 分页查询执行记录（按时间倒序） */
    Result<PageResult<WorkflowRunVO>> listRuns(Long workflowId, int page, int pageSize);

    /** 最近一次执行记录（含节点明细），不存在时返回 null */
    WorkflowRunDetailVO getLatestRun(Long workflowId);

    /** 指定执行记录（含节点明细），不存在时返回 null */
    WorkflowRunDetailVO getRun(Long workflowId, Long runId);
}
