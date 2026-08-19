-- StoneSync initial schema

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE vaults (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    owner_id   UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_vault_access (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  UUID NOT NULL REFERENCES users(id),
    vault_id UUID NOT NULL REFERENCES vaults(id),
    role     VARCHAR(32) NOT NULL,
    UNIQUE (user_id, vault_id)
);

CREATE TABLE documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id      UUID NOT NULL REFERENCES vaults(id),
    current_path  TEXT NOT NULL,
    content_type  VARCHAR(32) NOT NULL,
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_vault ON documents(vault_id);

CREATE TABLE yjs_updates (
    id           BIGSERIAL PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents(id),
    update_bytes BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_yjs_updates_document ON yjs_updates(document_id);

CREATE TABLE yjs_snapshots (
    document_id UUID PRIMARY KEY REFERENCES documents(id),
    state_bytes BYTEA NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attachments (
    document_id  UUID PRIMARY KEY REFERENCES documents(id),
    content_hash VARCHAR(64) NOT NULL,
    size         BIGINT NOT NULL,
    storage_path TEXT NOT NULL,
    modified_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachments_hash ON attachments(content_hash);

CREATE TABLE api_keys (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id),
    name       VARCHAR(255) NOT NULL,
    key_hash   VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
