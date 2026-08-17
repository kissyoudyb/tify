package com.phadcalc.llm.tify.mcp.refund.service;

import com.phadcalc.llm.tify.mcp.refund.dto.RefundCancelResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundEligibilityResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundStatusResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundSubmitResult;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundBizException;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundErrorCode;
import com.phadcalc.llm.tify.mcp.refund.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
class RefundServiceTest {

    @Autowired RefundService refundService;
    @Autowired OrderMockService orderMockService;
    @Autowired RefundApplicationMapper refundMapper;

    @BeforeEach
    void cleanTable() {
        // 物理清表（绕过 @TableLogic），保证测试间互不污染
        refundMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>() {{}}
        );
    }

    // ── checkEligibility ──────────────────────────────────────

    @Test
    void checkEligibility_signed_within_window_ok() {
        RefundEligibilityResult r = refundService.checkEligibility("ORD-001");
        assertThat(r.isEligible()).isTrue();
        assertThat(r.getAmount()).isEqualByComparingTo("299.00");
        assertThat(r.getDeadline()).isNotNull();
    }

    @Test
    void checkEligibility_signed_but_out_of_window_refused() {
        RefundEligibilityResult r = refundService.checkEligibility("ORD-002");
        assertThat(r.isEligible()).isFalse();
        assertThat(r.getAmount()).isNull();
        assertThat(r.getReason()).contains("7");
    }

    @Test
    void checkEligibility_not_signed_refused() {
        RefundEligibilityResult r = refundService.checkEligibility("ORD-003");
        assertThat(r.isEligible()).isFalse();
        assertThat(r.getReason()).contains("签收");
    }

    @Test
    void checkEligibility_unknown_order_throws() {
        assertThatThrownBy(() -> refundService.checkEligibility("ORD-999"))
                .isInstanceOf(RefundBizException.class)
                .extracting("errorCode").isEqualTo(RefundErrorCode.REFUND_ORDER_NOT_FOUND);
    }

    // ── submitRefund ──────────────────────────────────────────

    @Test
    void submitRefund_first_time_ok() {
        RefundSubmitResult r = refundService.submitRefund("ORD-001", "u001",
                new BigDecimal("299.00"), "商品破损");
        assertThat(r.getRefundId()).isNotNull();
        assertThat(r.getStatus()).isEqualTo("PENDING");
        assertThat(r.getStatusLabel()).isEqualTo("审核中");
        assertThat(r.getEstimatedDays()).isEqualTo(3);
    }

    @Test
    void submitRefund_duplicate_active_throws() {
        refundService.submitRefund("ORD-001", "u001", null, "first");
        assertThatThrownBy(() ->
                refundService.submitRefund("ORD-001", "u001", null, "second"))
                .isInstanceOf(RefundBizException.class)
                .extracting("errorCode").isEqualTo(RefundErrorCode.REFUND_DUPLICATE);
    }

    // ── getStatus ─────────────────────────────────────────────

    @Test
    void getStatus_by_refundId_ok() {
        RefundSubmitResult s = refundService.submitRefund("ORD-001", "u001",
                new BigDecimal("299.00"), "test");
        RefundStatusResult r = refundService.getStatus(null, s.getRefundId());
        assertThat(r.getRefundId()).isEqualTo(s.getRefundId());
        assertThat(r.getStatus()).isEqualTo("PENDING");
        assertThat(r.getStatusLabel()).isEqualTo("审核中");
    }

    @Test
    void getStatus_by_orderId_ok() {
        refundService.submitRefund("ORD-001", "u001", new BigDecimal("299.00"), "test");
        RefundStatusResult r = refundService.getStatus("ORD-001", null);
        assertThat(r.getOrderId()).isEqualTo("ORD-001");
    }

    @Test
    void getStatus_missing_both_throws() {
        assertThatThrownBy(() -> refundService.getStatus(null, null))
                .isInstanceOf(RefundBizException.class)
                .extracting("errorCode").isEqualTo(RefundErrorCode.REFUND_INVALID_PARAMS);
    }

    // ── cancelRefund ──────────────────────────────────────────

    @Test
    void cancelRefund_pending_ok() {
        RefundSubmitResult s = refundService.submitRefund("ORD-001", "u001",
                new BigDecimal("299.00"), "test");
        RefundCancelResult r = refundService.cancelRefund(s.getRefundId());
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMessage()).contains("撤销");

        // 复核状态
        RefundStatusResult status = refundService.getStatus(null, s.getRefundId());
        assertThat(status.getStatus()).isEqualTo("CANCELLED");
        assertThat(status.getStatusLabel()).isEqualTo("已撤销");
    }

    @Test
    void cancelRefund_unknown_throws() {
        assertThatThrownBy(() -> refundService.cancelRefund(99999L))
                .isInstanceOf(RefundBizException.class)
                .extracting("errorCode").isEqualTo(RefundErrorCode.REFUND_NOT_FOUND);
    }
}