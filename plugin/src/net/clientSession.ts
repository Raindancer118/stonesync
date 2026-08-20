/**
 * One random id per plugin load (not per file, not persisted), sent as the `X-StoneSync-Session`
 * header on requests that can trigger a vault-event broadcast (resolve, delete) - see
 * `DocumentController#SESSION_HEADER` server-side. Lets `VaultEventsManager` recognize and
 * ignore this same client's own events instead of missing others while paused during a bulk
 * operation (see agy architecture review).
 */
let cachedSessionId: string | null = null;

export function getClientSessionId(): string {
	if (!cachedSessionId) {
		cachedSessionId = crypto.randomUUID();
	}
	return cachedSessionId;
}
