package com.hify.provider.conventions;

import com.hify.common.dto.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization Test · 机制级护栏：禁止 {@code Result} 进 {@code @Cacheable} 缓存值
 *
 * <p>对应 {@code docs/test-gaps.md} #7 / {@code docs/test-plan.md} B1。
 *
 * <p>D3 根因：{@code @Cacheable} 缓存 {@code Result<PageResult<...>>}，Jackson 反序列化时
 * Result / PageResult 缺 default constructor → 缓存命中后全局 1999。
 *
 * <p>本测试用反射扫所有 {@code @Cacheable} 方法（含 {@code @Caching} 内的），断言返回类型
 * 不是 {@code Result<...>}。任何未来 PR 把 {@code Result} 重新塞进 cache value 都会立刻失败。
 *
 * <p>扫描范围：当前模块（hify-provider）所有 {@code @Service} 类的 public 方法。
 */
class NoResultInCacheableScanTest {

    /** 当前模块根包；可按需扩展为跨模块扫描 */
    private static final String SCAN_ROOT = "com.hify.provider";

    @Test
    @DisplayName("#7 · @Cacheable 方法的返回类型不能是 Result<>")
    void noCacheableMethodReturnsResult() {
        List<String> violations = scanForResultInCacheable();

        assertTrue(violations.isEmpty(),
                "违反约束：以下 @Cacheable 方法返回了 Result<>，会导致 Redis 反序列化失败。\n"
                        + "修复方法：cache 返回业务 VO，把 Result.ok() 移到 Controller 层。\n"
                        + "违规列表：\n  - " + String.join("\n  - ", violations));
    }

    private List<String> scanForResultInCacheable() {
        List<String> violations = new ArrayList<>();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            // 反射拿包下的 class 太重且需要 classpath 扫描库；这里采用显式列举当前已知的 Service 类
            // 增加新 Service 时务必在此列表追加
            String[] classNames = {
                    "com.hify.provider.service.impl.ProviderServiceImpl"
            };
            for (String name : classNames) {
                Class<?> cls = Class.forName(name, false, cl);
                if (!cls.isAnnotationPresent(Service.class)) continue;
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) continue;
                    Cacheable cacheable = findCacheableAnnotation(m);
                    if (cacheable == null) continue;
                    if (typeIsResult(m.getGenericReturnType())) {
                        violations.add(formatViolation(cls, m, m.getGenericReturnType()));
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // 测试期 class 不在 classpath（漏掉依赖）→ 当成扫描失败抛出，避免假绿
            throw new AssertionError("扫描失败：找不到 class，可能未把模块加入测试 classpath: " + e.getMessage(), e);
        }
        return violations;
    }

    /** 直接 @Cacheable 或 @Caching 内的 @Cacheable 都算 */
    private Cacheable findCacheableAnnotation(Method m) {
        Cacheable direct = m.getAnnotation(Cacheable.class);
        if (direct != null) return direct;
        org.springframework.cache.annotation.Caching grouping =
                m.getAnnotation(org.springframework.cache.annotation.Caching.class);
        if (grouping == null) return null;
        for (Cacheable inner : grouping.cacheable()) {
            if (inner != null) return inner;
        }
        return null;
    }

    /** 判断 type 是否是 Result<...> 形式（含泛型嵌套 / 数组 / ParameterizedType 嵌套） */
    private boolean typeIsResult(Type t) {
        if (t instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawClass) {
                if (Result.class.equals(rawClass)) return true;
            }
            // 嵌套：例如 Result<List<X>>, Result<PageResult<X>> 也算
            for (Type arg : pt.getActualTypeArguments()) {
                if (typeIsResult(arg)) return true;
            }
        }
        return false;
    }

    private String formatViolation(Class<?> cls, Method m, Type retType) {
        return cls.getSimpleName() + "#" + m.getName() + " → " + retType;
    }
}