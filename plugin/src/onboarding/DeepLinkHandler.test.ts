import { describe, expect, it } from "vitest";
import { parseConnectParams } from "./DeepLinkHandler";

describe("parseConnectParams", () => {
	it("parses all four required params", () => {
		const result = parseConnectParams({
			serverUrl: "https://stonesync.tstieh.de",
			apiKey: "some-api-key",
			vaultId: "11111111-1111-1111-1111-111111111111",
			displayName: "Jane Doe",
		});

		expect(result).toEqual({
			serverUrl: "https://stonesync.tstieh.de",
			apiKey: "some-api-key",
			vaultId: "11111111-1111-1111-1111-111111111111",
			displayName: "Jane Doe",
		});
	});

	it("returns null when a required param is missing", () => {
		expect(
			parseConnectParams({
				serverUrl: "https://stonesync.tstieh.de",
				apiKey: "some-api-key",
				vaultId: "11111111-1111-1111-1111-111111111111",
				// displayName missing
			})
		).toBeNull();
	});

	it("returns null when a required param is an empty string", () => {
		expect(
			parseConnectParams({
				serverUrl: "",
				apiKey: "some-api-key",
				vaultId: "11111111-1111-1111-1111-111111111111",
				displayName: "Jane Doe",
			})
		).toBeNull();
	});

	it("returns null for a completely empty params object", () => {
		expect(parseConnectParams({})).toBeNull();
	});
});
