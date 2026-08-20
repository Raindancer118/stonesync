// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import * as Y from "yjs";
import { Awareness, encodeAwarenessUpdate, applyAwarenessUpdate } from "y-protocols/awareness";
import { buildCollabExtension, createSyncExtension, syncCompartment } from "./syncExtension";
import type { DocumentSession } from "../sync/DocumentSession";

/**
 * The thing the whole plugin exists for: seeing *where* the other person is, as a colored caret
 * with their name, inside the text.
 *
 * Unlike the plain extension test, this wires two editors together the way the real client does
 * - Yjs updates and awareness frames are encoded, shipped and applied exactly as
 * `StoneSyncSocket` does - because a remote cursor survives (or does not survive) that encoding
 * round trip, which an in-process awareness object would never reveal.
 */
interface Peer {
	doc: Y.Doc;
	ytext: Y.Text;
	awareness: Awareness;
	view: EditorView;
}

function createPeer(name: string, color: string): Peer {
	const doc = new Y.Doc();
	const ytext = doc.getText("content");
	const awareness = new Awareness(doc);
	awareness.setLocalStateField("user", { name, color });

	const parent = document.createElement("div");
	document.body.appendChild(parent);
	const view = new EditorView({ state: EditorState.create({ extensions: [createSyncExtension()] }), parent });
	view.dispatch({
		effects: syncCompartment.reconfigure(
			buildCollabExtension({ ytext, awareness } as unknown as DocumentSession)
		),
	});
	return { doc, ytext, awareness, view };
}

/** Relays document + awareness updates between two peers, like the server does. */
function connect(a: Peer, b: Peer): void {
	const link = (from: Peer, to: Peer) => {
		from.doc.on("update", (update: Uint8Array, origin: unknown) => {
			if (origin === "remote") return;
			Y.applyUpdate(to.doc, update, "remote");
		});
		from.awareness.on("update", ({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }, origin: unknown) => {
			if (origin === "remote") return;
			const changed = added.concat(updated, removed);
			if (changed.length === 0) return;
			applyAwarenessUpdate(to.awareness, encodeAwarenessUpdate(from.awareness, changed), "remote");
		});
	};
	link(a, b);
	link(b, a);
}

describe("remote cursors in the editor", () => {
	it("renders the other person's caret, in their color and with their name", () => {
		const alice = createPeer("Alice", "#e57373");
		const bob = createPeer("Bob", "#64b5f6");
		connect(alice, bob);

		alice.view.dispatch({ changes: { from: 0, insert: "a shared paragraph" } });
		expect(bob.view.state.doc.toString()).toBe("a shared paragraph");

		// Alice puts her cursor in the middle of the line. Publishing a cursor requires a focused
		// editor - that is what y-codemirror.next keys the local awareness cursor field on.
		alice.view.focus();
		alice.view.dispatch({ selection: { anchor: 8, head: 8 } });

		const caret = bob.view.dom.querySelector(".cm-ySelectionCaret");
		expect(caret, "Bob should see a caret where Alice is").not.toBeNull();
		expect(caret?.getAttribute("style") ?? "").toContain("#e57373");
		expect(caret?.textContent).toContain("Alice");

		alice.view.destroy();
		bob.view.destroy();
	});

	it("renders a remote selection range as a colored highlight", () => {
		const alice = createPeer("Alice", "#e57373");
		const bob = createPeer("Bob", "#64b5f6");
		connect(alice, bob);

		alice.view.dispatch({ changes: { from: 0, insert: "select some of this" } });
		alice.view.focus();
		alice.view.dispatch({ selection: { anchor: 0, head: 6 } });

		expect(bob.view.dom.querySelector(".cm-ySelection"), "Bob should see Alice's selection").not.toBeNull();

		alice.view.destroy();
		bob.view.destroy();
	});
});
