package com.phadcalc.llm.tify.provider.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.phadcalc.llm.tify.app.TifyApplication;
import com.phadcalc.llm.tify.provider.dto.ProviderDetailResponse;
import com.phadcalc.llm.tify.provider.dto.ProviderQueryRequest;
import com.phadcalc.llm.tify.provider.entity.ModelConfig;
import com.phadcalc.llm.tify.provider.entity.Provider;
import com.phadcalc.llm.tify.provider.mapper.ModelConfigMapper;
import com.phadcalc.llm.tify.provider.mapper.ProviderMapper;
import com.phadcalc.llm.tify.provider.service.ProviderService;
import com.phadcalc.llm.tify.common.dto.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试 · B4 · ProviderService.list() 与 getDetail() 返回的 models 必须一致
 *
 * 修复历史：之前 list() 没加 enabled=1 过滤，前端页面"外面显示"和"详情里"看到的数量不一致 (D-修复)。
 * 本测试锁住契约：两个接口都只返回 enabled=1 的 model_config。
 */
@SpringBootTest(
        classes = TifyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class ProviderServiceListDetailConsistencyTest {

    @Autowired ProviderService service;
    @Autowired ProviderMapper providerMapper;
    @Autowired ModelConfigMapper modelConfigMapper;
    @Autowired CacheManager cacheManager;

    private Provider inserted;

    @BeforeEach
    void setUp() {
        // 清缓存避免 @Cacheable 跨测试污染
        Cache cache = cacheManager.getCache("provider-cache");
        if (cache != null) cache.clear();

        Provider p = new Provider();
        p.setName("b4-provider-" + System.nanoTime());
        p.setType("OPENAI");
        p.setBaseUrl("https://api.openai.com/v1");
        Map<String, Object> auth = new HashMap<>();
        auth.put("apiKey", "sk-fake-b4");
        p.setAuthConfig(auth);
        p.setDescription("");
        p.setEnabled(1);
        providerMapper.insert(p);
        this.inserted = p;

        // 准备 2 个 enabled=1 + 1 个 enabled=0 的 model_config
        ModelConfig m1 = mkModel(inserted.getId(), "gpt-4o", 1);
        ModelConfig m2 = mkModel(inserted.getId(), "gpt-4o-mini", 1);
        ModelConfig m0 = mkModel(inserted.getId(), "gpt-3.5-turbo-old", 0);
        modelConfigMapper.insert(m1);
        modelConfigMapper.insert(m2);
        modelConfigMapper.insert(m0);
    }

    @AfterEach
    void tearDown() {
        if (inserted != null && inserted.getId() != null) {
            try {
                modelConfigMapper.delete(
                    new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getProviderId, inserted.getId()));
                providerMapper.deleteById(inserted.getId());
            } catch (Exception ignored) {}
        }
        Cache cache = cacheManager.getCache("provider-cache");
        if (cache != null) cache.clear();
    }

    private ModelConfig mkModel(Long providerId, String modelId, int enabled) {
        ModelConfig m = new ModelConfig();
        m.setProviderId(providerId);
        m.setName(modelId);
        m.setModelId(modelId);
        m.setContextSize(4096);
        m.setEnabled(enabled);
        return m;
    }

    @Test
    @DisplayName("#2a · list() 返回的 models 只含 enabled=1（与 getDetail 一致）")
    void list_returnsOnlyEnabledModels() {
        PageResult<ProviderDetailResponse> page = service.list(new ProviderQueryRequest());

        ProviderDetailResponse myRow = page.getList().stream()
                .filter(p -> p.getId().equals(inserted.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("list() 应包含新插入的 provider"));

        assertNotNull(myRow.getModels(), "list() 应返回 models 字段");
        assertEquals(2, myRow.getModels().size(), "list() 应只返回 enabled=1 的 2 个 model");
        Set<String> ids = myRow.getModels().stream().map(ModelConfig::getModelId).collect(Collectors.toSet());
        assertTrue(ids.contains("gpt-4o"), "应包含 gpt-4o");
        assertTrue(ids.contains("gpt-4o-mini"), "应包含 gpt-4o-mini");
        assertTrue(!ids.contains("gpt-3.5-turbo-old"), "disabled model 不应在 list() 出现");
    }

    @Test
    @DisplayName("#2b · getDetail() 返回的 models 只含 enabled=1")
    void getDetail_returnsOnlyEnabledModels() {
        ProviderDetailResponse detail = service.getDetail(inserted.getId());

        assertNotNull(detail.getModels());
        assertEquals(2, detail.getModels().size());
        Set<String> ids = detail.getModels().stream().map(ModelConfig::getModelId).collect(Collectors.toSet());
        assertTrue(ids.contains("gpt-4o"));
        assertTrue(ids.contains("gpt-4o-mini"));
        assertTrue(!ids.contains("gpt-3.5-turbo-old"));
    }

    @Test
    @DisplayName("#2c · list() 与 getDetail() 返回的 models 完全一致（集合相等）")
    void list_andGetDetail_returnSameModelSet() {
        // 第一次 lookup hit DB
        cacheManager.getCache("provider-cache").clear();
        PageResult<ProviderDetailResponse> page = service.list(new ProviderQueryRequest());
        ProviderDetailResponse fromList = page.getList().stream()
                .filter(p -> p.getId().equals(inserted.getId()))
                .findFirst().orElseThrow();

        cacheManager.getCache("provider-cache").clear();
        ProviderDetailResponse fromDetail = service.getDetail(inserted.getId());

        Set<String> listIds = fromList.getModels().stream().map(ModelConfig::getModelId).collect(Collectors.toSet());
        Set<String> detailIds = fromDetail.getModels().stream().map(ModelConfig::getModelId).collect(Collectors.toSet());

        assertEquals(listIds, detailIds, "list() 和 getDetail() 返回的 model set 必须一致");
    }
}
