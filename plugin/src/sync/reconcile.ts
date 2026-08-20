/**
 * Decides how a freshly bound editor and its (already caught-up) Y.Text should be reconciled
 * before the live Yjs binding is attached.
 *
 * The order matters: seeding must only ever happen once the server's history replay has been
 * applied. Seeding *before* that (which is what the plugin used to do) makes every device
 * insert its own copy of an identical file into the CRDT, and Yjs - correctly - keeps both:
 * the document ends up with its content duplicated instead of "synchronized".
 */
export type Reconciliation =
	/** The document is empty server-side: push the local file content into the CRDT. */
	| { action: "seed"; content: string }
	/** The CRDT holds content: it wins, the editor is overwritten with it. */
	| { action: "replaceEditor"; content: string }
	/** Editor and CRDT already agree (or both are empty): attach the binding as-is. */
	| { action: "none" };

export function decideReconciliation(remoteText: string, editorContent: string): Reconciliation {
	if (remoteText.length === 0) {
		return editorContent.length === 0 ? { action: "none" } : { action: "seed", content: editorContent };
	}
	if (remoteText === editorContent) return { action: "none" };
	return { action: "replaceEditor", content: remoteText };
}
