-- Vault invite links: a single-use, expiring token that grants a specific role on a specific
-- vault to whoever authenticates via Authentik and redeems it. token_hash follows the same
-- hashed-lookup pattern as api_keys.key_hash - the raw token is never stored.
CREATE TABLE vault_invites (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id    UUID NOT NULL REFERENCES vaults(id),
    role        VARCHAR(32) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_vault_invites_token_hash ON vault_invites(token_hash);
