-- MySQL 8.0.22+；在选中的业务库内手动执行，可重复执行，不修改旧 Stream 数据。
-- 第二阶段只建三张独立表；历史订单业务唯一索引在第三阶段核查重复数据后增加。
CREATE TABLE IF NOT EXISTS tb_seckill_campaign_control (
    voucher_id BIGINT NOT NULL COMMENT '券编号，独立维护锁资源',
    epoch BIGINT NOT NULL COMMENT '库存预占代次',
    status VARCHAR(16) NOT NULL DEFAULT 'PAUSED' COMMENT 'READY / PAUSED',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (voucher_id),
    CONSTRAINT chk_campaign_epoch CHECK (epoch > 0),
    CONSTRAINT chk_campaign_status CHECK (status IN ('READY', 'PAUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS tb_seckill_request (
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL COMMENT '同一用户的 HTTP 重试标识',
    payload_hash CHAR(64) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREPARING',
    failure_reason VARCHAR(64) NULL,
    reservation_epoch BIGINT NULL,
    accepted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_request_user_id (user_id, request_id),
    KEY idx_request_recovery (status, updated_at, order_id),
    KEY idx_request_voucher (voucher_id, status, order_id),
    CONSTRAINT chk_request_status CHECK (status IN ('PREPARING', 'ACCEPTED', 'SUCCEEDED', 'REJECTED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS tb_mq_outbox (
    event_id VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    lease_token VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    broker_message_id VARCHAR(256) NULL,
    last_error VARCHAR(128) NULL COMMENT '仅存异常类型，不存消息正文或凭据',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_outbox_order_type (order_id, event_type),
    KEY idx_outbox_pending (event_type, status, next_retry_at, event_id),
    KEY idx_outbox_lease (event_type, status, lease_until, event_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'SENT')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 不自动初始化 READY 控制行，也不把旧 Redis 库存直接复制为新库存。
-- 新代次初始化与旧 Stream 排空属于第四、第五阶段，本阶段仅在隔离测试数据上验证。
