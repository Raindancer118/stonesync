import { describe, expect, it } from "vitest";
import { decideReconciliation } from "./reconcile";

describe("decideReconciliation", () => {
	it("seeds an empty server document with the local file content", () => {
		expect(decideReconciliation("", "# Local")).toEqual({ action: "seed", content: "# Local" });
	});

	it("does nothing when both sides are empty", () => {
		expect(decideReconciliation("", "")).toEqual({ action: "none" });
	});

	it("does nothing when both sides already agree - the identical-file case that used to duplicate", () => {
		expect(decideReconciliation("# Shared", "# Shared")).toEqual({ action: "none" });
	});

	it("lets existing server content win over a differing local editor state", () => {
		expect(decideReconciliation("# Server", "# Editor")).toEqual({
			action: "replaceEditor",
			content: "# Server",
		});
	});

	it("never seeds when the server already has content, even if the editor is empty", () => {
		expect(decideReconciliation("# Server", "")).toEqual({ action: "replaceEditor", content: "# Server" });
	});
});
