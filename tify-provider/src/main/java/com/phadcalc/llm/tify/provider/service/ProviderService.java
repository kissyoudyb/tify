package com.phadcalc.llm.tify.provider.service;

import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;
import com.phadcalc.llm.tify.provider.dto.ProviderCreateRequest;
import com.phadcalc.llm.tify.provider.dto.ProviderDetailResponse;
import com.phadcalc.llm.tify.provider.dto.ProviderQueryRequest;
import com.phadcalc.llm.tify.provider.dto.ProviderUpdateRequest;
import com.phadcalc.llm.tify.provider.entity.ModelConfig;
import com.phadcalc.llm.tify.provider.entity.Provider;

public interface ProviderService {

    Provider create(ProviderCreateRequest request);

    Provider update(Long id, ProviderUpdateRequest request);

    void delete(Long id);

    void toggleEnabled(Long id);

    ProviderDetailResponse getDetail(Long id);

    PageResult<ProviderDetailResponse> list(ProviderQueryRequest request);

    /** 跨模块调用：校验 modelConfigId 存在且已启用 */
    ModelConfig getEnabledModelConfigOrThrow(Long modelConfigId);
}
