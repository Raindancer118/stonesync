import { describe, expect, it, vi } from "vitest";
import { ReconnectBackoff } from "./backoff";

describe("ReconnectBackoff", () => {
	it("starts at the configured base delay", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 30000,
			jitter: () => 0.5,
		});

		expect(backoff.nextDelayMs()).toBe(1000);
	});

	it("doubles the delay on each subsequent failed attempt", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 60000,
			jitter: () => 0.5,
		});

		expect(backoff.nextDelayMs()).toBe(1000);
		expect(backoff.nextDelayMs()).toBe(2000);
		expect(backoff.nextDelayMs()).toBe(4000);
		expect(backoff.nextDelayMs()).toBe(8000);
	});

	it("caps the delay at maxDelayMs", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 5000,
			jitter: () => 0.5,
		});

		backoff.nextDelayMs(); // 1000
		backoff.nextDelayMs(); // 2000
		backoff.nextDelayMs(); // 4000
		expect(backoff.nextDelayMs()).toBe(5000); // would be 8000, capped
		expect(backoff.nextDelayMs()).toBe(5000); // stays capped
	});

	it("resets back to the base delay after a successful connection", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 30000,
			jitter: () => 0.5,
		});

		backoff.nextDelayMs(); // 1000
		backoff.nextDelayMs(); // 2000
		backoff.reset();

		expect(backoff.nextDelayMs()).toBe(1000);
	});

	it("applies jitter within the configured range without exceeding the cap", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 5000,
			jitter: () => 0.5,
			jitterRatio: 0.2,
		});

		// attempt 1: base=1000, jitterRange=[−200,+200], jitter()=0.5 -> +0 offset
		// offset = (jitter() * 2 - 1) * (delay * jitterRatio) = 0 * 200 = 0
		expect(backoff.nextDelayMs()).toBe(1000);
	});

	it("jitter can reduce the delay, but never below 0", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 100,
			maxDelayMs: 5000,
			jitter: () => 0, // minimal jitter draw -> maximal negative offset
			jitterRatio: 1, // +-100% jitter range
		});

		const delay = backoff.nextDelayMs();
		expect(delay).toBeGreaterThanOrEqual(0);
	});

	it("never returns a delay above maxDelayMs even with positive jitter", () => {
		const backoff = new ReconnectBackoff({
			baseDelayMs: 1000,
			maxDelayMs: 1200,
			jitter: () => 1, // maximal positive jitter draw
			jitterRatio: 0.5,
		});

		expect(backoff.nextDelayMs()).toBeLessThanOrEqual(1200);
	});

	it("tracks the attempt count", () => {
		const backoff = new ReconnectBackoff({ baseDelayMs: 500, maxDelayMs: 10000 });
		expect(backoff.attempt).toBe(0);
		backoff.nextDelayMs();
		expect(backoff.attempt).toBe(1);
		backoff.nextDelayMs();
		expect(backoff.attempt).toBe(2);
		backoff.reset();
		expect(backoff.attempt).toBe(0);
	});

	it("uses Math.random as the default jitter source", () => {
		const randomSpy = vi.spyOn(Math, "random").mockReturnValue(0.5);
		const backoff = new ReconnectBackoff({ baseDelayMs: 1000, maxDelayMs: 30000 });
		expect(backoff.nextDelayMs()).toBe(1000);
		randomSpy.mockRestore();
	});
});
