package com.phadcalc.llm.tify.mcp.refund.config;

import com.phadcalc.llm.tify.mcp.refund.tool.RefundToolRegistrar;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MCP Server 端点注册。
 *
 * <p>对外暴露：
 * <ul>
 *   <li>GET  /sse      — Client 发起长连接，Server 推 sessionId</li>
 *   <li>POST /messages — Client 发 JSON-RPC 请求（tools/list, tools/call 等）</li>
 * </ul>
 *
 * <p>关键点：0.18.3 里 {@link WebMvcSseServerTransportProvider} 只实现
 * {@code McpServerTransportProvider}，<b>不会自动把路由注册到 Spring MVC</b>。
 * 必须把它内置的 {@link RouterFunction} 作为 Bean 暴露出去，
 * Spring Boot 的 {@code RouterFunctionMapping} 才会自动拾取。
 *
 * <p>4 个退款工具通过 {@link RefundToolRegistrar} 在 builder 阶段链入。
 */
@Configuration
@RequiredArgsConstructor
public class McpServerConfig {

    private final RefundToolRegistrar toolRegistrar;

    @Bean
    public WebMvcSseServerTransportProvider mcpSseTransport() {
        return WebMvcSseServerTransportProvider.builder()
                // SDK 默认 sseEndpoint="/sse"、messageEndpoint="/messages"
                // 显式写明，与 tify-mcp Client 端保持一致
                .sseEndpoint("/sse")
                .messageEndpoint("/messages")
                .build();
    }

    /**
     * 把 transport 自带的 RouterFunction 暴露成 Spring Bean，
     * RouterFunctionMapping 自动拾取并注册 /sse 和 /messages 两个端点。
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcSseServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    public McpSyncServer mcpSyncServer(WebMvcSseServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("refund-mcp-server", "1.0.0"))
                .tools(toolRegistrar.specs())
                .build();
    }
}