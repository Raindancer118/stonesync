import type { DataAdapter } from "obsidian";

/**
 * Ensures every intermediate folder of a vault-relative path exists, creating any that are
 * missing (top-down). Obsidian's vault adapter refuses to write a file into a folder that
 * doesn't exist yet, which matters here because the bulk vault download creates files whose
 * parent folders may never have been created locally (a freshly onboarded, empty vault).
 *
 * Only a minimal structural subset of `DataAdapter` is required, so this stays trivially
 * testable with a fake in-memory adapter - no real Obsidian runtime needed.
 */
export async function ensureParentFolders(
	adapter: Pick<DataAdapter, "exists" | "mkdir">,
	vaultRelativePath: string
): Promise<void> {
	const segments = vaultRelativePath.split("/").filter((segment) => segment.length > 0);
	segments.pop(); // drop the file name itself - only folders remain

	let current = "";
	for (const segment of segments) {
		current = current ? `${current}/${segment}` : segment;
		if (!(await adapter.exists(current))) {
			await adapter.mkdir(current);
		}
	}
}
