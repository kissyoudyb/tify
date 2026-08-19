package com.phadcalc.llm.tify.agent.service;

import com.phadcalc.llm.tify.agent.dto.*;
import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;

public interface AgentService {

    AgentDetailResponse create(AgentCreateRequest request);

    AgentDetailResponse update(Long id, AgentUpdateRequest request);

    void bindTools(Long id, AgentToolBindRequest request);

    void delete(Long id);

    AgentDetailResponse getDetail(Long id);

    PageResult<AgentListItem> list(AgentQueryRequest request);
}
