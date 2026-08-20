import { describe, expect, it } from "vitest";
import { canManage, canRead, canWrite, effectiveLevel, prefixMatches, type VaultPermissions } from "./permissions";

const permissions = (vaultLevel: VaultPermissions["vaultLevel"], rules: VaultPermissions["rules"] = []): VaultPermissions => ({
	vaultLevel,
	rules,
});

describe("client-side permission resolution", () => {
	it("matches whole path segments only", () => {
		expect(prefixMatches("Team", "Team/plan.md")).toBe(true);
		expect(prefixMatches("Team", "Team")).toBe(true);
		expect(prefixMatches("Team", "Teamwork.md")).toBe(false);
		expect(prefixMatches("", "anything.md")).toBe(true);
	});

	it("falls back to the vault level when no rule matches", () => {
		expect(effectiveLevel(permissions("EDITOR"), "Notes/x.md")).toBe("EDITOR");
	});

	it("lets the most specific rule win", () => {
		const perms = permissions("VIEWER", [
			{ pathPrefix: "Team", level: "EDITOR" },
			{ pathPrefix: "Team/Payroll", level: "NONE" },
		]);

		expect(effectiveLevel(perms, "Team/plan.md")).toBe("EDITOR");
		expect(effectiveLevel(perms, "Team/Payroll/2026.md")).toBe("NONE");
		expect(effectiveLevel(perms, "Elsewhere.md")).toBe("VIEWER");
	});

	it("knows what a viewer may and may not do", () => {
		const perms = permissions("VIEWER");
		expect(canRead(perms, "a.md")).toBe(true);
		expect(canWrite(perms, "a.md")).toBe(false);
		expect(canManage(perms)).toBe(false);
	});

	it("treats a NONE rule as no access at all", () => {
		const perms = permissions("EDITOR", [{ pathPrefix: "Privat", level: "NONE" }]);
		expect(canRead(perms, "Privat/diary.md")).toBe(false);
		expect(canWrite(perms, "Privat/diary.md")).toBe(false);
		expect(canRead(perms, "Shared/plan.md")).toBe(true);
	});

	it("recognizes an owner as the one who may manage the vault", () => {
		expect(canManage(permissions("OWNER"))).toBe(true);
	});
});
