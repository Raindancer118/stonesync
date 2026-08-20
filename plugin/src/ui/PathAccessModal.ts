import { App, Modal, Notice, Setting } from "obsidian";
import type { PathAccess, PathAccessEntry, PermissionsClient } from "../access/PermissionsClient";

const LEVELS = ["NONE", "VIEWER", "EDITOR", "OWNER"] as const;
const INHERIT = "__inherit__";

/**
 * Per-file (or per-folder) access, opened from the right-click menu: who can see this, who can
 * change it, and where that comes from.
 *
 * Every row can either inherit - from the vault role or from a rule on a parent folder - or carry
 * a rule of its own on exactly this path. Removing that rule falls back to inheritance rather
 * than to "no access", which is what people expect from a permission dialog and what keeps a
 * single note from silently drifting away from its folder's rules.
 */
export class PathAccessModal extends Modal {
	private access: PathAccess | null = null;

	constructor(
		app: App,
		private readonly client: PermissionsClient,
		private readonly path: string,
		private readonly isFolder: boolean
	) {
		super(app);
	}

	async onOpen(): Promise<void> {
		this.modalEl.addClass("stonesync-members-modal");
		await this.reload();
	}

	private async reload(): Promise<void> {
		try {
			this.access = await this.client.accessFor(this.path);
		} catch (error) {
			console.error("[StoneSync] Failed to load access for", this.path, error);
			this.contentEl.empty();
			this.contentEl.createEl("h2", { text: "Access" });
			this.contentEl.createEl("p", {
				text: "Only a vault owner can see or change who has access - the server refused this request.",
			});
			return;
		}
		this.render();
	}

	private render(): void {
		const { contentEl } = this;
		contentEl.empty();
		contentEl.createEl("h2", { text: this.isFolder ? `Access to ${this.path}/` : `Access to ${this.path}` });
		contentEl.createEl("p", {
			cls: "setting-item-description",
			text: this.isFolder
				? "Applies to this folder and everything inside it. More specific rules deeper down win."
				: "Applies to this note. A rule here overrides whatever its folders say.",
		});

		for (const entry of this.access?.entries ?? []) {
			this.renderRow(entry);
		}

		contentEl.createEl("p", {
			cls: "setting-item-description",
			text: "‘Inherit’ removes the rule for this path again. ‘No access’ hides it completely: "
				+ "it is not listed, not downloaded, and any copy already on that person's device is removed.",
		});
	}

	private renderRow(entry: PathAccessEntry): void {
		const isEveryone = entry.userId === null;
		const setting = new Setting(this.contentEl)
			.setName(isEveryone ? "Everyone else" : entry.email)
			.setDesc(this.describe(entry));

		setting.addDropdown((dropdown) => {
			dropdown.addOption(INHERIT, isEveryone ? "no special rule" : "inherit");
			LEVELS.forEach((level) => dropdown.addOption(level, this.labelFor(level)));
			dropdown.setValue(entry.exactRuleId ? entry.level : INHERIT);
			dropdown.onChange(async (value) => {
				if (value === INHERIT) {
					if (!entry.exactRuleId) return;
					await this.apply(() => this.client.removeRule(entry.exactRuleId as string));
				} else {
					await this.apply(() => this.client.setRule(this.path, entry.userId, value as (typeof LEVELS)[number]));
				}
			});
		});
	}

	/** Explains the current state in words, including where an inherited level came from. */
	private describe(entry: PathAccessEntry): string {
		const effective = `Currently: ${this.labelFor(entry.level)}`;
		if (entry.exactRuleId) {
			return `${effective} — set here`;
		}
		if (entry.inheritedFrom !== null) {
			return `${effective} — from the rule on ${entry.inheritedFrom === "" ? "the whole vault" : entry.inheritedFrom}`;
		}
		if (entry.vaultRole) {
			return `${effective} — from their vault role (${entry.vaultRole.toLowerCase()})`;
		}
		return effective;
	}

	private labelFor(level: string): string {
		switch (level) {
			case "NONE":
				return "no access";
			case "VIEWER":
				return "read only";
			case "EDITOR":
				return "can edit";
			case "OWNER":
				return "full access";
			default:
				return level.toLowerCase();
		}
	}

	private async apply(action: () => Promise<unknown>): Promise<void> {
		try {
			await action();
			new Notice("StoneSync: Access updated.");
		} catch (error) {
			console.error("[StoneSync] Failed to change access", error);
			new Notice("StoneSync: The server refused that change.");
		}
		await this.reload();
	}

	onClose(): void {
		this.contentEl.empty();
	}
}
