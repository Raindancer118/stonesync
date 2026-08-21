/** The subset of {@code DocumentIdResolver} this needs - kept as an interface (rather than
 * importing the concrete class) so this logic, and its test, never need to touch the real
 * resolver's `obsidian` import. */
export interface DocumentIdLookup {
	peekId(path: string): string | undefined;
	resolve(path: string): Promise<string>;
}

/**
 * Finds the document id to delete for a local path.
 *
 * Prefers the cached id (no network call - the common case, since most deleted files were
 * opened/edited at some point, which is what populates the cache). Falls back to a real
 * `resolve()` when nothing is cached - which happens for a file that arrived via a bulk vault
 * download or the live vault-events "document_created" reactor and was never opened locally.
 *
 * Real bug this closes: without the fallback, deleting such a file silently never reached the
 * server at all (no error, no Notice) - the file only vanished locally, while the server kept
 * considering it live, so a later full vault re-download (or another collaborator's client)
 * resurrected it. `resolve()` is safe to call here even though the file no longer exists
 * locally: for a path that still exists server-side it just finds and returns the existing
 * document's id (no new document gets created - see `DocumentService.resolveOrCreate`'s
 * existing-path branch, which only checks read access).
 */
export async function resolveDeleteDocumentId(
	resolver: DocumentIdLookup | null | undefined,
	path: string
): Promise<string | undefined> {
	if (!resolver) return undefined;
	const cached = resolver.peekId(path);
	if (cached) return cached;
	return resolver.resolve(path);
}
