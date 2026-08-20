import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { applyLinkRewrite } from "./linkRewrites";

function textWith(content: string): Y.Text {
	const doc = new Y.Doc();
	const ytext = doc.getText("content");
	ytext.insert(0, content);
	return ytext;
}

describe("applying a queued link repair", () => {
	it("replaces the renamed link and leaves the rest of the note untouched", () => {
		const ytext = textWith("Intro\n\nSiehe [[docs:API|die Doku]] fuer Details.\n");

		const replaced = applyLinkRewrite(ytext, "[[docs:API|die Doku]]", "[[docs:Referenz/API v2|die Doku]]");

		expect(replaced).toBe(1);
		expect(ytext.toString()).toBe("Intro\n\nSiehe [[docs:Referenz/API v2|die Doku]] fuer Details.\n");
	});

	it("repairs every occurrence in the note", () => {
		const ytext = textWith("[[docs:API]] und nochmal [[docs:API]]");

		expect(applyLinkRewrite(ytext, "[[docs:API]]", "[[docs:API v2]]")).toBe(2);
		expect(ytext.toString()).toBe("[[docs:API v2]] und nochmal [[docs:API v2]]");
	});

	it("does nothing when the link is not in the note (any more)", () => {
		const ytext = textWith("nothing to see here");

		expect(applyLinkRewrite(ytext, "[[docs:API]]", "[[docs:API v2]]")).toBe(0);
		expect(ytext.toString()).toBe("nothing to see here");
	});

	it("never touches a local link that happens to look similar", () => {
		const ytext = textWith("[[API]] and [[docs:API]]");

		applyLinkRewrite(ytext, "[[docs:API]]", "[[docs:API v2]]");

		expect(ytext.toString()).toBe("[[API]] and [[docs:API v2]]");
	});

	it("produces a single Yjs update, so collaborators see one clean change", () => {
		const doc = new Y.Doc();
		const ytext = doc.getText("content");
		ytext.insert(0, "[[docs:API]] [[docs:API]]");
		let updates = 0;
		doc.on("update", () => updates++);

		applyLinkRewrite(ytext, "[[docs:API]]", "[[docs:API v2]]");

		expect(updates).toBe(1);
	});
});
