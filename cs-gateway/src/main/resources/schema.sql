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