import { defineConfig } from "vitest/config";
import { resolve } from "node:path";

/**
 * End-to-end suite: runs the *real* client networking stack against a *real* StoneSync server
 * (see e2e/README). Separate from the unit config because it needs a server and a longer
 * timeout; `obsidian` is aliased to a small stub so the client code runs outside Obsidian.
 */
export default defineConfig({
	resolve: {
		alias: {
			obsidian: resolve(__dirname, "e2e/obsidian.stub.ts"),
		},
	},
	test: {
		environment: "node",
		include: ["e2e/**/*.e2e.test.ts"],
		testTimeout: 30000,
		hookTimeout: 30000,
	},
});
