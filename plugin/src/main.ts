import { Menu, Notice, Plugin, TFile, type TAbstractFile } from "obsidian";
import { DEFAULT_SETTINGS, type StoneSyncSettings } from "./settings/StoneSyncSettings";
import { StoneSyncSettingTab } from "./settings/StoneSyncSettingTab";
import { SyncManager, type Peer } from "./sync/SyncManager";
import type { ConnectionStatus } from "./net/StoneSyncSocket";
import { AttachmentSync } from "./attachments/AttachmentSync";
import { VaultDownloadService } from "./sync/VaultDownloadService";
import { VaultUploadService } from "./sync/VaultUploadService";
import { VaultEventsManager } from "./sync/VaultEventsManager";
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
	private vaultEventsManager: VaultEventsManager | null = null;
	private statusBarItemEl: HTMLElement | null = null;
	private lastStatus: ConnectionStatus | null = null;
	private lastPeers: Peer[] = [];

	async onload(): Promise<void> {
		await this.loadSettings();
		if (!this.settings.displayName) {
			this.settings.displayName = `User-${Math.random().toString(36).slice(2, 6)}`;
			await this.saveData(this.settings);
		}

		const userName = this.settings.displayName;
		this.syncManager = new SyncManager(this.app, () => this.settings, userName, pickUserColor(userName));
		this.vaultEventsManager = new VaultEventsManager(this.app, () => this.settings, userName, pickUserColor(userName));
		this.vaultEventsManager.start();

		this.registerEditorExtension(createSyncExtension());
		// Editors that already exist when the plugin loads (Obsidian restores the previous
		// layout before onload finishes) only pick up a newly registered extension after an
		// explicit options refresh - without this, the very first bind of an already-open note
		// would silently have no compartment to reconfigure.
		this.app.workspace.updateOptions();

		this.addSettingTab(new StoneSyncSettingTab(this.app, this));

		// Status bar indicator for the currently-active file's live sync connection, and a
		// one-click menu for the commands that would otherwise only be reachable via the
		// command palette (Sync now, bulk download/upload).
		this.statusBarItemEl = this.addStatusBarItem();
		this.statusBarItemEl.addClass("stonesync-status-bar-item");
		this.updateStatusBar(null);
		this.statusBarItemEl.onClickEvent((evt) => this.showActionsMenu(evt));
		this.syncManager.setStatusListener((status) => this.updateStatusBar(status));
		this.syncManager.setPresenceListener((peers) => this.updatePresence(peers));

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

		// Panes being opened, closed or split change *which* notes are live-synced (every open
		// Markdown editor gets its own session), so the binding has to be re-evaluated here too -
		// not only when the focus moves.
		this.registerEvent(
			this.app.workspace.on("layout-change", () => {
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
		this.syncManager?.setPresenceListener(null);
		this.syncManager?.teardownAll();
		this.vaultEventsManager?.stop();
	}

	private updateStatusBar(status: ConnectionStatus | null): void {
		this.lastStatus = status;
		if (status === null) this.lastPeers = [];
		const { label, cssClass } = describeStatus(status);
		this.renderStatusBar(label, cssClass);
	}

	/** Live cursor presence: who else has this note open right now. */
	private updatePresence(peers: Peer[]): void {
		this.lastPeers = peers;
		const { label, cssClass } = describeStatus(this.lastStatus);
		this.renderStatusBar(label, cssClass);
	}

	/** Used while a bulk download/upload runs, since that has no `ConnectionStatus` of its own. */
	private setStatusBarBusy(label: string): void {
		this.renderStatusBar(label, "connecting");
	}

	private renderStatusBar(label: string, cssClass: string): void {
		if (!this.statusBarItemEl) return;
		this.statusBarItemEl.empty();
		this.statusBarItemEl.createSpan({ cls: `stonesync-status-dot stonesync-status-dot--${cssClass}` });
		this.statusBarItemEl.createSpan({ text: `StoneSync: ${label}`, cls: "stonesync-status-text" });

		if (this.lastPeers.length === 0) {
			this.statusBarItemEl.setAttr("aria-label", "StoneSync - nobody else is in this note");
			return;
		}
		const presenceEl = this.statusBarItemEl.createSpan({ cls: "stonesync-presence" });
		for (const peer of this.lastPeers.slice(0, 5)) {
			const dot = presenceEl.createSpan({ cls: "stonesync-presence-dot" });
			dot.style.backgroundColor = peer.color;
			dot.setAttr("aria-label", peer.name);
		}
		presenceEl.createSpan({
			text: this.lastPeers.length === 1 ? "1 editing" : `${this.lastPeers.length} editing`,
			cls: "stonesync-presence-text",
		});
		this.statusBarItemEl.setAttr("aria-label", `StoneSync - also here: ${this.lastPeers.map((p) => p.name).join(", ")}`);
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
		this.vaultEventsManager?.start();
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
			onProgress: (processed, total) => this.setStatusBarBusy(`downloading ${processed}/${total}`),
		});
		try {
			await service.downloadEntireVault();
		} finally {
			void this.syncManager?.onActiveLeafChange();
		}
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
			onProgress: (processed, total) => this.setStatusBarBusy(`uploading ${processed}/${total}`),
		});
		try {
			await service.uploadEntireVault();
		} finally {
			void this.syncManager?.onActiveLeafChange();
		}
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
