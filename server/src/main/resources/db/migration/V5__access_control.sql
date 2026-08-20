-- Full permission management: a global system role, path-scoped access rules, and an audit trail.

-- USER = ordinary account, ADMIN = may administer every vault and user (replaces relying on the
-- single bootstrap master key for day-to-day administration).
ALTER TABLE users ADD COLUMN system_role VARCHAR(32) NOT NULL DEFAULT 'USER';

-- Overrides the vault membership role for one subtree of a vault. user_id NULL = applies to
-- everyone who has access to the vault; level NONE takes access away entirely.
CREATE TABLE vault_path_rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id    UUID NOT NULL REFERENCES vaults(id),
    path_prefix TEXT NOT NULL,
    user_id     UUID REFERENCES users(id),
    level       VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID REFERENCES users(id)
);
CREATE INDEX idx_vault_path_rules_vault ON vault_path_rules(vault_id);
-- One rule per (vault, prefix, user). Postgres treats NULLs as distinct in a plain UNIQUE
-- constraint, so the everyone-rule needs its own partial index to stay unique.
CREATE UNIQUE INDEX uq_vault_path_rules_user ON vault_path_rules(vault_id, path_prefix, user_id)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_vault_path_rules_everyone ON vault_path_rules(vault_id, path_prefix)
    WHERE user_id IS NULL;

-- Append-only trail of who did what: permission changes, content changes, and refused attempts.
CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    type        VARCHAR(64) NOT NULL,
    actor_id    UUID REFERENCES users(id),
    actor_label VARCHAR(255) NOT NULL,
    vault_id    UUID REFERENCES vaults(id),
    document_id UUID,
    path        TEXT,
    subject_id  UUID REFERENCES users(id),
    detail      TEXT
);
CREATE INDEX idx_audit_events_vault ON audit_events(vault_id, id DESC);
CREATE INDEX idx_audit_events_path ON audit_events(vault_id, path);
