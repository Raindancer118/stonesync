import type * as Y from "yjs";

/**
 * Applies a queued link repair to a document's text.
 *
 * The server works out *what* should change but cannot perform the edit - it understands no Yjs.
 * So the replacement happens here, as an ordinary edit inside a Yjs transaction, which then
 * reaches every other client through the normal sync path. Character-precise on purpose: the rest
 * of the note, including anyone else's concurrent edits, stays untouched.
 */
export function applyLinkRewrite(ytext: Y.Text, oldLink: string, newLink: string): number {
	const content = ytext.toString();
	if (!content.includes(oldLink) || oldLink === newLink) {
		return 0;
	}

	let replacements = 0;
	ytext.doc?.transact(() => {
		// Re-read inside the transaction and walk backwards, so earlier indices stay valid while
		// later occurrences are replaced.
		const positions: number[] = [];
		let index = ytext.toString().indexOf(oldLink);
		while (index !== -1) {
			positions.push(index);
			index = ytext.toString().indexOf(oldLink, index + oldLink.length);
		}
		for (const position of positions.reverse()) {
			ytext.delete(position, oldLink.length);
			ytext.insert(position, newLink);
			replacements++;
		}
	});
	return replacements;
}
