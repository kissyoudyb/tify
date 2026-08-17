package com.phadcalc.llm.tify.mcp.refund.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 退款 MCP Server 本地错误码。
 *
 * <p>与 tify-common 的 ErrorCode 错开段位（5xxx 是 MCP 工具调用方用的，本 server 端用 6xxx）。
 * 主要供日志和单测断言；MCP 工具调用对外统一返 isError=true + 中文 message，不暴露 code。
 */
@Getter
@RequiredArgsConstructor
public enum RefundErrorCode {

    REFUND_NOT_FOUND(6000, "退款申请不存在"),
    REFUND_INVALID_STATE(6001, "退款状态不允许此操作"),
    REFUND_DUPLICATE(6002, "该订单已有进行中的退款申请"),
    REFUND_INVALID_PARAMS(6003, "请求参数无效"),
    REFUND_ORDER_NOT_FOUND(6004, "订单不存在"),
    REFUND_NOT_ELIGIBLE(6005, "不符合退款条件");

    private final int code;
    private final String message;
}