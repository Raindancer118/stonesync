import type { MirroredNote, StoneSyncSettings } from "../settings/StoneSyncSettings";

/**
 * Remembers which local notes are actually mirrors of notes in *other* vaults.
 *
 * A mirrored note is a perfectly ordinary file in this vault - that is the point: Obsidian links
 * to it, searches it, and shows it offline like any other note. But when it comes to syncing, it
 * must be bound to its *own* document on the server, not to a new document in this vault, which
 * is what a path-based lookup would produce.
 */
export class MirrorRegistry {
	constructor(
		private readonly getSettings: () => StoneSyncSettings,
		private readonly persist: () => Promise<void>
	) {}

	get(localPath: string): MirroredNote | undefined {
		return this.getSettings().mirrors[localPath];
	}

	isMirror(localPath: string): boolean {
		return this.get(localPath) !== undefined;
	}

	/** True for anything inside the mirror folder, even if not registered (e.g. leftovers). */
	isInMirrorFolder(localPath: string): boolean {
		const folder = this.getSettings().mirrorFolder.replace(/^\/+|\/+$/g, "");
		return folder.length > 0 && (localPath === folder || localPath.startsWith(folder + "/"));
	}

	async register(localPath: string, note: MirroredNote): Promise<void> {
		this.getSettings().mirrors[localPath] = note;
		await this.persist();
	}

	async forget(localPath: string): Promise<void> {
		delete this.getSettings().mirrors[localPath];
		await this.persist();
	}

	/** Keeps the mapping correct when the user moves a mirrored note around locally. */
	async rename(oldPath: string, newPath: string): Promise<void> {
		const existing = this.get(oldPath);
		if (!existing) return;
		delete this.getSettings().mirrors[oldPath];
		this.getSettings().mirrors[newPath] = existing;
		await this.persist();
	}
}
