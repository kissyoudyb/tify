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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
            List<String> remoteModelIds = adapter.listModels(provider, testClient);
            if (remoteModelIds == null || remoteModelIds.isEmpty()) {
                log.debug("provider={} listModels 返回空", provider.getName());
                return;
            }
            // 幂等同步策略：按 (provider_id, model_id) 全量比对，避免反复 INSERT 把已绑定的 model 顶掉。
            // 1) 拉一次本地全部行（含 enabled=0）建立 modelId -> ModelConfig 索引
            // 2) 远端有的：本地 enabled=1 不动；本地 enabled=0 重新启用（保留 id 给 Agent）；本地没有才 INSERT
            // 3) 远端没有的（即已下架）且本地 enabled=1 才置 0
            // 数据库层兜底：model_config (provider_id, model_id) 有 UK，重复 INSERT 会抛 DuplicateKeyException，被 try/catch 吞掉不影响其它行
            List<ModelConfig> localAll = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getProviderId, provider.getId()));

            Map<String, ModelConfig> localByModelId = localAll.stream()
                    .collect(Collectors.toMap(ModelConfig::getModelId, mc -> mc, (a, b) -> a));

            Set<String> remoteSet = new HashSet<>(remoteModelIds);

            int inserted = 0;
            int reEnabled = 0;
            for (String modelId : remoteModelIds) {
                ModelConfig exist = localByModelId.get(modelId);
                if (exist != null) {
                    if (exist.getEnabled() == null || exist.getEnabled() != 1) {
                        ModelConfig update = new ModelConfig();
                        update.setId(exist.getId());
                        update.setEnabled(1);
                        modelConfigMapper.updateById(update);
                        reEnabled++;
                    }
                    continue;   // 远端 + 本地都有：完全不动，保留 id 给 Agent
                }
                ModelConfig mc = new ModelConfig();
                mc.setProviderId(provider.getId());
                mc.setName(modelId);
                mc.setModelId(modelId);
                mc.setContextSize(4096);
                mc.setEnabled(1);
                try {
                    modelConfigMapper.insert(mc);
                    inserted++;
                } catch (Exception e) {
                    // 兜底：UK 冲突（并发 refreshModels 抢同一 (provider_id, model_id)）→ 忽略
                    log.debug("insert model_config skip provider={} modelId={}: {}",
                            provider.getName(), modelId, e.getMessage());
                }
            }

            int disabled = 0;
            for (ModelConfig mc : localAll) {
                if (mc.getEnabled() != null && mc.getEnabled() == 1
                        && !remoteSet.contains(mc.getModelId())) {
                    ModelConfig update = new ModelConfig();
                    update.setId(mc.getId());
                    update.setEnabled(0);
                    modelConfigMapper.updateById(update);
                    disabled++;
                }
            }

            log.info("provider={} 同步 model_config：远端 {}，本地 {}，新增 {}，重新启用 {}，下架 {}",
                    provider.getName(), remoteModelIds.size(), localAll.size(), inserted, reEnabled, disabled);
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
