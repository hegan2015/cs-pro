-- cs-pro MySQL 8 建表脚本（商用模式：CS_DB_MODE=mysql 时用 mysql 客户端执行本文件）。
-- 与 H2 schema.sql 等价：时间 BIGINT 毫秒；金额 BIGINT 分；ID 前缀 + cs_sequence 号表取号。
-- 幂等：全部 IF NOT EXISTS，可重复执行。

CREATE TABLE IF NOT EXISTS cs_sequence (
    name     VARCHAR(32) PRIMARY KEY,
    next_val BIGINT NOT NULL DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO cs_sequence(name, next_val) VALUES
    ('session', 0), ('message', 0), ('ticket', 0), ('note', 0),
    ('kb', 0), ('llm_call', 0), ('tenant', 0), ('sub_order', 0), ('invoice', 0)
ON DUPLICATE KEY UPDATE name = name;

CREATE TABLE IF NOT EXISTS cs_session (
    id          VARCHAR(32) PRIMARY KEY,
    user_role   VARCHAR(16) NOT NULL,
    user_id     VARCHAR(32) NOT NULL,
    user_name   VARCHAR(64),
    tenant_code VARCHAR(32) NOT NULL DEFAULT 'ride-pro',
    status      VARCHAR(16) NOT NULL,
    intent      VARCHAR(64),
    takeover    TINYINT(1)  NOT NULL DEFAULT 0,
    access_token VARCHAR(64),
    created_at  BIGINT      NOT NULL,
    ended_at    BIGINT,
    KEY idx_session_user (user_id),
    KEY idx_session_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_message (
    id           VARCHAR(32) PRIMARY KEY,
    session_id   VARCHAR(32) NOT NULL,
    role         VARCHAR(16) NOT NULL,
    content      LONGTEXT,
    tool_name    VARCHAR(64),
    tool_result  LONGTEXT,
    kb_refs      VARCHAR(512),
    ticket_draft LONGTEXT,
    tool_trace   LONGTEXT,
    attachments  VARCHAR(1024),
    latency_ms   BIGINT,
    tokens       BIGINT,
    created_at   BIGINT      NOT NULL,
    KEY idx_message_session (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_ticket (
    id          VARCHAR(32) PRIMARY KEY,
    session_id  VARCHAR(32),
    user_id     VARCHAR(32) NOT NULL,
    user_name   VARCHAR(64),
    category    VARCHAR(32) NOT NULL,
    priority    VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    summary     VARCHAR(512) NOT NULL,
    description LONGTEXT,
    order_id    VARCHAR(32),
    status      VARCHAR(16) NOT NULL,
    assignee    VARCHAR(64),
    request_id  VARCHAR(64) NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'USER',
    solution    VARCHAR(1024),
    attachments VARCHAR(1024),
    created_at  BIGINT      NOT NULL,
    resolved_at BIGINT,
    closed_at   BIGINT,
    UNIQUE KEY uk_ticket_request (request_id),
    KEY idx_ticket_user (user_id),
    KEY idx_ticket_status (status, created_at),
    KEY idx_ticket_source (source, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ride-pro 工单接入增量：存量库（CREATE TABLE IF NOT EXISTS 不会补列）须手动执行一次：
--   ALTER TABLE cs_ticket ADD COLUMN user_name VARCHAR(64) NULL AFTER user_id;
--   ALTER TABLE cs_ticket ADD COLUMN description LONGTEXT NULL AFTER summary;
--   ALTER TABLE cs_ticket ADD INDEX idx_ticket_source (source, created_at);
-- H2 侧对应 schema.sql 尾部幂等 ALTER 自动生效。

CREATE TABLE IF NOT EXISTS cs_ticket_note (
    id         VARCHAR(32) PRIMARY KEY,
    ticket_id  VARCHAR(32) NOT NULL,
    author     VARCHAR(64) NOT NULL,
    content    VARCHAR(1024) NOT NULL,
    created_at BIGINT      NOT NULL,
    KEY idx_note_ticket (ticket_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_kb (
    id         VARCHAR(32) PRIMARY KEY,
    question   VARCHAR(256) NOT NULL,
    answer     LONGTEXT     NOT NULL,
    category   VARCHAR(32)  NOT NULL,
    keywords   VARCHAR(256),
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    hit_count  BIGINT       NOT NULL DEFAULT 0,
    cited_count BIGINT      NOT NULL DEFAULT 0,
    cited_resolved_count BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT       NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_kb_revision (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id        VARCHAR(32)  NOT NULL,
    question     VARCHAR(256) NOT NULL,
    answer       LONGTEXT     NOT NULL,
    category     VARCHAR(32)  NOT NULL,
    keywords     VARCHAR(256),
    saved_by     VARCHAR(64),
    saved_at     BIGINT       NOT NULL,
    KEY idx_kbrev_kb (kb_id, saved_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_rating (
    target_type VARCHAR(16) NOT NULL,
    target_id   VARCHAR(32) NOT NULL,
    score       INT         NOT NULL,
    comment     VARCHAR(512),
    created_at  BIGINT      NOT NULL,
    PRIMARY KEY (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_llm_call (
    id             VARCHAR(32) PRIMARY KEY,
    session_id     VARCHAR(32),
    model          VARCHAR(64),
    prompt_version VARCHAR(32),
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    latency_ms     BIGINT,
    status         VARCHAR(16) NOT NULL,
    error          VARCHAR(256),
    created_at     BIGINT      NOT NULL,
    KEY idx_llm_call_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS stats_daily (
    stat_day   VARCHAR(10) NOT NULL,
    metric     VARCHAR(32) NOT NULL,
    stat_value BIGINT      NOT NULL,
    PRIMARY KEY (stat_day, metric)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json LONGTEXT,
    created_at  BIGINT      NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_tenant (
    id         VARCHAR(32) PRIMARY KEY,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT      NOT NULL,
    UNIQUE KEY uk_tenant_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_subscription (
    tenant_id  VARCHAR(32) PRIMARY KEY,
    plan_name  VARCHAR(32) NOT NULL,
    plan_code  VARCHAR(16) NOT NULL DEFAULT 'standard',
    days       INT         NOT NULL,
    status     VARCHAR(16) NOT NULL,
    start_at   BIGINT      NOT NULL,
    expire_at  BIGINT      NOT NULL,
    source     VARCHAR(16) NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_activation_code (
    code           VARCHAR(32) PRIMARY KEY,
    days           INT         NOT NULL DEFAULT 30,
    plan_code      VARCHAR(16) NOT NULL DEFAULT 'standard',
    status         VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    used_by_tenant VARCHAR(32),
    used_at        BIGINT,
    created_at     BIGINT      NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_sub_order (
    id            VARCHAR(32) PRIMARY KEY,
    tenant_id     VARCHAR(32) NOT NULL,
    amount        BIGINT      NOT NULL,
    days          INT         NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    pp_order_id   VARCHAR(32),
    pp_payment_id VARCHAR(32),
    status        VARCHAR(16) NOT NULL,
    created_at    BIGINT      NOT NULL,
    paid_at       BIGINT,
    UNIQUE KEY uk_sub_order_request (request_id),
    KEY idx_sub_order_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_free_usage (
    user_id    VARCHAR(32) NOT NULL,
    use_day    VARCHAR(10) NOT NULL,
    used_count INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, use_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- v1.2.0 商用底座新增域

CREATE TABLE IF NOT EXISTS cs_open_key (
    api_key    VARCHAR(48) PRIMARY KEY,
    tenant_id  VARCHAR(32) NOT NULL,
    secret     VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT      NOT NULL,
    KEY idx_openkey_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_invoice (
    id            VARCHAR(32) PRIMARY KEY,
    tenant_id     VARCHAR(32) NOT NULL,
    period        VARCHAR(7)  NOT NULL,
    prompt_tokens BIGINT      NOT NULL,
    complet_tokens BIGINT     NOT NULL,
    calls         BIGINT      NOT NULL,
    amount        BIGINT      NOT NULL,
    plan_code     VARCHAR(16) NOT NULL,
    created_at    BIGINT      NOT NULL,
    UNIQUE KEY uk_invoice (tenant_id, period),
    KEY idx_invoice_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_admin_audit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor      VARCHAR(64) NOT NULL,
    action     VARCHAR(64) NOT NULL,
    target     VARCHAR(128),
    detail     VARCHAR(1024),
    created_at BIGINT      NOT NULL,
    KEY idx_admaudit_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cs_proactive_event (
    event_key  VARCHAR(128) PRIMARY KEY,
    tenant_id  VARCHAR(32) NOT NULL,
    user_id    VARCHAR(32) NOT NULL,
    session_id VARCHAR(32),
    payload    VARCHAR(1024),
    status     VARCHAR(16) NOT NULL DEFAULT 'SENT',
    created_at BIGINT      NOT NULL,
    KEY idx_proactive_tenant (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
