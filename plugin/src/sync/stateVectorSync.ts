import * as Y from "yjs";

/**
 * Dünne, testbare Wrapper um Yjs' State-Vector-Diffing. Wird für den
 * Reconnect-Resync genutzt: statt nach einem Verbindungsabbruch den
 * kompletten Dokumentzustand neu zu senden, wird nur das Delta seit dem
 * letzten bekannten (Vor-Offline-)Zustand übertragen.
 */

/** Momentaufnahme des aktuellen Zustands, z.B. unmittelbar vor einem Disconnect. */
export function computeStateVector(doc: Y.Doc): Uint8Array {
	return Y.encodeStateVector(doc);
}

/**
 * Berechnet genau die Änderungen, die seit `sinceStateVector` am Dokument
 * vorgenommen wurden (z.B. während eines Verbindungsabbruchs). Das
 * Ergebnis ist ein normales Yjs-Update und kann wie jedes andere
 * Dokument-Update (Präfix 0x00) übertragen werden.
 */
export function computeOfflineDiff(doc: Y.Doc, sinceStateVector: Uint8Array): Uint8Array {
	return Y.encodeStateAsUpdate(doc, sinceStateVector);
}

/**
 * Ein Yjs-Update, das keine tatsächlichen Änderungen enthält, ist dennoch
 * nicht 0 Bytes lang (es trägt einen leeren Struct-/Delete-Set-Header).
 * Diese Heuristik erkennt "leere" Updates zuverlässig, indem sie sie in
 * ein frisches Doc appliziert und prüft, ob sich dessen State-Vector ändert.
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
