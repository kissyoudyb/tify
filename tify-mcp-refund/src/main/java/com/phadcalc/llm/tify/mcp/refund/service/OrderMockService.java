package com.phadcalc.llm.tify.mcp.refund.service;

import com.phadcalc.llm.tify.mcp.refund.exception.RefundBizException;
import com.phadcalc.llm.tify.mcp.refund.exception.RefundErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一期简化：硬编码 3 个测试订单，演示 7 天退款期判定。
 *
 * <p>真实场景应当调外部订单系统；本期按 25 讲选 hardcode mock，不引入订单系统依赖。
 */
@Service
public class OrderMockService {

    /** 退款期天数（按 25 讲）。 */
    public static final int REFUND_WINDOW_DAYS = 7;

    private record MockOrder(String orderId, String userId, BigDecimal amount,
                              boolean signed, LocalDateTime signedAt) {}

    private final java.util.Map<String, MockOrder> orders = new java.util.HashMap<>();

    public OrderMockService() {
        LocalDateTime now = LocalDateTime.now();
        orders.put("ORD-001", new MockOrder("ORD-001", "u001",
                new BigDecimal("299.00"), true, now.minusDays(5)));
        orders.put("ORD-002", new MockOrder("ORD-002", "u002",
                new BigDecimal("199.00"), true, now.minusDays(10)));
        orders.put("ORD-003", new MockOrder("ORD-003", "u003",
                new BigDecimal("599.00"), false, null));
    }

    /**
     * @return 订单基础信息
     * @throws RefundBizException REFUND_NOT_FOUND 订单不存在
     */
    public OrderInfo getOrder(String orderId) {
        MockOrder o = orders.get(orderId);
        if (o == null) {
            throw new RefundBizException(RefundErrorCode.REFUND_ORDER_NOT_FOUND,
                    "订单不存在：" + orderId);
        }
        return new OrderInfo(o.orderId, o.userId, o.amount, o.signed, o.signedAt);
    }

    public record OrderInfo(String orderId, String userId, BigDecimal amount,
                            boolean signed, LocalDateTime signedAt) {}
}