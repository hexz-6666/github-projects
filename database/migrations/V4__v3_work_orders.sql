-- M2 / v3: risk-alert work orders and immutable work-order configuration snapshots.
-- Apply after V1, synthetic seed and V3. This migration is additive; do not run destructive cleanup.

CREATE TABLE IF NOT EXISTS work_order_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    priority TINYINT NOT NULL DEFAULT 3,
    trigger_json JSON NOT NULL,
    risk_score_threshold DECIMAL(5,4) NOT NULL,
    sla_minutes INT NOT NULL,
    requires_review TINYINT NOT NULL,
    assign_role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    published_version INT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_woc_code (code),
    KEY idx_woc_status (status, updated_at),
    CONSTRAINT ck_woc_code CHECK (code REGEXP '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_woc_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_woc_sla CHECK (sla_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_woc_review CHECK (requires_review IN (0,1)),
    CONSTRAINT ck_woc_role CHECK (assign_role IN ('REVIEWER','ADMIN')),
    CONSTRAINT ck_woc_status CHECK (status IN ('DRAFT','ACTIVE','DISABLED')),
    CONSTRAINT ck_woc_version CHECK (version >= 1),
    CONSTRAINT ck_woc_published CHECK (published_version IS NULL OR published_version >= 1),
    CONSTRAINT ck_woc_threshold CHECK (risk_score_threshold BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS work_order_config_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    source_version INT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    priority TINYINT NOT NULL,
    trigger_json JSON NOT NULL,
    risk_score_threshold DECIMAL(5,4) NOT NULL,
    sla_minutes INT NOT NULL,
    requires_review TINYINT NOT NULL,
    assign_role VARCHAR(16) NOT NULL,
    published_by VARCHAR(64) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wocv_config_version (config_id, version_no),
    KEY idx_wocv_published (config_id, published_at),
    CONSTRAINT fk_wocv_config FOREIGN KEY (config_id)
        REFERENCES work_order_config (id),
    CONSTRAINT ck_wocv_version CHECK (version_no >= 1 AND source_version >= 1),
    CONSTRAINT ck_wocv_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_wocv_sla CHECK (sla_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_wocv_review CHECK (requires_review IN (0,1)),
    CONSTRAINT ck_wocv_role CHECK (assign_role IN ('REVIEWER','ADMIN')),
    CONSTRAINT ck_wocv_threshold CHECK (risk_score_threshold BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 约束/索引统一在文件末尾用 information_schema 守卫添加，保证干净库和已执行库可重复执行。
-- 不在这里无条件 ADD CONSTRAINT：重复执行会失败。

CREATE TABLE IF NOT EXISTS work_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_id BIGINT NOT NULL,
    config_id BIGINT NOT NULL,
    config_version_id BIGINT NOT NULL,
    config_version_no INT NOT NULL,
    config_snapshot_json JSON NOT NULL,
    assign_role VARCHAR(16) NOT NULL,
    assignee_id VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL,
    requires_review TINYINT NOT NULL,
    sla_minutes INT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    resolution_review_id BIGINT NULL,
    resolved_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wo_alert (alert_id),
    KEY idx_wo_assignee_status (assignee_id, status),
    KEY idx_wo_queue (status, assign_role, assignee_id),
    KEY idx_wo_due (status, due_at),
    KEY idx_wo_config (config_id),
    CONSTRAINT fk_wo_alert FOREIGN KEY (alert_id) REFERENCES ads_risk_alert (alert_id),
    CONSTRAINT fk_wo_config FOREIGN KEY (config_id) REFERENCES work_order_config (id),
    CONSTRAINT fk_wo_config_version FOREIGN KEY (config_version_id)
        REFERENCES work_order_config_version (id),
    CONSTRAINT fk_wo_config_version_no FOREIGN KEY (config_id, config_version_no)
        REFERENCES work_order_config_version (config_id, version_no),
    CONSTRAINT fk_wo_resolution_review FOREIGN KEY (resolution_review_id)
        REFERENCES ads_alert_review (review_id),
    CONSTRAINT ck_wo_status CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CANCELLED')),
    CONSTRAINT ck_wo_review CHECK (requires_review IN (0,1)),
    CONSTRAINT ck_wo_role CHECK (assign_role IN ('REVIEWER','ADMIN')),
    CONSTRAINT ck_wo_sla CHECK (sla_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_wo_version CHECK (version >= 1),
    CONSTRAINT ck_wo_config_version CHECK (config_version_no >= 1),
    CONSTRAINT ck_wo_due CHECK (due_at >= created_at),
    CONSTRAINT ck_wo_open_unassigned CHECK (status <> 'OPEN' OR assignee_id IS NULL),
    CONSTRAINT ck_wo_active_assigned CHECK (status NOT IN ('ASSIGNED','IN_PROGRESS') OR assignee_id IS NOT NULL),
    CONSTRAINT ck_wo_terminal_times CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND resolved_at IS NULL)
        OR (status NOT IN ('RESOLVED','CANCELLED') AND resolved_at IS NULL AND cancelled_at IS NULL)
    ),
    CONSTRAINT ck_wo_resolution_review CHECK (
        status <> 'RESOLVED' OR requires_review = 0 OR resolution_review_id IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS work_order_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    note VARCHAR(500) NULL,
    request_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_woe_timeline (work_order_id, created_at, event_id),
    CONSTRAINT fk_woe_work_order FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT ck_woe_type CHECK (event_type IN ('CREATED','ASSIGNED','REASSIGNED','CLAIMED','STARTED','RESOLVED','CANCELLED','NOTE_ADDED')),
    CONSTRAINT ck_woe_from CHECK (from_status IS NULL OR from_status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CANCELLED')),
    CONSTRAINT ck_woe_to CHECK (to_status IS NULL OR to_status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CANCELLED')),
    CONSTRAINT ck_woe_role CHECK (actor_role IN ('ANALYST','REVIEWER','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Idempotent constraint guards (MySQL 8.0.16+).
-- The mysql client executes this file with `source`; no Flyway/Liquibase schema
-- history exists. Each DDL is guarded by information_schema so a clean database
-- and a previously executed/partially executed database both converge safely.
-- No table is dropped and no row is deleted.
-- ---------------------------------------------------------------------------

-- P1-1: the referenced review must belong to the same alert as the work order.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ads_alert_review ADD UNIQUE KEY uk_review_id_alert (review_id, alert_id)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ads_alert_review'
      AND INDEX_NAME = 'uk_review_id_alert'
);
PREPARE v4_stmt FROM @ddl;
EXECUTE v4_stmt;
DEALLOCATE PREPARE v4_stmt;

-- The composite version key is needed by the three-column consistency FK.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE work_order_config_version ADD UNIQUE KEY uk_wocv_id_config_version (id, config_id, version_no)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'work_order_config_version'
      AND INDEX_NAME = 'uk_wocv_id_config_version'
);
PREPARE v4_stmt FROM @ddl;
EXECUTE v4_stmt;
DEALLOCATE PREPARE v4_stmt;

-- Circular pointer: config -> its last published immutable version.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE work_order_config ADD CONSTRAINT fk_woc_published_version FOREIGN KEY (id, published_version) REFERENCES work_order_config_version (config_id, version_no)',
        'SELECT 1')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'work_order_config'
      AND CONSTRAINT_NAME = 'fk_woc_published_version'
);
PREPARE v4_stmt FROM @ddl;
EXECUTE v4_stmt;
DEALLOCATE PREPARE v4_stmt;

-- P1-2: config_version_id, config_id and config_version_no must identify the
-- same immutable version row. The existing single/two-column FKs are retained
-- for compatibility; this three-column FK is the authoritative consistency guard.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE work_order ADD CONSTRAINT fk_wo_config_version_consistent FOREIGN KEY (config_version_id, config_id, config_version_no) REFERENCES work_order_config_version (id, config_id, version_no)',
        'SELECT 1')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'work_order'
      AND CONSTRAINT_NAME = 'fk_wo_config_version_consistent'
);
PREPARE v4_stmt FROM @ddl;
EXECUTE v4_stmt;
DEALLOCATE PREPARE v4_stmt;

-- P1-1: DB-level same-alert guard for RESOLVED work orders.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE work_order ADD CONSTRAINT fk_wo_resolution_review_alert FOREIGN KEY (resolution_review_id, alert_id) REFERENCES ads_alert_review (review_id, alert_id)',
        'SELECT 1')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'work_order'
      AND CONSTRAINT_NAME = 'fk_wo_resolution_review_alert'
);
PREPARE v4_stmt FROM @ddl;
EXECUTE v4_stmt;
DEALLOCATE PREPARE v4_stmt;
