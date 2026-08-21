import { describe, it, expect } from "vitest";
import { StaleGuard } from "./staleGuard";

describe("StaleGuard", () => {
	it("a token is current right after it's started, with nothing newer", () => {
		const guard = new StaleGuard();
		const token = guard.start();

		expect(guard.isCurrent(token)).toBe(true);
	});

	it("an older token stops being current once a newer one starts - the out-of-order-response case", () => {
		const guard = new StaleGuard();
		const older = guard.start();
		const newer = guard.start();

		expect(guard.isCurrent(older)).toBe(false);
		expect(guard.isCurrent(newer)).toBe(true);
	});

	it("a fresh guard with nothing started yet has no current token", () => {
		const guard = new StaleGuard();

		expect(guard.isCurrent(0)).toBe(false);
		expect(guard.isCurrent(1)).toBe(false);
	});

	it("tokens keep increasing across many starts", () => {
		const guard = new StaleGuard();
		const tokens = Array.from({ length: 5 }, () => guard.start());

		expect(new Set(tokens).size).toBe(5);
		expect(guard.isCurrent(tokens[tokens.length - 1])).toBe(true);
	});
});
