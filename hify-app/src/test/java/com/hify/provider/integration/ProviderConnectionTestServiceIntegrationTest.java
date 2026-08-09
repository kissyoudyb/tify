package com.hify.provider.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.HifyApplication;
import com.hify.provider.adapter.ProviderAdapter;
import com.hify.provider.adapter.ProviderAdapterFactory;
import com.hify.provider.dto.ConnectionTestResult;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.entity.ProviderHealth;
import com.hify.provider.mapper.ModelConfigMapper;
import com.hify.provider.mapper.ProviderHealthMapper;
import com.hify.provider.mapper.ProviderMapper;
import com.hify.provider.service.ProviderConnectionTestService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = HifyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Import(ProviderAdapterFactoryOverride.class)
@Rollback(false)
@Transactional(propagation = Propagation.NEVER)
class ProviderConnectionTestServiceIntegrationTest {

    @Autowired ProviderConnectionTestService service;
    @Autowired ProviderMapper providerMapper;
    @Autowired ProviderHealthMapper healthMapper;
    @Autowired ModelConfigMapper modelConfigMapper;
    @Autowired CacheManager cacheManager;
    @Autowired ApplicationContext ctx;

    private ProviderAdapter adapterMock = mock(ProviderAdapter.class);
    private ProviderAdapterFactory adapterFactory;

    private Provider inserted;

    @BeforeEach
    void setUp() {
        adapterFactory = ctx.getBean("providerAdapterFactoryOverride", ProviderAdapterFactory.class);
        injectMockFactoryIntoService(service, adapterFactory);
        when(adapterFactory.get(any())).thenReturn(adapterMock);

        Provider p = new Provider();
        p.setName("test-provider-" + System.nanoTime());
        p.setType("OPENAI");
        p.setBaseUrl("https://api.openai.com/v1");
        Map<String, Object> auth = new HashMap<>();
        auth.put("apiKey", "sk-fake-test");
        p.setAuthConfig(auth);
        p.setDescription("");
        p.setEnabled(1);
        providerMapper.insert(p);
        this.inserted = p;
    }

    @AfterEach
    void tearDown() {
        if (inserted != null && inserted.getId() != null) {
            try {
                providerMapper.deleteById(inserted.getId());
            } catch (Exception ignored) {}
        }
        Cache cache = cacheManager.getCache("provider-cache");
        if (cache != null) cache.clear();
    }

    private void injectMockFactoryIntoService(ProviderConnectionTestService svc, ProviderAdapterFactory mock) {
        try {
            var f = ProviderConnectionTestService.class.getDeclaredField("adapterFactory");
            f.setAccessible(true);
            f.set(svc, mock);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("#1a · success 路径：provider_health 新增一行 status=UP, failCount=0, latencyMs=正确")
    void success_writesHealthRecord() {
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.ok(42, 2));
        when(adapterMock.listModels(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(List.of("gpt-test-1", "gpt-test-2"));

        ConnectionTestResult result = service.test(inserted);

        assertTrue(result.isSuccess(), "实际失败：" + result.getErrorMessage());
        assertEquals(42, result.getLatencyMs());

        ProviderHealth health = healthMapper.findByProviderId(inserted.getId()).orElse(null);
        assertNotNull(health, "成功路径必须写入 provider_health 行");
        assertEquals("UP", health.getStatus());
        assertEquals(0, health.getFailCount());
        assertEquals(42, health.getLatencyMs());
        assertNotNull(health.getLastSuccessAt());
        assertTrue(health.getLastCheckAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    @DisplayName("#1b · success 路径：model_config 先 disabled 旧的，再按 listModels 返回值插入新的（enabled=1）")
    void success_syncsModelConfig() {
        ModelConfig old = new ModelConfig();
        old.setProviderId(inserted.getId());
        old.setName("old-model");
        old.setModelId("old-model-id");
        old.setContextSize(4096);
        old.setEnabled(1);
        modelConfigMapper.insert(old);

        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.ok(10, 2));
        when(adapterMock.listModels(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(List.of("new-model-a", "new-model-b"));

        service.test(inserted);

        ModelConfig oldAfter = modelConfigMapper.selectById(old.getId());
        assertNotNull(oldAfter);
        assertEquals(0, oldAfter.getEnabled(), "旧 model_config 应被置为 enabled=0");

        List<ModelConfig> enabled = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getProviderId, inserted.getId())
                        .eq(ModelConfig::getEnabled, 1));
        assertEquals(2, enabled.size());
        assertTrue(enabled.stream().anyMatch(m -> "new-model-a".equals(m.getModelId())));
        assertTrue(enabled.stream().anyMatch(m -> "new-model-b".equals(m.getModelId())));
    }

    @Test
    @DisplayName("#1c · success 路径：provider-cache::{id} 和 provider-cache::list 都被 evict")
    void success_evictsCache() {
        Cache cache = cacheManager.getCache("provider-cache");
        assertNotNull(cache);
        cache.put(inserted.getId(), "placeholder");
        cache.put("list", "placeholder");
        assertNotNull(cache.get(inserted.getId()));
        assertNotNull(cache.get("list"));

        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.ok(20, 1));
        when(adapterMock.listModels(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(List.of("m1"));

        service.test(inserted);

        assertEquals(null, cache.get(inserted.getId()),
                "provider-cache::{id} 应被 evict");
        assertEquals(null, cache.get("list"),
                "provider-cache::list 应被 evict");
    }

    // ───── B6 · 失败状态机 ────────────────────────────────────────

    @Test
    @DisplayName("#6a · 单次失败：status=DEGRADED, failCount=1")
    void failStateMachine_oneFailure() {
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.fail("timeout-1"));
        service.test(inserted);

        ProviderHealth h = healthMapper.findByProviderId(inserted.getId()).orElseThrow();
        assertEquals("DEGRADED", h.getStatus());
        assertEquals(1, h.getFailCount());
        assertEquals("timeout-1", h.getErrorMessage());
    }

    @Test
    @DisplayName("#6b · 连续 3 次失败：第 3 次 status=DOWN, failCount=3（熔断阈值）")
    void failStateMachine_threeFailuresSwitchToDown() {
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.fail("err-timeout"));

        service.test(inserted);  // 1
        assertEquals("DEGRADED", healthMapper.findByProviderId(inserted.getId()).orElseThrow().getStatus());
        service.test(inserted);  // 2
        assertEquals("DEGRADED", healthMapper.findByProviderId(inserted.getId()).orElseThrow().getStatus());
        service.test(inserted);  // 3
        ProviderHealth h = healthMapper.findByProviderId(inserted.getId()).orElseThrow();
        assertEquals("DOWN", h.getStatus());
        assertEquals(3, h.getFailCount());
    }

    @Test
    @DisplayName("#6c · 连续 5 次失败：failCount 单调递增到 5（不重计）")
    void failStateMachine_countsAccumulate() {
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.fail("err-timeout"));

        for (int i = 1; i <= 5; i++) {
            service.test(inserted);
        }
        ProviderHealth h = healthMapper.findByProviderId(inserted.getId()).orElseThrow();
        assertEquals(5, h.getFailCount(), "failCount 必须等连续失败次数");
        assertEquals("DOWN", h.getStatus());
    }

    @Test
    @DisplayName("#6d · 失败→成功→失败：成功重置 failCount，再失败时从 1 起")
    void failStateMachine_successResetsFailCount() {
        // 第 1 次失败 → failCount=1
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.fail("err-timeout"));
        service.test(inserted);
        assertEquals(1, healthMapper.findByProviderId(inserted.getId()).orElseThrow().getFailCount());

        // 成功 → failCount=0, status=UP
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.ok(30, 1));
        service.test(inserted);
        ProviderHealth afterSuccess = healthMapper.findByProviderId(inserted.getId()).orElseThrow();
        assertEquals(0, afterSuccess.getFailCount());
        assertEquals("UP", afterSuccess.getStatus());

        // 再失败 → failCount=1（不是 2）
        when(adapterMock.testConnection(any(Provider.class), any(OkHttpClient.class)))
                .thenReturn(ConnectionTestResult.fail("timeout-2"));
        service.test(inserted);
        ProviderHealth h = healthMapper.findByProviderId(inserted.getId()).orElseThrow();
        assertEquals(1, h.getFailCount(), "成功重置后再失败，failCount 应从 1 重新计数");
        assertEquals("DEGRADED", h.getStatus());
    }
}
