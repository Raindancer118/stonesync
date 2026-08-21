import { Menu, Notice, Plugin, TFile, TFolder, type TAbstractFile } from "obsidian";
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
import { PermissionsClient } from "./access/PermissionsClient";
import { canManage, type AccessLevel, type VaultPermissions } from "./access/permissions";
import { MembersModal } from "./ui/MembersModal";
import { HistoryModal } from "./ui/HistoryModal";
import { PathAccessModal } from "./ui/PathAccessModal";
import { DocumentIdResolver } from "./sync/DocumentIdResolver";
import { MirrorRegistry } from "./links/MirrorRegistry";
import { CrossVaultLinkOpener } from "./links/CrossVaultLinkOpener";
import { CrossVaultLinkRenderer } from "./links/CrossVaultLinkRenderer";
import { findCrossVaultLinks, parseCrossVaultLink } from "./links/crossVaultLinks";
import { exchangeCode } from "./onboarding/ApiKeyExchangeClient";
import { pickUserColor } from "./settings/userColor";

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
	private lastLevel: AccessLevel | null = null;
	private lastPermissions: VaultPermissions | null = null;
	private mirrors: MirrorRegistry | null = null;
	private linkOpener: CrossVaultLinkOpener | null = null;

	async onload(): Promise<void> {
		await this.loadSettings();
		if (!this.settings.displayName) {
			this.settings.displayName = `User-${Math.random().toString(36).slice(2, 6)}`;
			await this.saveData(this.settings);
		}

		// getSettings is a live accessor, not a captured value - SyncManager/VaultEventsManager/
		// CrossVaultLinkOpener read the display name through it every time they need it, so a
		// later change in Settings takes effect immediately instead of only after Obsidian is
		// restarted (previously these three took userName/userColor as plain strings captured
		// once here at onload() time, so a later name change never reached an already-running
		// session).
		this.mirrors = new MirrorRegistry(() => this.settings, () => this.saveData(this.settings));
		this.syncManager = new SyncManager(this.app, () => this.settings, this.mirrors);
		this.linkOpener = new CrossVaultLinkOpener({
			app: this.app,
			getSettings: () => this.settings,
			mirrors: this.mirrors,
			onMirrorCreated: async () => {
				await this.syncManager?.onActiveLeafChange();
			},
		});
		this.vaultEventsManager = new VaultEventsManager(this.app, () => this.settings,
			() => this.syncManager?.applyPermissionChange() ?? Promise.resolve(),
			(documentId) => this.syncManager?.applyLinkRewriteEvent(documentId) ?? Promise.resolve());
		this.vaultEventsManager.start();

		// Cross-vault links: Obsidian renders [[slug:Note]] as an unresolved link because the
		// target is not in this vault. Only those are replaced with a clickable element - every
		// ordinary link stays exactly as Obsidian rendered it, and keeps working with no server.
		const renderer = new CrossVaultLinkRenderer(this.app, (link) => void this.linkOpener?.open(link));
		this.registerMarkdownPostProcessor(renderer.process);

		// In the editor, Obsidian handles clicks itself, so the link is intercepted at the source.
		this.registerDomEvent(document, "click", (event) => this.interceptCrossVaultClick(event), { capture: true });

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
		this.syncManager.setPermissionsListener((permissions, level) => this.updatePermissions(permissions, level));

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
			id: "stonesync-open-cross-vault-link",
			name: "Follow the cross-vault link under the cursor",
			editorCallback: (editor) => {
				const line = editor.getLine(editor.getCursor().line);
				const [link] = findCrossVaultLinks(line);
				if (!link) {
					new Notice("StoneSync: No cross-vault link on this line.");
					return;
				}
				void this.linkOpener?.open(link);
			},
		});

		this.addCommand({
			id: "stonesync-manage-collaborators",
			name: "Manage collaborators and folder rules",
			callback: () => this.openMembersModal(),
		});

		this.addCommand({
			id: "stonesync-note-history",
			name: "Show who changed this note",
			callback: () => void this.openHistoryModal(),
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

		// Right-click on a note or folder (file explorer, tab header, search results): manage who
		// may see and change exactly that path, plus its history.
		this.registerEvent(
			this.app.workspace.on("file-menu", (menu, file) => this.addAccessMenuItems(menu, file))
		);
		this.registerEvent(
			this.app.workspace.on("editor-menu", (menu, _editor, view) => {
				if (view.file) this.addAccessMenuItems(menu, view.file);
			})
		);

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
				// Deleting a mirror only removes the local copy - the note itself lives in another
				// vault and must not be tombstoned there.
				if (this.mirrors?.isMirror(file.path)) {
					void this.mirrors.forget(file.path);
					return;
				}
				void this.syncManager?.handleDelete(file.path);
			})
		);

		// initial binding, in case a Markdown file is already open when the plugin loads
		this.app.workspace.onLayoutReady(() => {
			void this.syncManager?.refreshPermissions().then(() => this.syncManager?.onActiveLeafChange());
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

	/** The caller's own role, so "why can't I type here" is answerable at a glance. */
	/**
	 * Catches a click on a namespaced link before Obsidian tries (and fails) to resolve it in this
	 * vault. Anything that is not a cross-vault link is left to Obsidian untouched.
	 */
	private interceptCrossVaultClick(event: MouseEvent): void {
		const target = event.target;
		if (!(target instanceof HTMLElement)) return;
		const anchor = target.closest<HTMLElement>("a.internal-link, .cm-hmd-internal-link");
		if (!anchor) return;

		const href = anchor.getAttribute("data-href") ?? anchor.getAttribute("href") ?? anchor.textContent ?? "";
		const link = parseCrossVaultLink(href.trim());
		if (!link) return;

		event.preventDefault();
		event.stopPropagation();
		void this.linkOpener?.open(link);
	}

	private updatePermissions(permissions: VaultPermissions, level: AccessLevel | null): void {
		this.lastPermissions = permissions;
		this.lastLevel = level;
		const { label, cssClass } = describeStatus(this.lastStatus);
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

		if (this.lastLevel && this.lastLevel !== "EDITOR" && this.lastLevel !== "OWNER") {
			this.statusBarItemEl.createSpan({
				text: this.lastLevel === "NONE" ? "· no access" : "· read-only",
				cls: "stonesync-status-role",
			});
		}

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
				.setTitle("Show who changed this note")
				.setIcon("history")
				.onClick(() => void this.openHistoryModal())
		);
		if (!this.lastPermissions || canManage(this.lastPermissions)) {
			menu.addItem((item) =>
				item
					.setTitle("Manage collaborators and folder rules")
					.setIcon("users")
					.onClick(() => this.openMembersModal())
			);
		}
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

	/**
	 * Menu entries for one specific note or folder. Managing access is only offered to someone who
	 * may actually do it - the server enforces it regardless, but offering a dialog that can only
	 * fail is worse than not offering it.
	 */
	private addAccessMenuItems(menu: Menu, file: TAbstractFile): void {
		const isFolder = file instanceof TFolder;
		if (!isFolder && !(file instanceof TFile)) return;
		if (this.mirrors?.isInMirrorFolder(file.path)) return; // belongs to another vault

		if (!this.lastPermissions || canManage(this.lastPermissions)) {
			menu.addItem((item) =>
				item
					.setTitle("StoneSync: Manage access")
					.setIcon("lock")
					.onClick(() => {
						const client = this.permissionsClientOrNull();
						if (!client) {
							new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
							return;
						}
						new PathAccessModal(this.app, client, file.path, isFolder).open();
					})
			);
		}

		if (file instanceof TFile && file.extension === "md") {
			menu.addItem((item) =>
				item
					.setTitle("StoneSync: Show who changed this note")
					.setIcon("history")
					.onClick(() => void this.openHistoryModal(file))
			);
		}
	}

	/** Same as `permissionsClient()`, but quiet - for background/UI lookups that may simply skip. */
	permissionsClientOrNull(): PermissionsClient | null {
		if (!this.settings.serverUrl || !this.settings.apiKey || !this.settings.vaultId) return null;
		return new PermissionsClient(this.settings.serverUrl, this.settings.apiKey, this.settings.vaultId);
	}

	private permissionsClient(): PermissionsClient | null {
		if (!this.settings.serverUrl || !this.settings.apiKey || !this.settings.vaultId) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return null;
		}
		return new PermissionsClient(this.settings.serverUrl, this.settings.apiKey, this.settings.vaultId);
	}

	private openMembersModal(): void {
		const client = this.permissionsClient();
		if (client) new MembersModal(this.app, client).open();
	}

	/**
	 * Resolves the active note's server-side id first: history is keyed by document, and the
	 * plugin only ever knows the path until it asks.
	 */
	private async openHistoryModal(target?: TFile): Promise<void> {
		const client = this.permissionsClient();
		const file = target ?? this.app.workspace.getActiveFile();
		if (!client || !file) {
			if (client) new Notice("StoneSync: Open a note first.");
			return;
		}
		try {
			// A mirrored foreign note has its own document elsewhere - resolving by path would
			// look it up in the wrong vault.
			const mirror = this.mirrors?.get(file.path);
			const resolver = new DocumentIdResolver(this.settings.serverUrl, this.settings.apiKey, this.settings.vaultId);
			const documentId = mirror ? mirror.documentId : await resolver.resolve(file.path);
			new HistoryModal(this.app, client, documentId, file.path).open();
		} catch (error) {
			console.error("[StoneSync] Failed to resolve document for history", error);
			new Notice("StoneSync: Could not load this note's history.");
		}
	}

	async loadSettings(): Promise<void> {
		this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
	}

	async saveSettings(): Promise<void> {
		await this.saveData(this.settings);
		this.syncManager?.reconfigure();
		void this.syncManager?.refreshPermissions();
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
			isMirroredPath: (path) => this.mirrors?.isInMirrorFolder(path) ?? false,
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
