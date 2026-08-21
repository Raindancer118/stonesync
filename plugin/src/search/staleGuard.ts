/**
 * Tracks whether an earlier async operation's result is still relevant, given a newer one may
 * have started meanwhile - used by the live/typeahead search (quick-search modal, home view) to
 * discard a slow or out-of-order server response instead of flashing stale results when the user
 * has already typed further.
 */
export class StaleGuard {
	// `null`, not 0: a numeric-only counter starting at 0 would make isCurrent(0) true on a
	// fresh guard before start() was ever called, which is wrong - there is no "current"
	// operation yet at all, so nothing should compare equal.
	private current: number | null = null;
	private next = 1;

	/** Call at the start of a new operation; keep the returned token to check later. */
	start(): number {
		this.current = this.next;
		this.next += 1;
		return this.current;
	}

	/** True only if no newer operation has started since `token` was issued. */
	isCurrent(token: number): boolean {
		return token === this.current;
	}
}
