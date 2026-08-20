import { describe, expect, it } from "vitest";
import { DEFAULT_SETTINGS, isConfigured, toWebSocketBaseUrl } from "./StoneSyncSettings";

describe("toWebSocketBaseUrl", () => {
	it("converts https to wss", () => {
		expect(toWebSocketBaseUrl("https://stonesync.example.com")).toBe(
			"wss://stonesync.example.com"
		);
	});

	it("converts http to ws", () => {
		expect(toWebSocketBaseUrl("http://localhost:8080")).toBe("ws://localhost:8080");
	});

	it("leaves an explicit ws/wss URL unchanged", () => {
		expect(toWebSocketBaseUrl("wss://already.example.com")).toBe(
			"wss://already.example.com"
		);
		expect(toWebSocketBaseUrl("ws://already.example.com")).toBe(
			"ws://already.example.com"
		);
	});

	it("strips a trailing slash", () => {
		expect(toWebSocketBaseUrl("https://stonesync.example.com/")).toBe(
			"wss://stonesync.example.com"
		);
	});

	it("defaults to wss when no scheme is given", () => {
		expect(toWebSocketBaseUrl("stonesync.example.com")).toBe(
			"wss://stonesync.example.com"
		);
	});
});

describe("isConfigured", () => {
	it("is false for default (empty) settings", () => {
		expect(isConfigured(DEFAULT_SETTINGS)).toBe(false);
	});

	it("is true once serverUrl, apiKey and vaultId are all set", () => {
		expect(
			isConfigured({
				serverUrl: "https://example.com",
				apiKey: "key",
				vaultId: "vault-1",
				syncEnabled: true,
				displayName: "Tester",
				mirrorFolder: "_shared",
				mirrors: {},
			})
		).toBe(true);
	});

	it("is false if any single field is blank", () => {
		expect(
			isConfigured({
				serverUrl: "",
				apiKey: "key",
				vaultId: "vault-1",
				syncEnabled: true,
				displayName: "Tester",
				mirrorFolder: "_shared",
				mirrors: {},
			})
		).toBe(false);
	});
});
