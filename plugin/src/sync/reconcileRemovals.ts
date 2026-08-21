/**
 * Which vault-relative paths disappeared from the server between the last reconciliation
 * snapshot and now - see `VaultEventsManager.reconcileMissingFiles` for how this closes the
 * "I deleted files while a colleague was offline and they never found out" gap.
 *
 * Returns nothing (`[]`) when there is no prior snapshot (`previouslyKnown` is `undefined`):
 * with nothing to diff against, a path missing from the very first server list this client ever
 * saw could just as easily be local content never uploaded yet as a real deletion - there is no
 * way to tell those apart, so no removal is safe to infer.
 *
 * Callers MUST check {@link isPlausibleRemovalCount} before acting on the result - see its doc
 * comment for why: a transiently incomplete server response must never be treated as "the rest
 * of the vault was deleted".
 */
export function pathsRemovedSincePreviousSnapshot(
	previouslyKnown: string[] | undefined,
	currentPaths: string[]
): string[] {
	if (!previouslyKnown) return [];
	const currentPathSet = new Set(currentPaths);
	return previouslyKnown.filter((path) => !currentPathSet.has(path));
}

/**
 * Circuit breaker found necessary live in production: a transient hiccup while listing the
 * server's documents (a mid-restart response, a brief backend error swallowed as an empty
 * result, ...) can make `listDocuments` return a list that's incomplete but still superficially
 * valid - no HTTP error, just too few entries. Feeding that straight into
 * {@link pathsRemovedSincePreviousSnapshot} treated "the server briefly looked half-empty" as
 * "the user deleted most of their vault", and {@link removeLocallyIfPresent}'s
 * `app.vault.trash()` call fires Obsidian's own "delete" event - which the plugin's *own*
 * `vault.on("delete", ...)` handler (registered in main.ts) reacts to exactly like a real,
 * intentional user delete, propagating it straight back to the server. The result, observed
 * live: three different collaborators' clients each reconciled against the same transiently
 * short list within the same few seconds and mass-deleted well over a hundred real documents -
 * a false-positive reconciliation cascading into genuine, server-side, cross-collaborator data
 * loss, in exactly the way the original "deliberately additive-only" design was trying to avoid.
 *
 * Deliberately conservative: rejects the removal pass whenever more than half of what used to be
 * known has apparently vanished in a single reconcile (and always rejects a total wipeout to
 * zero). A real mass-delete-while-offline by the user just takes one extra reconcile cycle to
 * catch up on the next successful connect - a worse UX than a cascade that deletes a
 * collaborator's vault, but by an enormous margin the safer failure mode.
 */
export function isPlausibleRemovalCount(previouslyKnownCount: number, removedCount: number): boolean {
	if (previouslyKnownCount === 0) return true; // nothing to lose confidence in
	if (removedCount === 0) return true;
	if (removedCount === previouslyKnownCount) return false; // total wipeout - never trust this
	return removedCount / previouslyKnownCount <= 0.5;
}
