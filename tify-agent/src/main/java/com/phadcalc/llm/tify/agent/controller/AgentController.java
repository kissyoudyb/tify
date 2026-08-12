package com.phadcalc.llm.tify.agent.controller;

import com.phadcalc.llm.tify.agent.dto.*;
import com.phadcalc.llm.tify.agent.service.AgentService;
import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping
    public Result<AgentDetailResponse> create(@Valid @RequestBody AgentCreateRequest request) {
        return Result.ok(agentService.create(request));
    }

    @GetMapping
    public Result<PageResult<AgentListItem>> list(AgentQueryRequest request) {
        return Result.ok(agentService.list(request));
    }

    @GetMapping("/{id}")
    public Result<AgentDetailResponse> detail(@PathVariable Long id) {
        return Result.ok(agentService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<AgentDetailResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody AgentUpdateRequest request) {
        return Result.ok(agentService.update(id, request));
    }

    @PutMapping("/{id}/tools")
    public Result<Void> bindTools(@PathVariable Long id,
                                  @Valid @RequestBody AgentToolBindRequest request) {
        agentService.bindTools(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return Result.ok();
    }
}
