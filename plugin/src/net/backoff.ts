export interface ReconnectBackoffOptions {
	/** Verzögerung vor dem ersten Reconnect-Versuch, in ms. */
	baseDelayMs: number;
	/** Obergrenze für die Verzögerung, in ms. */
	maxDelayMs: number;
	/**
	 * Anteil zufälliger Streuung um den berechneten Delay (0..1).
	 * 0.2 bedeutet +/-20% Jitter. Verhindert, dass viele Clients (z.B. nach
	 * einem Server-Neustart) exakt synchron erneut verbinden ("Thundering Herd").
	 */
	jitterRatio?: number;
	/** Zufallsquelle für Jitter, Rückgabewert in [0, 1). Default: Math.random. */
	jitter?: () => number;
}

const DEFAULT_JITTER_RATIO = 0.2;

/**
 * Exponentieller Backoff mit Jitter für die WebSocket-Reconnect-Logik.
 * Wichtig für Mobile, wo die Verbindung beim Hintergrund-Wechsel gekappt
 * wird und viele kurz aufeinanderfolgende Reconnect-Versuche vermieden
 * werden sollen.
 */
export class ReconnectBackoff {
	private readonly baseDelayMs: number;
	private readonly maxDelayMs: number;
	private readonly jitterRatio: number;
	private readonly jitter: () => number;
	private attemptCount = 0;

	constructor(options: ReconnectBackoffOptions) {
		if (options.baseDelayMs <= 0) {
			throw new Error("baseDelayMs muss > 0 sein.");
		}
		if (options.maxDelayMs < options.baseDelayMs) {
			throw new Error("maxDelayMs muss >= baseDelayMs sein.");
		}

		this.baseDelayMs = options.baseDelayMs;
		this.maxDelayMs = options.maxDelayMs;
		this.jitterRatio = options.jitterRatio ?? DEFAULT_JITTER_RATIO;
		this.jitter = options.jitter ?? Math.random;
	}

	/** Anzahl der bisherigen (fehlgeschlagenen) Verbindungsversuche seit dem letzten reset(). */
	get attempt(): number {
		return this.attemptCount;
	}

	/** Liefert die Verzögerung für den nächsten Reconnect-Versuch und zählt hoch. */
	nextDelayMs(): number {
		this.attemptCount += 1;
		const exponentialDelay = this.baseDelayMs * 2 ** (this.attemptCount - 1);
		const cappedDelay = Math.min(exponentialDelay, this.maxDelayMs);

		const jitterOffset = (this.jitter() * 2 - 1) * cappedDelay * this.jitterRatio;
		const jittered = cappedDelay + jitterOffset;

		return Math.min(Math.max(0, Math.round(jittered)), this.maxDelayMs);
	}

	/** Nach erfolgreichem Connect aufrufen, um wieder bei baseDelayMs zu starten. */
	reset(): void {
		this.attemptCount = 0;
	}
}
