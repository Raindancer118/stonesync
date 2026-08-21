import { App, Notice, PluginSettingTab, Setting } from "obsidian";
import type StoneSyncPlugin from "../main";

export class StoneSyncSettingTab extends PluginSettingTab {
	constructor(app: App, private readonly plugin: StoneSyncPlugin) {
		super(app, plugin);
	}

	/** Reads the namespace from the server; silently leaves the field empty if we may not see it. */
	private async loadSlug(text: { setValue: (value: string) => unknown }): Promise<void> {
		try {
			const client = this.plugin.permissionsClientOrNull();
			if (!client) return;
			const { slug } = await client.slug();
			text.setValue(slug ?? "");
		} catch (error) {
			console.debug("[StoneSync] Could not read the vault namespace", error);
		}
	}

	private async saveSlug(value: string): Promise<void> {
		const client = this.plugin.permissionsClientOrNull();
		if (!client) return;
		try {
			await client.setSlug(value.trim() === "" ? null : value.trim());
			new Notice("StoneSync: Vault namespace saved.");
		} catch (error) {
			console.error("[StoneSync] Failed to save the vault namespace", error);
			new Notice("StoneSync: Could not save the namespace - only an owner may change it, and it must be lowercase letters, digits or dashes.");
		}
	}

	display(): void {
		const { containerEl } = this;
		containerEl.empty();

		containerEl.createEl("h2", { text: "StoneSync" });
		containerEl.createEl("p", {
			text:
				"Live multi-user sync for this vault. Connects to a StoneSync server " +
				"(Yjs CRDT relay + live cursor presence).",
		});

		new Setting(containerEl)
			.setName("Server URL")
			.setDesc("e.g. https://stonesync.example.com (https/wss is derived automatically).")
			.addText((text) =>
				text
					.setPlaceholder("https://stonesync.example.com")
					.setValue(this.plugin.settings.serverUrl)
					.onChange(async (value) => {
						this.plugin.settings.serverUrl = value.trim();
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("API key")
			.setDesc("Only sent as a bearer token for the ticket handshake (POST /api/auth/ticket).")
			.addText((text) => {
				text
					.setPlaceholder("sk-...")
					.setValue(this.plugin.settings.apiKey)
					.onChange(async (value) => {
						this.plugin.settings.apiKey = value.trim();
						await this.plugin.saveSettings();
					});
				text.inputEl.type = "password";
			});

		new Setting(containerEl)
			.setName("Vault ID")
			.setDesc("UUID of the vault on the server that this local Obsidian vault is synchronized with.")
			.addText((text) =>
				text
					.setPlaceholder("e.g. 3f6a2e9a-...")
					.setValue(this.plugin.settings.vaultId)
					.onChange(async (value) => {
						this.plugin.settings.vaultId = value.trim();
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("Display name")
			.setDesc("Shown to other users as your name in live cursor presence.")
			.addText((text) =>
				text
					.setPlaceholder("User-xxxx")
					.setValue(this.plugin.settings.displayName)
					.onChange(async (value) => {
						this.plugin.settings.displayName = value.trim();
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("Sync enabled")
			.setDesc("Globally turns live synchronization on/off.")
			.addToggle((toggle) =>
				toggle.setValue(this.plugin.settings.syncEnabled).onChange(async (value) => {
					this.plugin.settings.syncEnabled = value;
					await this.plugin.saveSettings();
				})
			);

		new Setting(containerEl)
			.setName("Open StoneSync home on startup")
			.setDesc(
				"Automatically opens the StoneSync home tab (branded search over this vault's " +
					"server-side index) once after Obsidian starts."
			)
			.addToggle((toggle) =>
				toggle.setValue(this.plugin.settings.openHomeOnStartup).onChange(async (value) => {
					this.plugin.settings.openHomeOnStartup = value;
					await this.plugin.saveSettings();
				})
			);

		new Setting(containerEl)
			.setName("Open StoneSync home when no file is open")
			.setDesc(
				"Also opens the StoneSync home tab whenever the workspace ends up with no note " +
					"open (e.g. after closing the last one) - not just once at startup."
			)
			.addToggle((toggle) =>
				toggle.setValue(this.plugin.settings.openHomeWhenNoFileOpen).onChange(async (value) => {
					this.plugin.settings.openHomeWhenNoFileOpen = value;
					await this.plugin.saveSettings();
				})
			);

		new Setting(containerEl)
			.setName("Vault link namespace")
			.setDesc(
				"Lets other vaults link into this one as [[namespace:Note]]. Lowercase letters, " +
					"digits and dashes. Only a vault owner can change it; leave empty to make this " +
					"vault unlinkable from outside."
			)
			.addText((text) => {
				text.setPlaceholder("e.g. sales");
				void this.loadSlug(text);
				text.onChange(() => undefined);
				text.inputEl.onblur = () => void this.saveSlug(text.getValue());
			});

		new Setting(containerEl)
			.setName("Shared notes folder")
			.setDesc(
				"Where notes from other vaults are placed when you follow a cross-vault link. " +
					"They become ordinary notes here, so they keep working offline."
			)
			.addText((text) =>
				text
					.setPlaceholder("_shared")
					.setValue(this.plugin.settings.mirrorFolder)
					.onChange(async (value) => {
						this.plugin.settings.mirrorFolder = value.trim() || "_shared";
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("Download entire vault")
			.setDesc(
				"Fetches every document that exists on the server but not yet locally " +
					"(never overwrites existing local files). Same as the " +
					'"StoneSync: Download entire vault from server" command.'
			)
			.addButton((button) =>
				button.setButtonText("Download now").onClick(() => {
					void this.plugin.downloadEntireVault();
				})
			);

		new Setting(containerEl)
			.setName("Upload entire vault")
			.setDesc(
				"Pushes every local file up to the server (never overwrites content the server " +
					"already has). Use this once to connect an existing vault - with content already " +
					'in it - to a freshly created, empty server vault. Same as the ' +
					'"StoneSync: Upload entire vault to server" command.'
			)
			.addButton((button) =>
				button.setButtonText("Upload now").onClick(() => {
					void this.plugin.uploadEntireVault();
				})
			);
	}
}
