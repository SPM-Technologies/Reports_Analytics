-- Identity module: hospitals, users, their hospital roles, and the audit log (schema "identity").
-- Applied first at every startup by the administrator connection (pr-app SchemaMigrator), as ONE script,
-- so DO blocks are allowed. Must stay idempotent. Nothing here is protected by row-level security:
-- login has to read users before anyone is known; the application filters these tables itself.
CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE IF NOT EXISTS identity.hospital (
    hospital_id    SERIAL PRIMARY KEY,
    hospital_code  VARCHAR(6)   NOT NULL UNIQUE CHECK (hospital_code ~ '^[A-Z][A-Z0-9]{1,5}$'),
    name           VARCHAR(120) NOT NULL,
    city           VARCHAR(60),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP
);
-- every existing patient belongs to this hospital (stage 5a migration); hospital_id 1 is fixed
INSERT INTO identity.hospital (hospital_id, hospital_code, name, city) VALUES (1, 'H001', 'Main Hospital', 'Mumbai')
ON CONFLICT (hospital_id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('identity.hospital', 'hospital_id'),
              greatest((SELECT max(hospital_id) FROM identity.hospital), 1));

CREATE TABLE IF NOT EXISTS identity.app_user (
    user_id        BIGSERIAL PRIMARY KEY,
    username       VARCHAR(40)  NOT NULL CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,39}$'),
    full_name      VARCHAR(100) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,             -- BCrypt
    super_admin    BOOLEAN      NOT NULL DEFAULT FALSE, -- may manage hospitals and all users; sees data only of assigned hospitals
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins  INTEGER      NOT NULL DEFAULT 0,
    locked_until   TIMESTAMP,
    last_login_at  TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_user_username ON identity.app_user (username);

-- a user's role in each hospital they work for (several hospitals per user are allowed)
CREATE TABLE IF NOT EXISTS identity.app_user_hospital (
    user_id      BIGINT      NOT NULL REFERENCES identity.app_user (user_id) ON DELETE CASCADE,
    hospital_id  INTEGER     NOT NULL REFERENCES identity.hospital (hospital_id),
    role         VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'REGISTRATION', 'REPORTS')),
    PRIMARY KEY (user_id, hospital_id)
);
CREATE INDEX IF NOT EXISTS idx_user_hospital_hospital ON identity.app_user_hospital (hospital_id);

-- append-only: the application account may insert and read, never change or delete
CREATE TABLE IF NOT EXISTS identity.audit_log (
    audit_id        BIGSERIAL PRIMARY KEY,
    at              TIMESTAMP    NOT NULL DEFAULT now(),
    user_id         BIGINT,
    username        VARCHAR(40),
    hospital_id     INTEGER,
    action          VARCHAR(40)  NOT NULL,
    detail          VARCHAR(500),
    client_address  VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_audit_at ON identity.audit_log (at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_hospital_at ON identity.audit_log (hospital_id, at DESC);

-- Stage 5b: roles are data. Built-in roles (hospital_id NULL, usable in every hospital) are written from
-- the Java enum identity.model.Role at every startup; hospitals add their own (hospital_id set).
CREATE TABLE IF NOT EXISTS identity.role (
    role_key     VARCHAR(40)  PRIMARY KEY CHECK (role_key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    hospital_id  INTEGER      REFERENCES identity.hospital (hospital_id),
    label        VARCHAR(60)  NOT NULL,
    permissions  VARCHAR(30)[] NOT NULL DEFAULT '{}',
    builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMP
);
-- the built-in keys must exist before the foreign key below (permissions are filled in by the application)
INSERT INTO identity.role (role_key, label, builtin) VALUES
    ('ADMIN', 'Hospital administrator', TRUE), ('REGISTRATION', 'Registration desk', TRUE),
    ('REPORTS', 'Reports and analytics', TRUE), ('ANALYST', 'Analyst', TRUE)
ON CONFLICT (role_key) DO NOTHING;
ALTER TABLE identity.app_user_hospital ALTER COLUMN role TYPE VARCHAR(40);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'app_user_hospital_role_check') THEN
        ALTER TABLE identity.app_user_hospital DROP CONSTRAINT app_user_hospital_role_check;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_hospital_role') THEN
        ALTER TABLE identity.app_user_hospital ADD CONSTRAINT fk_user_hospital_role
            FOREIGN KEY (role) REFERENCES identity.role (role_key);
    END IF;
END $$;

-- Stage 5b: raised when a password is reset or an account deactivated; sessions signed in before are ended
ALTER TABLE identity.app_user ADD COLUMN IF NOT EXISTS session_epoch INTEGER NOT NULL DEFAULT 0;
