-- Fuzzy/typo-tolerant search on top of the exact full-text search from V7: websearch_to_tsquery
-- only matches complete, stemmed words, so a typo or a partial word (exactly what's typed so far
-- in a live-search box) would otherwise miss entirely. pg_trgm trigram similarity fills that gap
-- for the note title (current_path) and the note/attachment plaintext, combined with the existing
-- ts_rank in DocumentRepository#searchRaw.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_documents_path_trgm ON documents USING GIN (current_path gin_trgm_ops);
CREATE INDEX idx_documents_plain_text_trgm ON documents USING GIN (plain_text gin_trgm_ops);
