import { Compartment, type Extension } from "@codemirror/state";
import { yCollab } from "y-codemirror.next";
import type { DocumentSession } from "../sync/DocumentSession";

/**
 * Ein einziges, geteiltes Compartment-Objekt, das global (einmal) via
 * `plugin.registerEditorExtension(createSyncExtension())` registriert wird.
 * CM6-Compartments sind pro EditorView unabhängig: ein `dispatch()` mit
 * `syncCompartment.reconfigure(...)` gegen EINE bestimmte EditorView-Instanz
 * ändert nur diese Instanz, nicht andere offene Editoren.
 */
export const syncCompartment = new Compartment();

export function createSyncExtension(): Extension {
	return [syncCompartment.of([])];
}

/**
 * Baut die Extension, die Yjs-Text-Sync + Live-Cursor-Rendering anderer
 * Nutzer (Remote-Selections als CM6-Decorations, inkl. Name/Farbe aus dem
 * Awareness-State) für eine konkrete Datei liefert. `yCollab` aus
 * `y-codemirror.next` übernimmt beides in einer Extension.
 */
export function buildCollabExtension(session: DocumentSession): Extension {
	return yCollab(session.ytext, session.awareness, { undoManager: false });
}

/** Leere Extension, um das Compartment beim Verlassen eines synchronisierten Dokuments zurückzusetzen. */
export function emptyExtension(): Extension {
	return [];
}
