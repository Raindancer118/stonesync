// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import * as Y from "yjs";
import { Awareness } from "y-protocols/awareness";
import { buildCollabExtension, createSyncExtension, emptyExtension, syncCompartment } from "./syncExtension";
import type { DocumentSession } from "../sync/DocumentSession";

/**
 * The editor half of live collaboration: typing has to reach the CRDT, remote CRDT changes have
 * to reach the editor, and remote awareness has to be rendered as a cursor. Exercised through
 * the same compartment dance `SyncManager` performs on a real Obsidian editor.
 */
function fakeSession(): Pick<DocumentSession, "ytext" | "awareness"> & { doc: Y.Doc } {
	const doc = new Y.Doc();
	return { doc, ytext: doc.getText("content"), awareness: new Awareness(doc) };
}

function createView(): EditorView {
	const parent = document.createElement("div");
	document.body.appendChild(parent);
	return new EditorView({ state: EditorState.create({ extensions: [createSyncExtension()] }), parent });
}

describe("collab editor extension", () => {
	it("sends local typing into the shared Y.Text", () => {
		const session = fakeSession();
		const view = createView();
		view.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session as DocumentSession)) });

		view.dispatch({ changes: { from: 0, insert: "hello from the editor" } });

		expect(session.ytext.toString()).toBe("hello from the editor");
		view.destroy();
	});

	it("applies a remote CRDT change to the editor - watching someone else type", () => {
		const session = fakeSession();
		const view = createView();
		view.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session as DocumentSession)) });

		// A remote peer's update, applied the way StoneSyncSocket applies incoming 0x00 frames.
		const remote = new Y.Doc();
		remote.getText("content").insert(0, "typed elsewhere");
		Y.applyUpdate(session.doc, Y.encodeStateAsUpdate(remote));

		expect(view.state.doc.toString()).toBe("typed elsewhere");
		view.destroy();
	});

	it("renders a remote collaborator's cursor from awareness state", () => {
		const session = fakeSession();
		const view = createView();
		view.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session as DocumentSession)) });
		session.ytext.insert(0, "shared line");

		// Simulate what applyAwarenessUpdate does for a remote client with a cursor.
		session.awareness.getStates().set(42, {
			user: { name: "Alice", color: "#e57373" },
			cursor: {
				anchor: Y.createRelativePositionFromTypeIndex(session.ytext, 2),
				head: Y.createRelativePositionFromTypeIndex(session.ytext, 5),
			},
		});
		session.awareness.emit("change", [{ added: [42], updated: [], removed: [] }, "test"]);
		session.awareness.emit("update", [{ added: [42], updated: [], removed: [] }, "test"]);

		expect(view.dom.querySelectorAll(".cm-ySelection, .cm-ySelectionCaret, .cm-ySelectionInfo").length).toBeGreaterThan(0);
		view.destroy();
	});

	it("detaches cleanly when leaving a synchronized document", () => {
		const session = fakeSession();
		const view = createView();
		view.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session as DocumentSession)) });
		view.dispatch({ effects: syncCompartment.reconfigure(emptyExtension()) });

		view.dispatch({ changes: { from: 0, insert: "no longer synced" } });

		expect(session.ytext.toString()).toBe("");
		view.destroy();
	});
});
