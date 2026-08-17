package com.phadcalc.llm.tify.mcp.refund.exception;

import lombok.Getter;

@Getter
public class RefundBizException extends RuntimeException {

    private final RefundErrorCode errorCode;

    public RefundBizException(RefundErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public RefundBizException(RefundErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}