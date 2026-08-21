import type { VaultEvent } from "../net/VaultEventsSocket";

/** Human-readable label for a queued/in-flight vault-events reaction - see `VaultEventsManager`'s
 * sync-queue tracking, shown by `SyncQueueModal`. */
export function labelForEvent(event: VaultEvent): string {
	switch (event.type) {
		case "document_created":
			return `Downloading "${event.path}"`;
		case "document_deleted":
			return `Removing "${event.path}" (deleted by a collaborator)`;
		case "access_revoked":
			return `Revoking local access to "${event.path}"`;
		case "link_rewrite":
			return `Applying a link rewrite in "${event.path}"`;
		case "vault_deleted":
			return "Stopping sync (vault was deleted on the server)";
	}
}
