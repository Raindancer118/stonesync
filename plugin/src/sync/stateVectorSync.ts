import * as Y from "yjs";

/**
 * Thin, testable wrapper around Yjs' state vector diffing. Used for the
 * reconnect resync: instead of resending the complete document state after
 * a connection loss, only the delta since the last known (pre-offline)
 * state is transmitted.
 */

/** Snapshot of the current state, e.g. immediately before a disconnect. */
export function computeStateVector(doc: Y.Doc): Uint8Array {
	return Y.encodeStateVector(doc);
}

/**
 * Computes exactly the changes made to the document since `sinceStateVector`
 * (e.g. during a connection loss). The result is a normal Yjs update and can
 * be transmitted like any other document update (prefix 0x00).
 */
export function computeOfflineDiff(doc: Y.Doc, sinceStateVector: Uint8Array): Uint8Array {
	return Y.encodeStateAsUpdate(doc, sinceStateVector);
}

/**
 * A Yjs update that contains no actual changes is nevertheless not 0 bytes
 * long (it carries an empty struct/delete-set header). This heuristic
 * reliably detects "empty" updates by applying them to a fresh doc and
 * checking whether its state vector changes.
 */
export function isEmptyUpdate(update: Uint8Array): boolean {
	const probe = new Y.Doc();
	const beforeSv = Y.encodeStateVector(probe);
	Y.applyUpdate(probe, update);
	const afterSv = Y.encodeStateVector(probe);
	return uint8ArraysEqual(beforeSv, afterSv);
}

function uint8ArraysEqual(a: Uint8Array, b: Uint8Array): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) return false;
	}
	return true;
}
