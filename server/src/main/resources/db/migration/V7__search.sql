-- Full-text search across every document in a vault: notes (plaintext already decoded by the
-- materialize side-channel - see MaterializeService) and attachments (text extracted at upload
-- time - PDFs via PDFBox, images/scanned PDF pages via OCR - see AttachmentTextExtractionService).
-- Filenames are indexed too (weight B), so an attachment with no extractable text (or an
-- unsupported format) is still findable by name.

ALTER TABLE documents ADD COLUMN plain_text TEXT;
ALTER TABLE documents ADD COLUMN search_vector tsvector;

-- Maintained by trigger rather than a generated column: to_tsvector() is STABLE, not IMMUTABLE
-- (its result depends on the text search configuration), so Postgres refuses it in a `GENERATED
-- ALWAYS AS (...) STORED` expression. A BEFORE INSERT/UPDATE trigger has no such restriction and
-- is the standard, documented pattern for this.
CREATE FUNCTION documents_search_vector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.plain_text, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.current_path, '')), 'B');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER documents_search_vector_update
    BEFORE INSERT OR UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION documents_search_vector_trigger();

CREATE INDEX idx_documents_search_vector ON documents USING GIN(search_vector);
