import { describe, expect, it } from "vitest";
import { parseConnectParams } from "./DeepLinkHandler";

describe("parseConnectParams", () => {
	it("parses both required params", () => {
		const result = parseConnectParams({
			serverUrl: "https://stonesync.tstieh.de",
			exchangeCode: "some-exchange-code",
		});

		expect(result).toEqual({
			serverUrl: "https://stonesync.tstieh.de",
			exchangeCode: "some-exchange-code",
		});
	});

	it("returns null when a required param is missing", () => {
		expect(
			parseConnectParams({
				serverUrl: "https://stonesync.tstieh.de",
				// exchangeCode missing
			})
		).toBeNull();
	});

	it("returns null when a required param is an empty string", () => {
		expect(
			parseConnectParams({
				serverUrl: "",
				exchangeCode: "some-exchange-code",
			})
		).toBeNull();
	});

	it("returns null for a completely empty params object", () => {
		expect(parseConnectParams({})).toBeNull();
	});
});
