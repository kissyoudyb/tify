package com.phadcalc.llm.tify.mcp.refund.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 退款 MCP Server 健康检查。
 *
 * <p>唯一对外的 REST 接口（不暴露业务能力），K8s liveness/readiness probe 用。
 * 真实业务通过 MCP 协议（/sse + /messages）暴露，不走 REST。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}