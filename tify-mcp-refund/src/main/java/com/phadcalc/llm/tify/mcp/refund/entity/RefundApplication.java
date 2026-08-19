package com.phadcalc.llm.tify.mcp.refund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款申请主表。
 *
 * <p>自管 4 个审计字段（id / created_at / updated_at / deleted），不继承 tify-common 的 BaseEntity，
 * 避免 refund server 反向依赖主工程。created_at / updated_at 由 {@code MyMetaObjectHandler} 自动填充。
 */
@Getter
@Setter
@TableName("refund_application")
public class RefundApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderId;
    private String userId;
    private BigDecimal amount;
    private String reason;

    /** PENDING / APPROVED / PROCESSING / COMPLETED / REJECTED / CANCELLED */
    private String status;

    private String rejectReason;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}