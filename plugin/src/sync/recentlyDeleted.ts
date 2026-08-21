/**
 * Closes a real race found live: `VaultEventsManager.reconcileMissingFiles()` (run once per
 * connect) snapshots the vault's document list once and then works through downloading whatever
 * is locally missing via a small concurrency-limited queue - for a vault with hundreds of
 * documents, fully draining that queue can take minutes (see `AsyncConfig`-style bounded pools
 * server-side for the same reasoning). Deleting a file *while* an earlier reconciliation pass is
 * still working through its queue used to get it silently re-downloaded a few seconds later: the
 * queued task was built from a snapshot taken before the delete, and neither
 * `downloadTextDocument`/`downloadAttachmentDocument` nor the queue re-checked anything before
 * writing - only "does the local file already exist", which is false right after a delete.
 *
 * `SyncManager.handleDelete` marks a path here the moment it starts deleting; every downloader
 * checks it first and skips instead of resurrecting the file. A TTL (not a permanent blocklist)
 * so a colleague genuinely re-creating the same path later is not silently ignored.
 *
 * Live incident found this set too short: a bulk delete of ~1300 files queues that many
 * `resolveDeleteDocumentId` + `deleteDocument` round-trips, and under that load individual
 * deletes can take well over a minute to actually land server-side. Any reconciliation pass
 * (triggered by a websocket reconnect, which server-side load itself makes more likely) that
 * lands after the mark expired but before the delete confirmed re-downloaded the file - forever,
 * since nothing deletes it a second time. 30 minutes comfortably covers realistic bulk-delete
 * drain times; see also the `pendingDeletePaths` check in `DocumentDownloader`, which guards the
 * same race for deletes still queued for offline retry regardless of how long that takes.
 */
const RECENTLY_DELETED_TTL_MS = 30 * 60_000;

const recentlyDeleted = new Map<string, number>();

export function markRecentlyDeleted(path: string): void {
	recentlyDeleted.set(path, Date.now());
}

export function wasRecentlyDeleted(path: string): boolean {
	const deletedAt = recentlyDeleted.get(path);
	if (deletedAt === undefined) return false;
	if (Date.now() - deletedAt > RECENTLY_DELETED_TTL_MS) {
		recentlyDeleted.delete(path);
		return false;
	}
	return true;
}
