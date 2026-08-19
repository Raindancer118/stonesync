import { Plugin, TFile, type TAbstractFile } from "obsidian";
import { DEFAULT_SETTINGS, type StoneSyncSettings } from "./settings/StoneSyncSettings";
import { StoneSyncSettingTab } from "./settings/StoneSyncSettingTab";
import { SyncManager } from "./sync/SyncManager";
import { AttachmentSync } from "./attachments/AttachmentSync";
import { createSyncExtension } from "./editor/syncExtension";

const CURSOR_COLORS = [
	"#e57373",
	"#64b5f6",
	"#81c784",
	"#ffb74d",
	"#ba68c8",
	"#4db6ac",
	"#f06292",
	"#a1887f",
];

function pickUserColor(seed: string): string {
	let hash = 0;
	for (let i = 0; i < seed.length; i++) {
		hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
	}
	return CURSOR_COLORS[hash % CURSOR_COLORS.length];
}

export default class StoneSyncPlugin extends Plugin {
	settings: StoneSyncSettings = DEFAULT_SETTINGS;
	private syncManager: SyncManager | null = null;

	async onload(): Promise<void> {
		await this.loadSettings();
		if (!this.settings.displayName) {
			this.settings.displayName = `User-${Math.random().toString(36).slice(2, 6)}`;
			await this.saveData(this.settings);
		}

		const userName = this.settings.displayName;
		this.syncManager = new SyncManager(this.app, () => this.settings, userName, pickUserColor(userName));

		this.registerEditorExtension(createSyncExtension());

		this.addSettingTab(new StoneSyncSettingTab(this.app, this));

		this.addCommand({
			id: "stonesync-sync-now",
			name: "Sync now",
			callback: () => {
				void this.syncManager?.syncNow();
			},
		});

		this.registerEvent(
			this.app.workspace.on("active-leaf-change", () => {
				void this.syncManager?.onActiveLeafChange();
			})
		);

		this.registerEvent(
			this.app.vault.on("rename", (file: TAbstractFile, oldPath: string) => {
				if (file instanceof TFile) {
					this.syncManager?.handleRename(file, oldPath);
				}
			})
		);

		this.registerEvent(
			this.app.vault.on("delete", (file: TAbstractFile) => {
				this.syncManager?.handleDelete(file.path);
			})
		);

		// initial binding, in case a Markdown file is already open when the plugin loads
		this.app.workspace.onLayoutReady(() => {
			void this.syncManager?.onActiveLeafChange();
		});
	}

	onunload(): void {
		this.syncManager?.teardownAll();
	}

	async loadSettings(): Promise<void> {
		this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
	}

	async saveSettings(): Promise<void> {
		await this.saveData(this.settings);
		this.syncManager?.reconfigure();
	}

	/** Synchronizes a single attachment (e.g. callable from a context menu entry). */
	async syncAttachment(vaultRelativePath: string): Promise<void> {
		if (!this.settings.serverUrl || !this.settings.apiKey || !this.settings.vaultId) {
			throw new Error("StoneSync is not fully configured.");
		}
		const sync = new AttachmentSync({
			serverUrl: this.settings.serverUrl,
			apiKey: this.settings.apiKey,
			vaultId: this.settings.vaultId,
			adapter: this.app.vault.adapter,
		});
		await sync.syncFile(vaultRelativePath);
	}

}
