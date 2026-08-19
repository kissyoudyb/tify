-- MySQL 建表（生产 DDL）
-- refund_application：退款申请主表

CREATE TABLE IF NOT EXISTS refund_application (
    id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id        VARCHAR(64)     NOT NULL,
    user_id         VARCHAR(64)     NOT NULL,
    amount          DECIMAL(10,2)   NOT NULL,
    reason          VARCHAR(500)    NOT NULL DEFAULT '',
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    reject_reason   VARCHAR(500),
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    KEY idx_refund_order_id       (order_id),
    KEY idx_refund_user_id        (user_id),
    KEY idx_refund_status         (status),
    KEY idx_refund_order_created  (order_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;