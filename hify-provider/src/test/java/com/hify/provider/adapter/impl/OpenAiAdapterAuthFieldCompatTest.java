package com.hify.provider.adapter.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmHttpClient;
import com.hify.provider.entity.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization Test · OpenAiAdapter.getAuth() 鉴权字段命名兼容性
 *
 * <p>对应 {@code docs/test-gaps.md} #3 / {@code docs/test-plan.md} B2。
 * <p>锁定"前端可同时传 camelCase (apiKey) 和 snake_case (api_key)"的事实约束。
 * 任何人未来"统一规范"为单 key 都会让已上线的前端代码崩。
 */
class OpenAiAdapterAuthFieldCompatTest {

    private final OpenAiAdapter adapter = new OpenAiAdapter(
            new LlmHttpClient(),
            new ObjectMapper()
    );

    private Provider providerWithAuth(Map<String, Object> auth) {
        Provider p = new Provider();
        p.setType("OPENAI");
        p.setBaseUrl("https://api.openai.com/v1");
        p.setAuthConfig(auth);
        return p;
    }

    @Test
    @DisplayName("#3a · auth_config={\"apiKey\":\"sk-xxx\"} → 返回 sk-xxx")
    void camelCaseKey_isAccepted() {
        Map<String, Object> auth = new HashMap<>();
        auth.put("apiKey", "sk-camel-123");
        String got = adapter.getAuth(providerWithAuth(auth), "apiKey");
        assertEquals("sk-camel-123", got);
    }

    @Test
    @DisplayName("#3b · auth_config={\"api_key\":\"sk-xxx\"} → 返回 sk-xxx")
    void snakeCaseKey_isAccepted() {
        Map<String, Object> auth = new HashMap<>();
        auth.put("api_key", "sk-snake-456");
        String got = adapter.getAuth(providerWithAuth(auth), "apiKey");
        assertEquals("sk-snake-456", got);
    }

    @Test
    @DisplayName("#3c · 两个 key 都没有 → 抛 IllegalArgumentException，消息含 'apiKey'")
    void missingKey_throwsWithFieldName() {
        Map<String, Object> auth = new HashMap<>();
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.getAuth(providerWithAuth(auth), "apiKey")
        );
        assertTrue(ex.getMessage().contains("apiKey"),
                "异常消息应包含字段名 'apiKey'，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("#3d · auth_config 是 null → 抛 IllegalArgumentException")
    void nullAuthConfig_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.getAuth(providerWithAuth(null), "apiKey")
        );
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    @Test
    @DisplayName("#3e · camelCase 优先（两个都有时返回 camelCase）")
    void bothKeys_camelCaseWins() {
        Map<String, Object> auth = new HashMap<>();
        auth.put("apiKey", "sk-camel");
        auth.put("api_key", "sk-snake");
        String got = adapter.getAuth(providerWithAuth(auth), "apiKey");
        assertEquals("sk-camel", got);
    }
}