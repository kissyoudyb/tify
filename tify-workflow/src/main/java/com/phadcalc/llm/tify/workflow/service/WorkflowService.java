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
}
