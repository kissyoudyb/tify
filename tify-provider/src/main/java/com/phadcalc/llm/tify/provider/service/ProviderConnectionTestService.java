package com.phadcalc.llm.tify.provider.service;

import com.phadcalc.llm.tify.common.exception.BizException;
import com.phadcalc.llm.tify.common.exception.ErrorCode;
import com.phadcalc.llm.tify.provider.adapter.ProviderAdapter;
import com.phadcalc.llm.tify.provider.adapter.ProviderAdapterFactory;
import com.phadcalc.llm.tify.provider.dto.ConnectionTestResult;
import com.phadcalc.llm.tify.provider.entity.ModelConfig;
import com.phadcalc.llm.tify.provider.entity.Provider;
import com.phadcalc.llm.tify.provider.entity.ProviderHealth;
import com.phadcalc.llm.tify.provider.mapper.ModelConfigMapper;
import com.phadcalc.llm.tify.provider.mapper.ProviderHealthMapper;
import com.phadcalc.llm.tify.provider.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConnectionTestService {

    private final ProviderAdapterFactory adapterFactory;
    private final ProviderMapper providerMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final ProviderHealthMapper providerHealthMapper;
    private final CacheManager cacheManager;

    /** 连通性测试专用 10s 超时客户端 */
    private final OkHttpClient testClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public ConnectionTestResult testById(Long id) {
        Provider provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new BizException(ErrorCode.PROVIDER_NOT_FOUND);
        }
        return test(provider);
    }

    public ConnectionTestResult test(Provider provider) {
        log.info("连通性测试 provider={} type={}", provider.getName(), provider.getType());
        ProviderAdapter adapter = adapterFactory.get(provider.getType());
        ConnectionTestResult result = adapter.testConnection(provider, testClient);

        // 1. 把测试结果写回 provider_health（无论成功失败，让前端立即看到）
        persistHealth(provider.getId(), result);

        // 2. 成功时刷 model_config 列表（让前端"模型数"立刻显示）
        if (result.isSuccess()) {
            refreshModels(provider, adapter);
        }

        // 3. 主动 evict 缓存，避免 30min TTL 内前端看不到新状态
        evictProviderCache(provider.getId());
        return result;
    }

    private void persistHealth(Long providerId, ConnectionTestResult result) {
        ProviderHealth health = providerHealthMapper.findByProviderId(providerId)
                .orElseGet(() -> {
                    ProviderHealth h = new ProviderHealth();
                    h.setProviderId(providerId);
                    h.setStatus("UNKNOWN");
                    h.setFailCount(0);
                    return h;
                });
        health.setLastCheckAt(LocalDateTime.now());
        if (result.isSuccess()) {
            health.setStatus("UP");
            health.setLatencyMs(result.getLatencyMs());
            health.setLastSuccessAt(LocalDateTime.now());
            health.setFailCount(0);
            health.setErrorMessage(null);
        } else {
            int next = (health.getFailCount() == null ? 0 : health.getFailCount()) + 1;
            health.setFailCount(next);
            health.setErrorMessage(result.getErrorMessage());
            health.setStatus(next >= 3 ? "DOWN" : "DEGRADED");
        }
        if (health.getId() == null) {
            providerHealthMapper.insert(health);
        } else {
            providerHealthMapper.updateById(health);
        }
    }

    private void refreshModels(Provider provider, ProviderAdapter adapter) {
        try {
            List<String> modelIds = adapter.listModels(provider, testClient);
            if (modelIds == null || modelIds.isEmpty()) {
                log.debug("provider={} listModels 返回空", provider.getName());
                return;
            }
            // 简单同步策略：把当前 enabled=1 的 model_config 全部置 0，再按远端列表插入
            ModelConfig update = new ModelConfig();
            update.setEnabled(0);
            modelConfigMapper.update(update,
                    new LambdaUpdateWrapper<ModelConfig>()
                            .eq(ModelConfig::getProviderId, provider.getId())
                            .eq(ModelConfig::getEnabled, 1));
            for (String modelId : modelIds) {
                ModelConfig mc = new ModelConfig();
                mc.setProviderId(provider.getId());
                mc.setName(modelId);
                mc.setModelId(modelId);
                mc.setContextSize(4096);
                mc.setEnabled(1);
                modelConfigMapper.insert(mc);
            }
            log.info("provider={} 同步 model_config，共 {} 个", provider.getName(), modelIds.size());
        } catch (Exception e) {
            log.warn("refreshModels 失败 provider={}: {}", provider.getName(), e.getMessage());
        }
    }

    private void evictProviderCache(Long providerId) {
        try {
            var cache = cacheManager.getCache("provider-cache");
            if (cache != null) {
                cache.evict(providerId);
                cache.evict("list");
            }
        } catch (Exception e) {
            log.warn("evict provider-cache 失败: {}", e.getMessage());
        }
    }
}
