package com.phadcalc.llm.tify.mcp.refund.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** cancel_refund 工具的返回结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundCancelResult {
    private boolean success;
    private String message;
}