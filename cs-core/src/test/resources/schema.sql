-- cs-pro 单库 schema（H2 文件模式，MODE=MySQL）。幂等建表，重启不丢数据。
-- 约定：时间 BIGINT 毫秒；金额 BIGINT 分；ID 序列+前缀。

CREATE SEQUENCE IF NOT EXISTS seq_session START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_message START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_ticket START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_note START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_kb START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_llm_call START WITH 1;

CREATE TABLE IF NOT EXISTS cs_session (
    id         VARCHAR(32) PRIMARY KEY,
    user_role  VARCHAR(16) NOT NULL,
    user_id    VARCHAR(32) NOT NULL,
    user_name  VARCHAR(64),
    tenant_code VARCHAR(32) NOT NULL DEFAULT 'ride-pro',
    status     VARCHAR(16) NOT NULL,
    intent     VARCHAR(64),
    created_at BIGINT      NOT NULL,
    ended_at   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_session_user ON cs_session (user_id);
CREATE INDEX IF NOT EXISTS idx_session_created ON cs_session (created_at);

CREATE TABLE IF NOT EXISTS cs_message (
    id          VARCHAR(32) PRIMARY KEY,
    session_id  VARCHAR(32) NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     CLOB,
    tool_name   VARCHAR(64),
    tool_result CLOB,
    kb_refs     VARCHAR(512),
    ticket_draft CLOB,
    latency_ms  BIGINT,
    tokens      BIGINT,
    created_at  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_message_session ON cs_message (session_id, created_at);

CREATE TABLE IF NOT EXISTS cs_ticket (
    id          VARCHAR(32) PRIMARY KEY,
    session_id  VARCHAR(32),
    user_id     VARCHAR(32) NOT NULL,
    category    VARCHAR(32) NOT NULL,
    priority    VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    summary     VARCHAR(512) NOT NULL,
    order_id    VARCHAR(32),
    status      VARCHAR(16) NOT NULL,
    assignee    VARCHAR(64),
    request_id  VARCHAR(64) NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'USER',
    solution    VARCHAR(1024),
    created_at  BIGINT      NOT NULL,
    resolved_at BIGINT,
    closed_at   BIGINT,
    CONSTRAINT uk_ticket_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_ticket_user ON cs_ticket (user_id);
CREATE INDEX IF NOT EXISTS idx_ticket_status ON cs_ticket (status, created_at);

CREATE TABLE IF NOT EXISTS cs_ticket_note (
    id         VARCHAR(32) PRIMARY KEY,
    ticket_id  VARCHAR(32) NOT NULL,
    author     VARCHAR(64) NOT NULL,
    content    VARCHAR(1024) NOT NULL,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_note_ticket ON cs_ticket_note (ticket_id, created_at);

CREATE TABLE IF NOT EXISTS cs_kb (
    id         VARCHAR(32) PRIMARY KEY,
    question   VARCHAR(256) NOT NULL,
    answer     CLOB         NOT NULL,
    category   VARCHAR(32)  NOT NULL,
    keywords   VARCHAR(256),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    hit_count  BIGINT       NOT NULL DEFAULT 0,
    updated_at BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS cs_rating (
    target_type VARCHAR(16) NOT NULL,
    target_id   VARCHAR(32) NOT NULL,
    score       INT         NOT NULL,
    comment     VARCHAR(512),
    created_at  BIGINT      NOT NULL,
    PRIMARY KEY (target_type, target_id)
);

CREATE TABLE IF NOT EXISTS cs_llm_call (
    id                VARCHAR(32) PRIMARY KEY,
    session_id        VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    latency_ms        BIGINT,
    status            VARCHAR(16) NOT NULL,
    error             VARCHAR(256),
    created_at        BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_llm_call_created ON cs_llm_call (created_at);

CREATE TABLE IF NOT EXISTS stats_daily (
    stat_day   VARCHAR(10) NOT NULL,
    metric     VARCHAR(32) NOT NULL,
    stat_value BIGINT      NOT NULL,
    PRIMARY KEY (stat_day, metric)
);

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json CLOB,
    created_at  BIGINT      NOT NULL
);

-- 订阅域（§3A）
CREATE SEQUENCE IF NOT EXISTS seq_tenant START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_sub_order START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_invoice START WITH 1;

CREATE TABLE IF NOT EXISTS cs_tenant (
    id         VARCHAR(32) PRIMARY KEY,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_tenant_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS cs_subscription (
    tenant_id  VARCHAR(32) PRIMARY KEY,
    plan_name  VARCHAR(32) NOT NULL,
    days       INT         NOT NULL,
    status     VARCHAR(16) NOT NULL,
    start_at   BIGINT      NOT NULL,
    expire_at  BIGINT      NOT NULL,
    source     VARCHAR(16) NOT NULL,
    updated_at BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS cs_activation_code (
    code           VARCHAR(32) PRIMARY KEY,
    days           INT         NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    used_by_tenant VARCHAR(32),
    used_at        BIGINT,
    created_at     BIGINT      NOT NULL
);

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
    CONSTRAINT uk_sub_order_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_sub_order_status ON cs_sub_order (status, created_at);

CREATE TABLE IF NOT EXISTS cs_free_usage (
    user_id    VARCHAR(32) NOT NULL,
    use_day    VARCHAR(10) NOT NULL,
    used_count INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, use_day)
);

-- v1.1.0 增量（幂等 ALTER，与主 schema 保持一致）
ALTER TABLE cs_message ADD COLUMN IF NOT EXISTS tool_trace CLOB;
ALTER TABLE cs_session ADD COLUMN IF NOT EXISTS takeover BOOLEAN NOT NULL DEFAULT FALSE;-- v1.2.0 增量（幂等，与 db/schema-mysql.sql 保持一致）
ALTER TABLE cs_llm_call ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32);
ALTER TABLE cs_kb ADD COLUMN IF NOT EXISTS cited_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE cs_kb ADD COLUMN IF NOT EXISTS cited_resolved_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE cs_activation_code ADD COLUMN IF NOT EXISTS plan_code VARCHAR(16) NOT NULL DEFAULT 'standard';
-- E 批：图片附件（消息/工单）
ALTER TABLE cs_message ADD COLUMN IF NOT EXISTS attachments VARCHAR(1024);
ALTER TABLE cs_ticket ADD COLUMN IF NOT EXISTS attachments VARCHAR(1024);
ALTER TABLE cs_session ADD COLUMN IF NOT EXISTS access_token VARCHAR(64);
ALTER TABLE cs_subscription ADD COLUMN IF NOT EXISTS plan_code VARCHAR(16) NOT NULL DEFAULT 'standard';

CREATE TABLE IF NOT EXISTS cs_kb_revision (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id      VARCHAR(32)  NOT NULL,
    question   VARCHAR(256) NOT NULL,
    answer     CLOB         NOT NULL,
    category   VARCHAR(32)  NOT NULL,
    keywords   VARCHAR(256),
    saved_by   VARCHAR(64),
    saved_at   BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_kbrev_kb ON cs_kb_revision (kb_id, saved_at);

CREATE TABLE IF NOT EXISTS cs_open_key (
    api_key    VARCHAR(48) PRIMARY KEY,
    tenant_id  VARCHAR(32) NOT NULL,
    secret     VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_openkey_tenant ON cs_open_key (tenant_id);

CREATE TABLE IF NOT EXISTS cs_invoice (
    id             VARCHAR(32) PRIMARY KEY,
    tenant_id      VARCHAR(32) NOT NULL,
    period         VARCHAR(7)  NOT NULL,
    prompt_tokens  BIGINT      NOT NULL,
    complet_tokens BIGINT      NOT NULL,
    calls          BIGINT      NOT NULL,
    amount         BIGINT      NOT NULL,
    plan_code      VARCHAR(16) NOT NULL,
    created_at     BIGINT      NOT NULL,
    CONSTRAINT uk_invoice UNIQUE (tenant_id, period)
);
CREATE INDEX IF NOT EXISTS idx_invoice_tenant ON cs_invoice (tenant_id);

CREATE TABLE IF NOT EXISTS cs_admin_audit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor      VARCHAR(64) NOT NULL,
    action     VARCHAR(64) NOT NULL,
    target     VARCHAR(128),
    detail     VARCHAR(1024),
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_admaudit_time ON cs_admin_audit (created_at);

CREATE TABLE IF NOT EXISTS cs_proactive_event (
    event_key  VARCHAR(128) PRIMARY KEY,
    tenant_id  VARCHAR(32) NOT NULL,
    user_id    VARCHAR(32) NOT NULL,
    session_id VARCHAR(32),
    payload    VARCHAR(1024),
    status     VARCHAR(16) NOT NULL DEFAULT 'SENT',
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_proactive_tenant ON cs_proactive_event (tenant_id, created_at);
