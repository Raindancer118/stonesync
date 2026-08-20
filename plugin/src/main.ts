import { Menu, Notice, Plugin, TFile, type TAbstractFile } from "obsidian";
import { DEFAULT_SETTINGS, type StoneSyncSettings } from "./settings/StoneSyncSettings";
import { StoneSyncSettingTab } from "./settings/StoneSyncSettingTab";
import { SyncManager } from "./sync/SyncManager";
import type { ConnectionStatus } from "./net/StoneSyncSocket";
import { AttachmentSync } from "./attachments/AttachmentSync";
import { VaultDownloadService } from "./sync/VaultDownloadService";
import { VaultUploadService } from "./sync/VaultUploadService";
import { createSyncExtension } from "./editor/syncExtension";
import { parseConnectParams } from "./onboarding/DeepLinkHandler";
import { exchangeCode } from "./onboarding/ApiKeyExchangeClient";

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

/** `app.setting` is Obsidian's internal settings-panel API - stable in practice, but not part of the public typings. */
interface AppWithSettings {
	setting: {
		open(): void;
		openTabById(id: string): void;
	};
}

function describeStatus(status: ConnectionStatus | null): { label: string; cssClass: string } {
	switch (status) {
		case null:
		case "idle":
			return { label: "idle", cssClass: "idle" };
		case "connecting":
			return { label: "connecting…", cssClass: "connecting" };
		case "connected":
			return { label: "connected", cssClass: "connected" };
		case "reconnecting":
			return { label: "reconnecting…", cssClass: "connecting" };
		case "closed":
			return { label: "disconnected", cssClass: "disconnected" };
		case "unauthorized":
			return { label: "unauthorized", cssClass: "error" };
	}
}

export default class StoneSyncPlugin extends Plugin {
	settings: StoneSyncSettings = DEFAULT_SETTINGS;
	private syncManager: SyncManager | null = null;
	private statusBarItemEl: HTMLElement | null = null;

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

		// Status bar indicator for the currently-active file's live sync connection, and a
		// one-click menu for the commands that would otherwise only be reachable via the
		// command palette (Sync now, bulk download/upload).
		this.statusBarItemEl = this.addStatusBarItem();
		this.statusBarItemEl.addClass("stonesync-status-bar-item");
		this.updateStatusBar(null);
		this.statusBarItemEl.onClickEvent((evt) => this.showActionsMenu(evt));
		this.syncManager.setStatusListener((status) => this.updateStatusBar(status));

		this.addRibbonIcon("refresh-cw", "StoneSync actions", (evt) => this.showActionsMenu(evt));

		// Entry point for the collaborator-invite flow (see AuthentikLoginSuccessHandler /
		// DeepLinkBuilder on the server): after a colleague logs in via Authentik, the browser
		// opens obsidian://stonesync-connect?serverUrl=...&apiKey=...&vaultId=...&displayName=...,
		// which Obsidian routes here without any manual settings entry needed.
		this.registerObsidianProtocolHandler("stonesync-connect", (params) => {
			void this.handleConnectDeepLink(params as unknown as Record<string, string>);
		});

		this.addCommand({
			id: "stonesync-sync-now",
			name: "Sync now",
			callback: () => {
				void this.syncManager?.syncNow();
			},
		});

		this.addCommand({
			id: "stonesync-download-vault",
			name: "Download entire vault from server",
			callback: () => {
				void this.downloadEntireVault();
			},
		});

		this.addCommand({
			id: "stonesync-upload-vault",
			name: "Upload entire vault to server",
			callback: () => {
				void this.uploadEntireVault();
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
				void this.syncManager?.handleDelete(file.path);
			})
		);

		// initial binding, in case a Markdown file is already open when the plugin loads
		this.app.workspace.onLayoutReady(() => {
			void this.syncManager?.onActiveLeafChange();
		});
	}

	onunload(): void {
		this.syncManager?.setStatusListener(null);
		this.syncManager?.teardownAll();
	}

	private updateStatusBar(status: ConnectionStatus | null): void {
		if (!this.statusBarItemEl) return;
		const { label, cssClass } = describeStatus(status);
		this.statusBarItemEl.empty();
		this.statusBarItemEl.createSpan({ cls: `stonesync-status-dot stonesync-status-dot--${cssClass}` });
		this.statusBarItemEl.createSpan({ text: `StoneSync: ${label}`, cls: "stonesync-status-text" });
	}

	private showActionsMenu(evt: MouseEvent): void {
		const menu = new Menu();
		menu.addItem((item) =>
			item
				.setTitle("Sync now")
				.setIcon("refresh-cw")
				.onClick(() => void this.syncManager?.syncNow())
		);
		menu.addItem((item) =>
			item
				.setTitle("Download entire vault from server")
				.setIcon("download")
				.onClick(() => void this.downloadEntireVault())
		);
		menu.addItem((item) =>
			item
				.setTitle("Upload entire vault to server")
				.setIcon("upload")
				.onClick(() => void this.uploadEntireVault())
		);
		menu.addSeparator();
		menu.addItem((item) =>
			item
				.setTitle("Open StoneSync settings")
				.setIcon("settings")
				.onClick(() => {
					const appWithSettings = this.app as unknown as AppWithSettings;
					appWithSettings.setting.open();
					appWithSettings.setting.openTabById(this.manifest.id);
				})
		);
		menu.showAtMouseEvent(evt);
	}

	async loadSettings(): Promise<void> {
		this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
	}

	async saveSettings(): Promise<void> {
		await this.saveData(this.settings);
		this.syncManager?.reconfigure();
	}

	/**
	 * Applies the settings carried by a `stonesync-connect` deep link, then re-triggers sync
	 * binding for whatever file is currently active so live sync starts immediately for it, and
	 * kicks off a full bulk vault download - this is what actually satisfies "colleague clicks
	 * the invite link and their plugin auto-configures and downloads the whole vault", with no
	 * further manual step (see `VaultDownloadService`).
	 */
	private async handleConnectDeepLink(params: Record<string, string>): Promise<void> {
		const parsed = parseConnectParams(params);
		if (!parsed) {
			new Notice("StoneSync: Received an incomplete connect link - please request a new invite.");
			return;
		}

		let exchanged;
		try {
			exchanged = await exchangeCode(parsed.serverUrl, parsed.exchangeCode);
		} catch (error) {
			console.error("[StoneSync] Failed to exchange connect code", error);
			new Notice("StoneSync: This connect link has expired or was already used - please request a new invite.");
			return;
		}

		this.settings.serverUrl = parsed.serverUrl;
		this.settings.apiKey = exchanged.apiKey;
		this.settings.vaultId = exchanged.vaultId;
		this.settings.displayName = exchanged.displayName;
		await this.saveSettings();

		new Notice(`StoneSync: Connected as ${exchanged.displayName}. Sync is now active.`);
		await this.syncManager?.onActiveLeafChange();
		await this.downloadEntireVault();
	}

	/** Manual trigger for the "StoneSync: Download entire vault from server" command. */
	async downloadEntireVault(): Promise<void> {
		if (!this.settings.serverUrl || !this.settings.apiKey || !this.settings.vaultId) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return;
		}
		const service = new VaultDownloadService({
			app: this.app,
			settings: this.settings,
			userName: this.settings.displayName,
			userColor: pickUserColor(this.settings.displayName),
		});
		await service.downloadEntireVault();
	}

	/**
	 * Manual trigger for the "StoneSync: Upload entire vault to server" command - the mirror of
	 * `downloadEntireVault`, for connecting an existing local vault (with content already in it)
	 * to a freshly created, still-empty server vault. Never overwrites content already present
	 * on the server.
	 */
	async uploadEntireVault(): Promise<void> {
		if (!this.settings.serverUrl || !this.settings.apiKey || !this.settings.vaultId) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return;
		}
		const service = new VaultUploadService({
			app: this.app,
			settings: this.settings,
			userName: this.settings.displayName,
			userColor: pickUserColor(this.settings.displayName),
		});
		await service.uploadEntireVault();
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
