-- Cross-vault linking: a human-readable vault namespace, a server-side link index built from
-- materialized plaintext, and a queue of link rewrites that clients apply as ordinary Yjs edits.

-- Namespace used in links: [[sales:Jahresabschluss]]. Nullable until an owner picks one, so
-- existing vaults keep working untouched.
ALTER TABLE vaults ADD COLUMN slug VARCHAR(64);
CREATE UNIQUE INDEX uq_vaults_slug ON vaults(slug) WHERE slug IS NOT NULL;

-- One row per link found in a note. Only cross-vault links are indexed: plain [[Note]] links
-- stay Obsidian's own business and must keep working with no server involved at all.
CREATE TABLE document_links (
    id                  BIGSERIAL PRIMARY KEY,
    source_document_id  UUID NOT NULL REFERENCES documents(id),
    source_vault_id     UUID NOT NULL REFERENCES vaults(id),
    target_vault_slug   VARCHAR(64) NOT NULL,
    target_path         TEXT NOT NULL,
    target_document_id  UUID,
    link_text           TEXT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_links_source ON document_links(source_document_id);
CREATE INDEX idx_document_links_target ON document_links(target_vault_slug, target_path);
CREATE INDEX idx_document_links_target_doc ON document_links(target_document_id);

-- A pending link rewrite for one document: "replace this exact link text with that one".
-- The server never touches Yjs itself - it only says what should change; whichever client has
-- (or next opens) the document applies it as a normal edit. Same pattern as the restore queue.
CREATE TABLE link_rewrites (
    id           BIGSERIAL PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents(id),
    old_link     TEXT NOT NULL,
    new_link     TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at   TIMESTAMPTZ
);
CREATE INDEX idx_link_rewrites_pending ON link_rewrites(document_id) WHERE applied_at IS NULL;
