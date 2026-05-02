-- ============================================================================
-- Hazelcast Admin :: V1 initial schema
--   Postgres 12+
-- ============================================================================

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,    -- SUPER_ADMIN / CLUSTER_OPERATOR / DEVELOPER / READ_ONLY
    description VARCHAR(255)
);

CREATE TABLE users (
    id                 BIGSERIAL PRIMARY KEY,
    username           VARCHAR(128) NOT NULL UNIQUE,
    email              VARCHAR(255),
    full_name          VARCHAR(255),
    password_hash      VARCHAR(255) NOT NULL,        -- bcrypt
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    locked             BOOLEAN      NOT NULL DEFAULT FALSE,
    must_change_password BOOLEAN    NOT NULL DEFAULT FALSE,
    failed_login_count INT          NOT NULL DEFAULT 0,
    last_login_at      TIMESTAMPTZ,
    auth_source        VARCHAR(32)  NOT NULL DEFAULT 'DB',   -- DB / LDAP / OIDC
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX ix_user_roles_role ON user_roles(role_id);

-- ----------------------------------------------------------------------------
-- Cluster registry (multi-cluster, multi-environment)
-- ----------------------------------------------------------------------------
CREATE TABLE clusters (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(128) NOT NULL UNIQUE,    -- display name
    environment         VARCHAR(32)  NOT NULL,           -- DEV / QA / STAGE / PROD
    hz_cluster_name     VARCHAR(128) NOT NULL,           -- Hazelcast group name
    major_version       VARCHAR(8)   NOT NULL,           -- "5.1" | "5.2"
    member_addresses    TEXT         NOT NULL,           -- comma-separated host:port
    security_mode       VARCHAR(16)  NOT NULL,           -- PLAIN / TLS / MTLS
    username            VARCHAR(128),
    password_cipher     TEXT,                            -- AES-GCM (base64)
    truststore_path     TEXT,
    truststore_pwd_cipher TEXT,
    keystore_path       TEXT,
    keystore_pwd_cipher TEXT,
    tls_protocol        VARCHAR(16),
    is_prod             BOOLEAN      NOT NULL DEFAULT FALSE, -- triggers stricter rate limits
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_clusters_env ON clusters(environment);

-- ----------------------------------------------------------------------------
-- SSH host registry (per-cluster operations) — Phase 4 wires up
-- ----------------------------------------------------------------------------
CREATE TABLE ssh_hosts (
    id                BIGSERIAL PRIMARY KEY,
    cluster_id        BIGINT       NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
    hostname          VARCHAR(255) NOT NULL,
    port              INT          NOT NULL DEFAULT 22,
    ssh_user          VARCHAR(128) NOT NULL,
    auth_method       VARCHAR(16)  NOT NULL,           -- KEY / PASSWORD
    private_key_cipher TEXT,                            -- when KEY
    passphrase_cipher  TEXT,
    password_cipher    TEXT,                            -- when PASSWORD
    hz_install_dir    VARCHAR(512),                    -- e.g. /opt/hazelcast
    java_home         VARCHAR(512),
    notes             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_ssh_hosts_cluster ON ssh_hosts(cluster_id);

-- ----------------------------------------------------------------------------
-- Audit log (forever retention; CSV export from UI)
-- ----------------------------------------------------------------------------
CREATE TABLE audit_events (
    id            BIGSERIAL PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor         VARCHAR(128) NOT NULL,
    actor_role    VARCHAR(64),
    cluster_name  VARCHAR(128),
    action        VARCHAR(64)  NOT NULL,         -- MAP_PUT / MEMBER_SHUTDOWN / USER_CREATE ...
    target        VARCHAR(512),
    reason        TEXT,                          -- mandatory for write actions
    request_hash  VARCHAR(64),                   -- sha256 hex of payload
    outcome       VARCHAR(16)  NOT NULL,         -- SUCCESS / DENIED / FAILED
    error_message TEXT,
    source_ip     VARCHAR(64)
);

CREATE INDEX ix_audit_actor    ON audit_events(actor);
CREATE INDEX ix_audit_cluster  ON audit_events(cluster_name);
CREATE INDEX ix_audit_action   ON audit_events(action);
CREATE INDEX ix_audit_occurred ON audit_events(occurred_at DESC);

-- ----------------------------------------------------------------------------
-- Saved queries (Phase 5 UI surfaces; table created up front to avoid migrations later)
-- ----------------------------------------------------------------------------
CREATE TABLE saved_queries (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(128) NOT NULL,
    cluster_id  BIGINT REFERENCES clusters(id) ON DELETE SET NULL,
    map_name    VARCHAR(128),
    query_text  TEXT NOT NULL,
    shared      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(owner_id, name)
);

-- ----------------------------------------------------------------------------
-- Alert rules + notifications (Phase 5)
-- ----------------------------------------------------------------------------
CREATE TABLE alert_rules (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    cluster_id   BIGINT REFERENCES clusters(id) ON DELETE CASCADE,
    metric       VARCHAR(64)  NOT NULL,         -- HEAP_USED_PCT / MEMBER_COUNT / SPLIT_BRAIN
    operator     VARCHAR(8)   NOT NULL,         -- > >= < <= == !=
    threshold    NUMERIC(20,4) NOT NULL,
    severity     VARCHAR(16)  NOT NULL,         -- INFO / WARN / CRITICAL
    channels     TEXT         NOT NULL,         -- comma list: EMAIL,SLACK,WEBHOOK,IN_APP
    channel_cfg  TEXT,                          -- JSON per channel target
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    severity    VARCHAR(16) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    cluster_name VARCHAR(128),
    read_at     TIMESTAMPTZ,
    target_user VARCHAR(128)         -- null = broadcast
);
CREATE INDEX ix_notifications_target ON notifications(target_user, created_at DESC);
