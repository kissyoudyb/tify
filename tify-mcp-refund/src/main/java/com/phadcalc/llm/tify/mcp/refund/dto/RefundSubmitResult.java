package com.phadcalc.llm.tify.mcp.refund.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** submit_refund 工具的返回结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundSubmitResult {
    private Long refundId;
    private String orderId;
    /** PENDING / APPROVED / PROCESSING / COMPLETED / REJECTED / CANCELLED */
    private String status;
    /** 中文标签，专给 LLM 说给用户听。 */
    private String statusLabel;
    private int estimatedDays;
}