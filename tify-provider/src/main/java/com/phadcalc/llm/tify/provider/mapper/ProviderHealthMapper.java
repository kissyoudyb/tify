package com.phadcalc.llm.tify.provider.mapper;

import com.phadcalc.llm.tify.common.mapper.BaseMapper;
import com.phadcalc.llm.tify.provider.entity.ProviderHealth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ProviderHealthMapper extends BaseMapper<ProviderHealth> {

    @Select("SELECT * FROM provider_health WHERE provider_id = #{providerId}")
    Optional<ProviderHealth> findByProviderId(Long providerId);
}
