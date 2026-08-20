import { App, Modal, Notice, Setting } from "obsidian";
import type { HistoryEntry, PermissionsClient } from "../access/PermissionsClient";
import { authorsIn, displayAuthor, filterHistory, EMPTY_FILTER, type HistoryFilter } from "./historyFilter";

/**
 * "Who changed this note, when - and what exactly." The list comes from the vault's git history
 * (one entry per actually-changed materialize); each diff is fetched on demand, so opening a note
 * with a long history stays cheap.
 *
 * Filtering happens on the already-fetched list rather than on the server: the history of a single
 * note is small enough that a round trip per keystroke would be the slower option, and it keeps
 * the diffs already loaded in place while you narrow things down.
 */
export class HistoryModal extends Modal {
	private entries: HistoryEntry[] = [];
	private filter: HistoryFilter = { ...EMPTY_FILTER };
	private listEl: HTMLElement | null = null;
	private countEl: HTMLElement | null = null;
	private readonly diffCache = new Map<string, string>();

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
			this.entries = await this.client.history(this.documentId, 200);
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

		this.renderControls(contentEl);
		this.countEl = contentEl.createEl("p", { cls: "setting-item-description" });
		this.listEl = contentEl.createEl("div", { cls: "stonesync-history-list" });
		this.renderList();
	}

	private renderControls(container: HTMLElement): void {
		new Setting(container)
			.setName("Search")
			.setDesc("Matches the person and what the change was recorded as.")
			.addText((text) =>
				text.setPlaceholder("e.g. a name or a file name").onChange((value) => {
					this.filter.query = value;
					this.renderList();
				})
			);

		new Setting(container).setName("Person").addDropdown((dropdown) => {
			dropdown.addOption("", "anyone");
			authorsIn(this.entries).forEach((author) => dropdown.addOption(author, author));
			dropdown.onChange((value) => {
				this.filter.author = value;
				this.renderList();
			});
		});

		new Setting(container)
			.setName("Between")
			.setDesc("Both days are included. Leave a field empty for no limit.")
			.addText((text) => {
				text.inputEl.type = "date";
				text.onChange((value) => {
					this.filter.from = value;
					this.renderList();
				});
			})
			.addText((text) => {
				text.inputEl.type = "date";
				text.onChange((value) => {
					this.filter.to = value;
					this.renderList();
				});
			})
			.addExtraButton((button) =>
				button
					.setIcon("reset")
					.setTooltip("Clear all filters")
					.onClick(() => {
						this.filter = { ...EMPTY_FILTER };
						this.close();
						new HistoryModal(this.app, this.client, this.documentId, this.path).open();
					})
			);
	}

	private renderList(): void {
		if (!this.listEl) return;
		const matching = filterHistory(this.entries, this.filter);
		this.countEl?.setText(
			matching.length === this.entries.length
				? `${this.entries.length} change${this.entries.length === 1 ? "" : "s"}`
				: `${matching.length} of ${this.entries.length} changes`
		);

		this.listEl.empty();
		if (matching.length === 0) {
			this.listEl.createEl("p", { text: "No change matches these filters." });
			return;
		}
		for (const entry of matching) {
			this.renderEntry(this.listEl, entry);
		}
	}

	private renderEntry(list: HTMLElement, entry: HistoryEntry): void {
		const row = list.createEl("div", { cls: "stonesync-history-entry" });
		row.createEl("div", { text: new Date(entry.changedAt).toLocaleString(), cls: "stonesync-history-when" });
		row.createEl("div", { text: displayAuthor(entry), cls: "stonesync-history-who" });

		const diffEl = row.createEl("pre", { cls: "stonesync-history-diff" });
		diffEl.hide();
		const cached = this.diffCache.get(entry.commitId);
		if (cached) diffEl.setText(cached);

		const toggle = row.createEl("button", { text: "Show changes" });
		toggle.onclick = async () => {
			if (diffEl.isShown()) {
				diffEl.hide();
				toggle.setText("Show changes");
				return;
			}
			if (!this.diffCache.has(entry.commitId)) {
				try {
					const diff = await this.client.diff(this.documentId, entry.commitId);
					this.diffCache.set(entry.commitId, diff);
					diffEl.setText(diff);
				} catch (error) {
					console.error("[StoneSync] Failed to load diff", error);
					new Notice("StoneSync: Could not load that change.");
					return;
				}
			}
			diffEl.show();
			toggle.setText("Hide changes");
		};
	}

	onClose(): void {
		this.contentEl.empty();
	}
}
