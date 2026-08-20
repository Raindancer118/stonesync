import { Notice, TFile, type App } from "obsidian";
import { DocumentSession } from "../sync/DocumentSession";
import { ensureParentFolders } from "../sync/ensureParentFolders";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import type { CrossVaultLink } from "./crossVaultLinks";
import { mirrorPathFor } from "./crossVaultLinks";
import { LinkClient, type ResolvedLink } from "./LinkClient";
import type { MirrorRegistry } from "./MirrorRegistry";

export interface CrossVaultLinkOpenerOptions {
	app: App;
	getSettings: () => StoneSyncSettings;
	mirrors: MirrorRegistry;
	userName: string;
	userColor: string;
	/** Called after a mirror was created, so live sync can pick the new note up. */
	onMirrorCreated: () => Promise<void>;
}

/**
 * Follows a `[[slug:Note]]` link into another vault.
 *
 * The note is mirrored into this vault as an ordinary file and then opened the ordinary way. That
 * is deliberate: from the moment it is here, Obsidian treats it like any other note - links,
 * backlinks, search, and reading it offline all work with no server involved. Live sync binds it
 * to its *own* document on the server via {@link MirrorRegistry}, so editing it (with write
 * access) edits the real note in the other vault rather than a copy.
 */
export class CrossVaultLinkOpener {
	constructor(private readonly options: CrossVaultLinkOpenerOptions) {}

	async open(link: CrossVaultLink): Promise<void> {
		const settings = this.options.getSettings();
		const localPath = mirrorPathFor(settings.mirrorFolder, link.vaultSlug, link.targetPath);

		// Already mirrored: just open it. No server needed - this is what makes a followed link
		// keep working offline afterwards.
		const existing = this.options.app.vault.getAbstractFileByPath(localPath);
		if (existing instanceof TFile) {
			await this.openFile(existing);
			return;
		}

		let resolved: ResolvedLink;
		try {
			resolved = await new LinkClient(settings.serverUrl, settings.apiKey).resolve(link.vaultSlug, link.targetPath);
		} catch (error) {
			console.error("[StoneSync] Failed to resolve cross-vault link", link.raw, error);
			new Notice("StoneSync: Could not reach the server to follow this link.");
			return;
		}

		if (resolved.status === "RESTRICTED") {
			new Notice(`StoneSync: You don't have access to "${link.vaultSlug}:${link.targetPath}".`);
			return;
		}
		if (resolved.status === "NOT_FOUND" || !resolved.documentId) {
			new Notice(`StoneSync: "${link.vaultSlug}:${link.targetPath}" doesn't exist (any more).`);
			return;
		}

		const content = await this.fetchContent(resolved.documentId);
		if (content === null) return;

		await ensureParentFolders(this.options.app.vault.adapter, localPath);
		await this.options.app.vault.adapter.write(localPath, content);
		await this.options.mirrors.register(localPath, {
			documentId: resolved.documentId,
			vaultSlug: link.vaultSlug,
			sourcePath: resolved.path ?? link.targetPath,
			writable: resolved.writable,
		});
		await this.options.onMirrorCreated();

		const file = this.options.app.vault.getAbstractFileByPath(localPath);
		if (file instanceof TFile) {
			await this.openFile(file);
			new Notice(
				resolved.writable
					? `StoneSync: Opened "${link.targetPath}" from ${link.vaultSlug} - your edits sync back.`
					: `StoneSync: Opened "${link.targetPath}" from ${link.vaultSlug} (read-only).`
			);
		}
	}

	/** Pulls the note's current content over the normal sync channel - no extra server API needed. */
	private async fetchContent(documentId: string): Promise<string | null> {
		const settings = this.options.getSettings();
		const session = new DocumentSession({
			documentId,
			serverUrl: settings.serverUrl,
			apiKey: settings.apiKey,
			userName: this.options.userName,
			userColor: this.options.userColor,
			readOnly: true,
			onError: (error) => console.error("[StoneSync]", error),
		});
		try {
			const caughtUp = await session.connectAndWaitUntilCaughtUp(10000);
			if (!caughtUp) {
				new Notice("StoneSync: Timed out fetching that note from the server.");
				return null;
			}
			return session.ytext.toString();
		} finally {
			session.destroy();
		}
	}

	private async openFile(file: TFile): Promise<void> {
		await this.options.app.workspace.getLeaf(false).openFile(file);
	}
}
