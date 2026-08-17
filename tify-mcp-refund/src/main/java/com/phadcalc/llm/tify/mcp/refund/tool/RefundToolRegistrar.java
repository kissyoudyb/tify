package com.phadcalc.llm.tify.mcp.refund.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundCancelResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundEligibilityResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundStatusResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundSubmitResult;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundBizException;
import com.phadcalc.llm.tify.mcp.refund.service.RefundService;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 把 4 个退款工具注册到 McpSyncServer。
 *
 * <p>0.18.3 SDK 的工具注册入口是 {@code McpServer.SyncSpecification#tool(Tool, BiFunction)}，
 * 必须在 {@code build()} 之前链入。本类集中构造 4 个 {@link McpSchema.Tool} 及其 handler，
 * 由 {@link com.phadcalc.llm.tify.mcp.refund.config.McpServerConfig} 在创建
 * {@code McpSyncServer} Bean 时批量注册。
 *
 * <p>handler 策略：捕获业务异常 → 返回 {@code isError=true} + 中文 message，
 * <b>不</b>抛异常给 SDK 避免断 SSE 流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundToolRegistrar {

    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    /**
     * 返回 4 个工具的 {@link McpSchema.Tool} schema 定义。
     * 每个工具的 description 措辞便于 LLM 准确选型（按 25 讲）。
     */
    public List<McpSchema.Tool> tools() {
        return List.of(
                buildCheckEligibilityTool(),
                buildSubmitRefundTool(),
                buildGetStatusTool(),
                buildCancelRefundTool()
        );
    }

    /**
     * 注册工具到 builder，统一异常处理。
     * 调用方：{@code McpServer.SyncSpecification#tools(List<SyncToolSpecification>)}.
     *
     * <p>0.18.3 SDK 的 callHandler 签名是 {@code BiFunction<exchange, CallToolRequest, CallToolResult>}，
     * 因此 handler 直接接 {@link McpSchema.CallToolRequest}，从 {@code request.arguments()} 取参数 Map。
     */
    public List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> specs() {
        return List.of(
                spec(buildCheckEligibilityTool(),
                        (exchange, req) -> safeHandle("check_refund_eligibility",
                                req.arguments(), this::doCheckEligibility)),
                spec(buildSubmitRefundTool(),
                        (exchange, req) -> safeHandle("submit_refund",
                                req.arguments(), this::doSubmitRefund)),
                spec(buildGetStatusTool(),
                        (exchange, req) -> safeHandle("get_refund_status",
                                req.arguments(), this::doGetStatus)),
                spec(buildCancelRefundTool(),
                        (exchange, req) -> safeHandle("cancel_refund",
                                req.arguments(), this::doCancelRefund))
        );
    }

    // ── handler 实现 ──────────────────────────────────────────

    private McpSchema.CallToolResult doCheckEligibility(Map<String, Object> args) {
        String orderId = requiredString(args, "orderId");
        RefundEligibilityResult r = refundService.checkEligibility(orderId);
        return successResult(r);
    }

    private McpSchema.CallToolResult doSubmitRefund(Map<String, Object> args) {
        String orderId = requiredString(args, "orderId");
        String reason = optionalString(args, "reason");
        String userId = optionalString(args, "userId");
        BigDecimal amount = optionalDecimal(args, "amount");
        RefundSubmitResult r = refundService.submitRefund(orderId, userId, amount, reason);
        return successResult(r);
    }

    private McpSchema.CallToolResult doStatusById(Map<String, Object> args) {
        Long refundId = optionalLong(args, "refundId");
        String orderId = optionalString(args, "orderId");
        RefundStatusResult r = refundService.getStatus(orderId, refundId);
        return successResult(r);
    }

    private McpSchema.CallToolResult doGetStatus(Map<String, Object> args) {
        return doStatusById(args);
    }

    private McpSchema.CallToolResult doCancelRefund(Map<String, Object> args) {
        Long refundId = requiredLong(args, "refundId");
        RefundCancelResult r = refundService.cancelRefund(refundId);
        return successResult(r);
    }

    // ── 统一调用封装：异常 → isError=true ─────────────────────

    private McpSchema.CallToolResult safeHandle(String toolName,
                                                Map<String, Object> args,
                                                java.util.function.Function<Map<String, Object>,
                                                        McpSchema.CallToolResult> handler) {
        long start = System.currentTimeMillis();
        try {
            McpSchema.CallToolResult r = handler.apply(args);
            log.info("[refund-tool] tool={} args={} elapsed={}ms ok=true",
                    toolName, args, System.currentTimeMillis() - start);
            return r;
        } catch (RefundBizException e) {
            log.warn("[refund-tool] tool={} args={} elapsed={}ms bizError code={} msg={}",
                    toolName, args, System.currentTimeMillis() - start,
                    e.getErrorCode().getCode(), e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(e.getMessage())
                    .isError(true)
                    .build();
        } catch (IllegalArgumentException e) {
            // 入参校验类错误（requiredString/Long 抛出），对客户端可见但不写 ERROR 日志
            log.warn("[refund-tool] tool={} args={} elapsed={}ms badRequest msg={}",
                    toolName, args, System.currentTimeMillis() - start, e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(e.getMessage())
                    .isError(true)
                    .build();
        } catch (Exception e) {
            log.error("[refund-tool] tool={} args={} elapsed={}ms unexpected error",
                    toolName, args, System.currentTimeMillis() - start, e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("系统繁忙，请稍后再试")
                    .isError(true)
                    .build();
        }
    }

    // ── Tool schema 定义 ──────────────────────────────────────

    private McpSchema.Tool buildCheckEligibilityTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("orderId", Map.of("type", "string", "description", "订单编号，例如 ORD-001")),
                List.of("orderId"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("check_refund_eligibility")
                .title("查询退款资格")
                .description("用户想了解自己订单是否还能退款、或是否在退款期内时调用。"
                        + " 入参 orderId，返回 eligible/deadline/amount 三个字段。"
                        + " 在发起退款之前调用，确认订单符合条件。")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool buildSubmitRefundTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "orderId", Map.of("type", "string", "description", "订单编号，例如 ORD-001"),
                        "reason",  Map.of("type", "string", "description", "退款原因，可选"),
                        "userId",  Map.of("type", "string", "description", "用户标识，可选；缺省取订单所属用户"),
                        "amount",  Map.of("type", "number", "description", "退款金额，可选；缺省取订单金额")
                ),
                List.of("orderId"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("submit_refund")
                .title("提交退款申请")
                .description("用户确认要发起退款时调用。会落库一条 PENDING 状态的申请。"
                        + " 若同订单已有进行中的申请，返回 isError=true 和原 refundId。")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool buildGetStatusTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "orderId",  Map.of("type", "string", "description", "订单编号，二选一"),
                        "refundId", Map.of("type", "number", "description", "退款申请编号，二选一；优先于 orderId")
                ),
                List.of(),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_refund_status")
                .title("查询退款进度")
                .description("用户问「我的退款到哪了」「申请结果怎样」时调用。"
                        + " 接受 orderId 或 refundId（用户常常只记得订单号）。"
                        + " 返回 status（英文枚举）+ statusLabel（中文标签）+ estimatedArrival。")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool buildCancelRefundTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("refundId", Map.of("type", "number", "description", "退款申请编号")),
                List.of("refundId"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("cancel_refund")
                .title("撤销退款申请")
                .description("用户改变主意、想撤回退款时调用。仅 PENDING 状态可撤销，"
                        + " 其他状态返回 isError=true 和友好提示。")
                .inputSchema(schema)
                .build();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec(
            McpSchema.Tool tool,
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    /** Java 对象 → JSON 文本 → text content（isError=false）。 */
    private McpSchema.CallToolResult successResult(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.error("[refund-tool] serialize failed payload={}", payload, e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("响应序列化失败")
                    .isError(true)
                    .build();
        }
    }

    // ── 参数解析 ──────────────────────────────────────────────

    private static String requiredString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("缺少必填参数：" + key);
        }
        return v.toString();
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static Long optionalLong(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static long requiredLong(Map<String, Object> args, String key) {
        Long v = optionalLong(args, key);
        if (v == null) throw new IllegalArgumentException("缺少必填参数：" + key);
        return v;
    }

    private static BigDecimal optionalDecimal(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }
}