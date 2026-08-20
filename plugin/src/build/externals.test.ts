import { describe, expect, it } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { OBSIDIAN_PROVIDED_MODULES } from "../../esbuild.externals.mjs";

/**
 * Regression guard for the bug that broke live sync entirely: with `@codemirror/*` bundled
 * into main.js, the plugin's editor extension is built against a *second* CM6 copy, which
 * Obsidian's editor rejects ("Unrecognized extension value in extension set"). Result: no Yjs
 * binding, no remote cursors, no propagation of edits - while everything else (WebSocket,
 * status bar) still looked healthy.
 */
describe("esbuild externals", () => {
	it("keeps every CodeMirror/Lezer package that Obsidian provides out of the bundle", () => {
		for (const mod of [
			"@codemirror/state",
			"@codemirror/view",
			"@codemirror/language",
			"@lezer/common",
			"@lezer/highlight",
			"obsidian",
		]) {
			expect(OBSIDIAN_PROVIDED_MODULES).toContain(mod);
		}
	});

	it("does not bundle a second CodeMirror copy into the built main.js", () => {
		const bundlePath = new URL("../../main.js", import.meta.url).pathname;
		if (!existsSync(bundlePath)) return; // nothing built yet (e.g. fresh CI checkout before `npm run build`)
		const bundle = readFileSync(bundlePath, "utf8");
		expect(bundle).toContain('require("@codemirror/state")');
		expect(bundle).toContain('require("@codemirror/view")');
		expect(bundle).not.toContain("Unrecognized extension value");
	});
});
