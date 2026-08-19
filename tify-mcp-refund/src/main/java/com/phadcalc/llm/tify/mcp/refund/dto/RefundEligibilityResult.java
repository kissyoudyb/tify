package com.phadcalc.llm.tify.mcp.refund.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** check_refund_eligibility 工具的返回结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundEligibilityResult {
    private boolean eligible;
    private String reason;
    /** 可退时填最晚可退日期；不可退时为 null。 */
    private LocalDate deadline;
    /** 可退时填可退金额；不可退时为 null。 */
    private BigDecimal amount;
}