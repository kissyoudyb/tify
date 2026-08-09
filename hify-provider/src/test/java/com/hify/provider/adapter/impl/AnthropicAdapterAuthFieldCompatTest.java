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
 * Characterization Test · AnthropicAdapter 鉴权字段命名兼容性
 *
 * <p>对应 {@code docs/test-gaps.md} #3 / {@code docs/test-plan.md} B2。
 * <p>AnthropicAdapter 自带一份 {@code getAuth(Map, String)} 私有方法，
 * 行为应当与 OpenAiAdapter 一致（同样支持 snake_case 兜底）。
 */
class AnthropicAdapterAuthFieldCompatTest {

    private final AnthropicAdapter adapter = new AnthropicAdapter(
            new LlmHttpClient(),
            new ObjectMapper()
    );

    @Test
    @DisplayName("anthropic #3a · apiKey camelCase 优先")
    void camelCaseKey_isAccepted() {
        Map<String, Object> auth = new HashMap<>();
        auth.put("apiKey", "sk-ant-camel");
        Provider p = new Provider();
        p.setType("ANTHROPIC");
        p.setBaseUrl("https://api.anthropic.com");
        p.setAuthConfig(auth);

        // 调用 authHeaders()，里头走私有 getAuth；通过抓 header 间接验证
        // Anthropic 的 authHeaders 是 private，改用反射 or 直接构造请求体
        // 这里直接调用 OpenAiAdapter 的同形态 getAuth 不可见，
        // 改为 reflection 调 AnthropicAdapter 的 private getAuth
        String got = (String) invokeGetAuth(adapter, auth, "apiKey");
        assertEquals("sk-ant-camel", got);
    }

    @Test
    @DisplayName("anthropic #3b · api_key snake_case 也接受")
    void snakeCaseKey_isAccepted() {
        Map<String, Object> auth = new HashMap<>();
        auth.put("api_key", "sk-ant-snake");
        String got = (String) invokeGetAuth(adapter, auth, "apiKey");
        assertEquals("sk-ant-snake", got);
    }

    @Test
    @DisplayName("anthropic #3c · 缺字段抛异常，消息含 'apiKey'")
    void missingKey_throwsWithFieldName() {
        Map<String, Object> auth = new HashMap<>();
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> invokeGetAuth(adapter, auth, "apiKey"));
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    private Object invokeGetAuth(AnthropicAdapter adapter, Map<String, Object> auth, String key) {
        try {
            var m = AnthropicAdapter.class.getDeclaredMethod("getAuth", Map.class, String.class);
            m.setAccessible(true);
            return m.invoke(adapter, auth, key);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 反射会把目标异常包成 InvocationTargetException，这里解开重新抛
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}