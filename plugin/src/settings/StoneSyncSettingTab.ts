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
				"Live-Multi-User-Sync für dieses Vault. Verbindet sich mit einem StoneSync-Server " +
				"(Yjs-CRDT-Relay + Live-Cursor-Presence).",
		});

		new Setting(containerEl)
			.setName("Server-URL")
			.setDesc("z.B. https://stonesync.example.com (https/wss wird automatisch abgeleitet).")
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
			.setName("API-Key")
			.setDesc("Wird nur für den Ticket-Handshake (POST /api/auth/ticket) als Bearer-Token gesendet.")
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
			.setName("Vault-ID")
			.setDesc("UUID des Vaults auf dem Server, mit dem dieses lokale Obsidian-Vault synchronisiert wird.")
			.addText((text) =>
				text
					.setPlaceholder("z.B. 3f6a2e9a-...")
					.setValue(this.plugin.settings.vaultId)
					.onChange(async (value) => {
						this.plugin.settings.vaultId = value.trim();
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("Anzeigename")
			.setDesc("Wird anderen Nutzern bei Live-Cursor-Presence als dein Name angezeigt.")
			.addText((text) =>
				text
					.setPlaceholder("Nutzer-xxxx")
					.setValue(this.plugin.settings.displayName)
					.onChange(async (value) => {
						this.plugin.settings.displayName = value.trim();
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("Sync aktiv")
			.setDesc("Schaltet die Live-Synchronisation global ein/aus.")
			.addToggle((toggle) =>
				toggle.setValue(this.plugin.settings.syncEnabled).onChange(async (value) => {
					this.plugin.settings.syncEnabled = value;
					await this.plugin.saveSettings();
				})
			);
	}
}
