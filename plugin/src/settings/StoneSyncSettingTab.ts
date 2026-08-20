import { App, PluginSettingTab, Setting } from "obsidian";
import type StoneSyncPlugin from "../main";

export class StoneSyncSettingTab extends PluginSettingTab {
	constructor(app: App, private readonly plugin: StoneSyncPlugin) {
		super(app, plugin);
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
	}
}
