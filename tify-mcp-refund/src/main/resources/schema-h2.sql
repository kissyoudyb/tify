-- H2 内存库建表，仅用于 dev profile
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
    deleted         TINYINT         NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_refund_order_id     ON refund_application (order_id);
CREATE INDEX IF NOT EXISTS idx_refund_user_id      ON refund_application (user_id);
CREATE INDEX IF NOT EXISTS idx_refund_status       ON refund_application (status);
CREATE INDEX IF NOT EXISTS idx_refund_order_created ON refund_application (order_id, created_at DESC);