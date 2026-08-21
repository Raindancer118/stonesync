import { describe, it, expect } from "vitest";
import { pickUserColor } from "./userColor";

describe("pickUserColor", () => {
	it("is deterministic - the same name always gets the same color", () => {
		expect(pickUserColor("Tom")).toBe(pickUserColor("Tom"));
	});

	it("returns one of the defined cursor colors (a real hex color, not empty/undefined)", () => {
		const color = pickUserColor("Colleague");
		expect(color).toMatch(/^#[0-9a-f]{6}$/i);
	});

	it("changes when the seed changes (not a constant fallback)", () => {
		const colors = new Set(["Alice", "Bob", "Carol", "Dave", "Eve", "Frank", "Grace", "Heidi"].map(pickUserColor));
		expect(colors.size).toBeGreaterThan(1);
	});
});
