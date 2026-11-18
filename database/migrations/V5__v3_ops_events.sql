-- M3 / v3: structured operational diagnostics. This is additive and separate
-- from audit_event. Apply after V4. Do not run destructive cleanup here.

CREATE TABLE IF NOT EXISTS ops_log_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL,
    source VARCHAR(16) NOT NULL,
    level VARCHAR(8) NOT NULL,
    route VARCHAR(128) NULL,
    code VARCHAR(64) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    task_type VARCHAR(32) NULL,
    task_id VARCHAR(64) NULL,
    request_id VARCHAR(64) NULL,
    related_request_id VARCHAR(64) NULL,
    actor_id VARCHAR(64) NULL,
    build_version VARCHAR(32) NULL,
    duration_ms INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_ops_recent (occurred_at, event_id),
    KEY idx_ops_filter (source, level, occurred_at, event_id),
    KEY idx_ops_request (request_id, occurred_at, event_id),
    KEY idx_ops_related (related_request_id, occurred_at, event_id),
    KEY idx_ops_task (task_type, task_id, occurred_at, event_id),
    CONSTRAINT ck_ops_source CHECK (source IN ('FRONTEND','BACKEND','MODEL','TASK')),
    CONSTRAINT ck_ops_level CHECK (level IN ('INFO','WARN','ERROR')),
    CONSTRAINT ck_ops_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 operational diagnostics; not immutable audit';
