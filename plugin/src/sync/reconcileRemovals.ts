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
 * There used to be a circuit breaker here (`isPlausibleRemovalCount`) rejecting a reconcile that
 * would remove more than half of what was previously known, added after a transient short server
 * response cascaded into a real cross-collaborator mass-delete in production. Removed by explicit
 * product decision: every reconcile result is now trusted and acted on as-is.
 */
export function pathsRemovedSincePreviousSnapshot(
	previouslyKnown: string[] | undefined,
	currentPaths: string[]
): string[] {
	if (!previouslyKnown) return [];
	const currentPathSet = new Set(currentPaths);
	return previouslyKnown.filter((path) => !currentPathSet.has(path));
}
