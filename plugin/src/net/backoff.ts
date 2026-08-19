export interface ReconnectBackoffOptions {
	/** Delay before the first reconnect attempt, in ms. */
	baseDelayMs: number;
	/** Upper bound for the delay, in ms. */
	maxDelayMs: number;
	/**
	 * Fraction of random spread around the computed delay (0..1).
	 * 0.2 means +/-20% jitter. Prevents many clients (e.g. after a server
	 * restart) from reconnecting at exactly the same time ("thundering herd").
	 */
	jitterRatio?: number;
	/** Source of randomness for jitter, return value in [0, 1). Default: Math.random. */
	jitter?: () => number;
}

const DEFAULT_JITTER_RATIO = 0.2;

/**
 * Exponential backoff with jitter for the WebSocket reconnect logic.
 * Important for mobile, where the connection is cut when switching to the
 * background and many closely-spaced reconnect attempts should be avoided.
 */
export class ReconnectBackoff {
	private readonly baseDelayMs: number;
	private readonly maxDelayMs: number;
	private readonly jitterRatio: number;
	private readonly jitter: () => number;
	private attemptCount = 0;

	constructor(options: ReconnectBackoffOptions) {
		if (options.baseDelayMs <= 0) {
			throw new Error("baseDelayMs must be > 0.");
		}
		if (options.maxDelayMs < options.baseDelayMs) {
			throw new Error("maxDelayMs must be >= baseDelayMs.");
		}

		this.baseDelayMs = options.baseDelayMs;
		this.maxDelayMs = options.maxDelayMs;
		this.jitterRatio = options.jitterRatio ?? DEFAULT_JITTER_RATIO;
		this.jitter = options.jitter ?? Math.random;
	}

	/** Number of (failed) connection attempts so far since the last reset(). */
	get attempt(): number {
		return this.attemptCount;
	}

	/** Returns the delay for the next reconnect attempt and increments the counter. */
	nextDelayMs(): number {
		this.attemptCount += 1;
		const exponentialDelay = this.baseDelayMs * 2 ** (this.attemptCount - 1);
		const cappedDelay = Math.min(exponentialDelay, this.maxDelayMs);

		const jitterOffset = (this.jitter() * 2 - 1) * cappedDelay * this.jitterRatio;
		const jittered = cappedDelay + jitterOffset;

		return Math.min(Math.max(0, Math.round(jittered)), this.maxDelayMs);
	}

	/** Call after a successful connect to restart at baseDelayMs. */
	reset(): void {
		this.attemptCount = 0;
	}
}
