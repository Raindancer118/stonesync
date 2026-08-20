-- Binds each invite to the specific colleague it was created for, so a leaked invite link is
-- useless to anyone whose Authentik-verified email doesn't match (found via agy architecture
-- review: previously any Authentik account could redeem any invite link it got hold of).
ALTER TABLE vault_invites ADD COLUMN invitee_email VARCHAR(320);
UPDATE vault_invites SET invitee_email = 'unknown@invalid.invite' WHERE invitee_email IS NULL;
ALTER TABLE vault_invites ALTER COLUMN invitee_email SET NOT NULL;

-- Short-lived, single-use exchange codes so a freshly minted (long-lived) API key never has to
-- appear in the obsidian:// deep link itself - it would otherwise sit in plaintext in the
-- browser's history and could be intercepted by another app claiming the same URL scheme.
CREATE TABLE api_key_exchanges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash    VARCHAR(64) NOT NULL UNIQUE,
    api_key      VARCHAR(255) NOT NULL,
    vault_id     UUID NOT NULL REFERENCES vaults(id),
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ
);
CREATE INDEX idx_api_key_exchanges_code_hash ON api_key_exchanges(code_hash);
