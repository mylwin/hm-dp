-- 项目根目录使用 MySQL 客户端手动执行，先完成第二、三阶段及第四阶段发布列迁移。
-- 不改动旧 Stream，不重置已有任务；以下表支持重复执行。
CREATE TABLE IF NOT EXISTS tb_seckill_recovery_attempt (
    resource_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    last_error VARCHAR(128) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (resource_type, resource_id),
    KEY idx_recovery_due (next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_seckill_maintenance_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NULL,
    voucher_id BIGINT NULL,
    action VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY idx_audit_order (order_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_seckill_message_quarantine (
    quarantine_id CHAR(64) NOT NULL PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    message_id VARCHAR(256) NULL,
    topic VARCHAR(255) NULL,
    body MEDIUMBLOB NULL,
    body_hash CHAR(64) NOT NULL,
    error_type VARCHAR(128) NOT NULL,
    reconsume_times INT NOT NULL,
    resolved TINYINT NOT NULL DEFAULT 0,
    resolution VARCHAR(256) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY idx_quarantine_open (resolved, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
