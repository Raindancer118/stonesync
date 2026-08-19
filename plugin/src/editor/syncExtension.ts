import { Compartment, type Extension } from "@codemirror/state";
import { yCollab } from "y-codemirror.next";
import type { DocumentSession } from "../sync/DocumentSession";

/**
 * A single, shared Compartment object that is registered globally (once) via
 * `plugin.registerEditorExtension(createSyncExtension())`. CM6 compartments
 * are independent per EditorView: a `dispatch()` with
 * `syncCompartment.reconfigure(...)` against ONE specific EditorView instance
 * changes only that instance, not other open editors.
 */
export const syncCompartment = new Compartment();

export function createSyncExtension(): Extension {
	return [syncCompartment.of([])];
}

/**
 * Builds the extension that provides Yjs text sync + live cursor rendering
 * of other users (remote selections as CM6 decorations, including name/color
 * from the awareness state) for a specific file. `yCollab` from
 * `y-codemirror.next` handles both in a single extension.
 */
export function buildCollabExtension(session: DocumentSession): Extension {
	return yCollab(session.ytext, session.awareness, { undoManager: false });
}

/** Empty extension, used to reset the compartment when leaving a synchronized document. */
export function emptyExtension(): Extension {
	return [];
}
