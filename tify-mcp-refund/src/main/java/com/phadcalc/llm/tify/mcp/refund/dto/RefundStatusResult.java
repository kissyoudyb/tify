package com.phadcalc.llm.tify.mcp.refund.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** get_refund_status 工具的返回结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundStatusResult {
    private Long refundId;
    private String orderId;
    private BigDecimal amount;
    private String status;
    private String statusLabel;
    private LocalDateTime submittedAt;
    private LocalDate estimatedArrival;
    private String rejectReason;
}