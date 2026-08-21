-- Makes every table that references documents(id) cascade-delete when the document row itself is
-- deleted. Previously AdminService#deleteVault(UUID, boolean)'s force path had to pre-delete each
-- of these tables in application code, in a fixed order, before deleting the documents themselves -
-- a real production incident showed that ordering is not actually race-free: a still-connected
-- client's live edit landing between the yjs_updates pre-delete and the final documents delete
-- re-inserted a row that the documents delete's own foreign key check then tripped over. A single
-- cascading DELETE FROM documents is one atomic statement with no such window.

ALTER TABLE yjs_updates
    DROP CONSTRAINT yjs_updates_document_id_fkey,
    ADD CONSTRAINT yjs_updates_document_id_fkey FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE yjs_snapshots
    DROP CONSTRAINT yjs_snapshots_document_id_fkey,
    ADD CONSTRAINT yjs_snapshots_document_id_fkey FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE attachments
    DROP CONSTRAINT attachments_document_id_fkey,
    ADD CONSTRAINT attachments_document_id_fkey FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE document_restore_queue
    DROP CONSTRAINT document_restore_queue_document_id_fkey,
    ADD CONSTRAINT document_restore_queue_document_id_fkey FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE document_links
    DROP CONSTRAINT document_links_source_document_id_fkey,
    ADD CONSTRAINT document_links_source_document_id_fkey FOREIGN KEY (source_document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE link_rewrites
    DROP CONSTRAINT link_rewrites_document_id_fkey,
    ADD CONSTRAINT link_rewrites_document_id_fkey FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;
