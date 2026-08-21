-- Closes the same gap V9 closed for documents(id), one level up: AdminService#deleteVault's
-- force path deletes documents and access grants explicitly, then deletes the vault row itself -
-- but vault_invites, api_key_exchanges, vault_path_rules and audit_events all reference
-- vaults(id) too and were never cleared, so the final DELETE FROM vaults failed its own foreign
-- key check every time a vault actually had any of these (an invite, an audit trail entry, a
-- path rule). document_links.source_vault_id is included too for defense in depth, even though
-- those rows should already be gone via source_document_id's cascade (V9) by this point.

ALTER TABLE vault_invites
    DROP CONSTRAINT vault_invites_vault_id_fkey,
    ADD CONSTRAINT vault_invites_vault_id_fkey FOREIGN KEY (vault_id) REFERENCES vaults(id) ON DELETE CASCADE;

ALTER TABLE api_key_exchanges
    DROP CONSTRAINT api_key_exchanges_vault_id_fkey,
    ADD CONSTRAINT api_key_exchanges_vault_id_fkey FOREIGN KEY (vault_id) REFERENCES vaults(id) ON DELETE CASCADE;

ALTER TABLE vault_path_rules
    DROP CONSTRAINT vault_path_rules_vault_id_fkey,
    ADD CONSTRAINT vault_path_rules_vault_id_fkey FOREIGN KEY (vault_id) REFERENCES vaults(id) ON DELETE CASCADE;

ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_vault_id_fkey,
    ADD CONSTRAINT audit_events_vault_id_fkey FOREIGN KEY (vault_id) REFERENCES vaults(id) ON DELETE CASCADE;

ALTER TABLE document_links
    DROP CONSTRAINT document_links_source_vault_id_fkey,
    ADD CONSTRAINT document_links_source_vault_id_fkey FOREIGN KEY (source_vault_id) REFERENCES vaults(id) ON DELETE CASCADE;
