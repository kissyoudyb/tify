package com.phadcalc.llm.tify.mcp.refund.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundCancelResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundEligibilityResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundStatusResult;
import com.phadcalc.llm.tify.mcp.refund.dto.RefundSubmitResult;
import com.phadcalc.llm.tify.mcp.refund.entity.RefundApplication;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundBizException;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundErrorCode;
import com.phadcalc.llm.tify.mcp.refund.mapper.RefundApplicationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 退款业务逻辑。
 *
 * <p>对应 4 个 MCP 工具：
 * <ul>
 *   <li>check_refund_eligibility → {@link #checkEligibility(String)}</li>
 *   <li>submit_refund → {@link #submitRefund}</li>
 *   <li>get_refund_status → {@link #getStatus}</li>
 *   <li>cancel_refund → {@link #cancelRefund}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    public static final String STATUS_PENDING    = "PENDING";
    public static final String STATUS_APPROVED   = "APPROVED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED  = "COMPLETED";
    public static final String STATUS_REJECTED   = "REJECTED";
    public static final String STATUS_CANCELLED  = "CANCELLED";

    private static final List<String> ACTIVE_STATUSES = List.of(
            STATUS_PENDING, STATUS_APPROVED, STATUS_PROCESSING);

    private final RefundApplicationMapper refundMapper;
    private final OrderMockService orderMockService;

    // ── 1. check_refund_eligibility ────────────────────────────

    public RefundEligibilityResult checkEligibility(String orderId) {
        OrderMockService.OrderInfo order = orderMockService.getOrder(orderId);

        if (!order.signed()) {
            return new RefundEligibilityResult(false, "订单尚未签收，暂时无法退款",
                    null, null);
        }

        long daysSinceSign = ChronoUnit.DAYS.between(order.signedAt().toLocalDate(),
                LocalDateTime.now().toLocalDate());
        if (daysSinceSign > OrderMockService.REFUND_WINDOW_DAYS) {
            return new RefundEligibilityResult(false,
                    "已超过 " + OrderMockService.REFUND_WINDOW_DAYS + " 天退款期，无法退款",
                    null, null);
        }

        LocalDate deadline = order.signedAt().toLocalDate()
                .plusDays(OrderMockService.REFUND_WINDOW_DAYS);
        return new RefundEligibilityResult(true, "符合退款条件",
                deadline, order.amount());
    }

    // ── 2. submit_refund ───────────────────────────────────────

    @Transactional
    public RefundSubmitResult submitRefund(String orderId, String userId,
                                            BigDecimal amount, String reason) {
        OrderMockService.OrderInfo order = orderMockService.getOrder(orderId);

        // 查同订单进行中的申请
        RefundApplication existing = findActiveByOrderId(orderId);
        if (existing != null) {
            throw new RefundBizException(RefundErrorCode.REFUND_DUPLICATE,
                    "该订单已有进行中的退款申请，编号：" + existing.getId());
        }

        RefundApplication app = new RefundApplication();
        app.setOrderId(orderId);
        app.setUserId(userId != null ? userId : order.userId());
        app.setAmount(amount != null ? amount : order.amount());
        app.setReason(reason != null ? reason : "");
        app.setStatus(STATUS_PENDING);
        refundMapper.insert(app);

        log.info("[refund] submit orderId={} refundId={} amount={}",
                orderId, app.getId(), app.getAmount());

        return new RefundSubmitResult(app.getId(), orderId, STATUS_PENDING,
                "审核中", 3);
    }

    // ── 3. get_refund_status ───────────────────────────────────

    public RefundStatusResult getStatus(String orderId, Long refundId) {
        RefundApplication app;
        if (refundId != null) {
            app = refundMapper.selectById(refundId);
            if (app == null) {
                throw new RefundBizException(RefundErrorCode.REFUND_NOT_FOUND,
                        "退款申请不存在：编号 " + refundId);
            }
        } else if (orderId != null && !orderId.isBlank()) {
            app = findLatestByOrderId(orderId);
            if (app == null) {
                throw new RefundBizException(RefundErrorCode.REFUND_NOT_FOUND,
                        "该订单没有退款记录：" + orderId);
            }
        } else {
            throw new RefundBizException(RefundErrorCode.REFUND_INVALID_PARAMS,
                    "orderId 和 refundId 至少传一个");
        }

        return new RefundStatusResult(
                app.getId(), app.getOrderId(), app.getAmount(),
                app.getStatus(), statusLabel(app.getStatus()),
                app.getCreatedAt(),
                estimateArrival(app.getStatus(), app.getCreatedAt()),
                app.getRejectReason());
    }

    // ── 4. cancel_refund ───────────────────────────────────────

    @Transactional
    public RefundCancelResult cancelRefund(Long refundId) {
        RefundApplication app = refundMapper.selectById(refundId);
        if (app == null) {
            throw new RefundBizException(RefundErrorCode.REFUND_NOT_FOUND,
                    "退款申请不存在：编号 " + refundId);
        }
        if (!STATUS_PENDING.equals(app.getStatus())) {
            throw new RefundBizException(RefundErrorCode.REFUND_INVALID_STATE,
                    "退款已在处理中，无法撤销（当前状态：" + app.getStatus() + "）");
        }

        app.setStatus(STATUS_CANCELLED);
        refundMapper.updateById(app);

        log.info("[refund] cancel refundId={} orderId={}", refundId, app.getOrderId());
        return new RefundCancelResult(true, "已为您撤销退款申请");
    }

    // ── 内部工具 ───────────────────────────────────────────────

    private RefundApplication findActiveByOrderId(String orderId) {
        return refundMapper.selectList(
                new LambdaQueryWrapper<RefundApplication>()
                        .eq(RefundApplication::getOrderId, orderId)
                        .in(RefundApplication::getStatus, ACTIVE_STATUSES)
                        .orderByDesc(RefundApplication::getCreatedAt)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private RefundApplication findLatestByOrderId(String orderId) {
        return refundMapper.selectList(
                new LambdaQueryWrapper<RefundApplication>()
                        .eq(RefundApplication::getOrderId, orderId)
                        .orderByDesc(RefundApplication::getCreatedAt)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private String statusLabel(String status) {
        return switch (status) {
            case STATUS_PENDING    -> "审核中";
            case STATUS_APPROVED   -> "已批准";
            case STATUS_PROCESSING -> "处理中";
            case STATUS_COMPLETED  -> "已完成";
            case STATUS_REJECTED   -> "已拒绝";
            case STATUS_CANCELLED  -> "已撤销";
            default                -> status;
        };
    }

    private LocalDate estimateArrival(String status, LocalDateTime submittedAt) {
        if (status == null) return null;
        return switch (status) {
            case STATUS_PENDING, STATUS_APPROVED, STATUS_PROCESSING ->
                    submittedAt.toLocalDate().plusDays(3);
            case STATUS_COMPLETED -> submittedAt.toLocalDate();
            default -> null;
        };
    }
}