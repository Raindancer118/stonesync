import { App, Modal } from "obsidian";
import type { VaultEventsManager, SyncQueueSnapshot } from "../sync/VaultEventsManager";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";

/**
 * "What is StoneSync doing right now" - live view of the vault-events reaction queue (downloads/
 * removals triggered by collaborators' changes or by reconciliation after a reconnect - see
 * `VaultEventsManager`) plus this device's own deletes still waiting to reach the server (offline
 * retry queue, see `SyncManager.flushPendingDeletes`). Updates live via
 * `VaultEventsManager.setQueueListener` while open, and unsubscribes on close.
 */
export class SyncQueueModal extends Modal {
	private activeEl: HTMLElement | null = null;
	private pendingEl: HTMLElement | null = null;
	private offlineEl: HTMLElement | null = null;

	constructor(
		app: App,
		private readonly vaultEventsManager: VaultEventsManager,
		private readonly getSettings: () => StoneSyncSettings
	) {
		super(app);
	}

	onOpen(): void {
		this.modalEl.addClass("stonesync-queue-modal");
		const { contentEl } = this;
		contentEl.createEl("h2", { text: "StoneSync sync queue" });

		contentEl.createEl("h3", { text: "In progress" });
		this.activeEl = contentEl.createDiv({ cls: "stonesync-queue-list" });

		contentEl.createEl("h3", { text: "Queued" });
		this.pendingEl = contentEl.createDiv({ cls: "stonesync-queue-list" });

		contentEl.createEl("h3", { text: "Waiting to sync (offline)" });
		contentEl.createEl("p", {
			cls: "stonesync-queue-hint",
			text: "Deletes made on this device that couldn't reach the server yet - retried automatically once back online.",
		});
		this.offlineEl = contentEl.createDiv({ cls: "stonesync-queue-list" });

		this.vaultEventsManager.setQueueListener((snapshot) => this.render(snapshot));
	}

	onClose(): void {
		this.vaultEventsManager.setQueueListener(null);
		this.contentEl.empty();
	}

	private render(snapshot: SyncQueueSnapshot): void {
		this.renderList(this.activeEl, snapshot.active.map((item) => item.label), "Nothing in progress right now.");
		this.renderList(this.pendingEl, snapshot.pending.map((item) => item.label), "Nothing queued.");
		this.renderList(this.offlineEl, this.getSettings().pendingDeletePaths ?? [], "Nothing waiting.");
	}

	private renderList(el: HTMLElement | null, labels: string[], emptyText: string): void {
		if (!el) return;
		el.empty();
		if (labels.length === 0) {
			el.createEl("p", { cls: "stonesync-queue-empty", text: emptyText });
			return;
		}
		const list = el.createEl("ul");
		for (const label of labels) {
			list.createEl("li", { text: label });
		}
	}
}
