import { App, Modal, Notice } from "obsidian";
import type { HistoryEntry, PermissionsClient } from "../access/PermissionsClient";

/**
 * "Who changed this note, when - and what exactly." The list comes from the vault's git history
 * (one entry per actually-changed materialize), the diff is fetched on demand so opening the
 * dialog stays cheap for a note with a long history.
 */
export class HistoryModal extends Modal {
	private entries: HistoryEntry[] = [];

	constructor(
		app: App,
		private readonly client: PermissionsClient,
		private readonly documentId: string,
		private readonly path: string
	) {
		super(app);
	}

	async onOpen(): Promise<void> {
		this.modalEl.addClass("stonesync-history-modal");
		const { contentEl } = this;
		contentEl.createEl("h2", { text: `History of ${this.path}` });
		const status = contentEl.createEl("p", { text: "Loading…" });

		try {
			this.entries = await this.client.history(this.documentId);
		} catch (error) {
			console.error("[StoneSync] Failed to load history", error);
			status.setText("Could not load this note's history from the server.");
			return;
		}

		status.remove();
		if (this.entries.length === 0) {
			contentEl.createEl("p", { text: "No changes recorded yet." });
			return;
		}

		const list = contentEl.createEl("div", { cls: "stonesync-history-list" });
		for (const entry of this.entries) {
			const row = list.createEl("div", { cls: "stonesync-history-entry" });
			row.createEl("div", { text: new Date(entry.changedAt).toLocaleString(), cls: "stonesync-history-when" });
			row.createEl("div", { text: entry.authorEmail, cls: "stonesync-history-who" });
			const diffEl = row.createEl("pre", { cls: "stonesync-history-diff" });
			diffEl.hide();

			const toggle = row.createEl("button", { text: "Show changes" });
			toggle.onclick = async () => {
				if (!diffEl.isShown()) {
					if (!diffEl.textContent) {
						try {
							diffEl.setText(await this.client.diff(this.documentId, entry.commitId));
						} catch (error) {
							console.error("[StoneSync] Failed to load diff", error);
							new Notice("StoneSync: Could not load that change.");
							return;
						}
					}
					diffEl.show();
					toggle.setText("Hide changes");
				} else {
					diffEl.hide();
					toggle.setText("Show changes");
				}
			};
		}
	}

	onClose(): void {
		this.contentEl.empty();
	}
}
