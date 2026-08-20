import { App, Modal, Notice, Setting } from "obsidian";
import type { Member, PermissionsClient, Rule } from "../access/PermissionsClient";
import type { AccessLevel } from "../access/permissions";

const VAULT_ROLES: Array<Member["role"]> = ["OWNER", "EDITOR", "VIEWER"];
const RULE_LEVELS: AccessLevel[] = ["NONE", "VIEWER", "EDITOR", "OWNER"];

/**
 * Owner-facing permission management inside Obsidian: who is in this vault, what may they do,
 * and which folders are scoped differently. Everything here goes through the same endpoints the
 * console commands use - the server, not this dialog, decides whether the caller may do any of it.
 */
export class MembersModal extends Modal {
	private members: Member[] = [];
	private rules: Rule[] = [];

	constructor(app: App, private readonly client: PermissionsClient) {
		super(app);
	}

	async onOpen(): Promise<void> {
		this.modalEl.addClass("stonesync-members-modal");
		await this.reload();
	}

	private async reload(): Promise<void> {
		try {
			[this.members, this.rules] = await Promise.all([this.client.members(), this.client.rules()]);
		} catch (error) {
			this.contentEl.empty();
			this.contentEl.createEl("h2", { text: "Collaborators" });
			this.contentEl.createEl("p", {
				text: "Only a vault owner can manage collaborators - the server refused this request.",
			});
			console.error("[StoneSync] Failed to load members/rules", error);
			return;
		}
		this.render();
	}

	private render(): void {
		const { contentEl } = this;
		contentEl.empty();
		contentEl.createEl("h2", { text: "Collaborators" });

		for (const member of this.members) {
			new Setting(contentEl)
				.setName(member.email)
				.setDesc(member.role === "OWNER" ? "Can manage members, rules and history" : "")
				.addDropdown((dropdown) => {
					VAULT_ROLES.forEach((role) => dropdown.addOption(role, role.toLowerCase()));
					dropdown.setValue(member.role);
					dropdown.onChange(async (value) => {
						await this.guard(() => this.client.setMemberRole(member.userId, value as Member["role"]),
							`${member.email} is now ${value.toLowerCase()}.`);
					});
				})
				.addExtraButton((button) =>
					button
						.setIcon("trash")
						.setTooltip("Remove from this vault")
						.onClick(async () => {
							await this.guard(() => this.client.removeMember(member.userId),
								`${member.email} no longer has access.`);
						})
				);
		}

		contentEl.createEl("h3", { text: "Invite someone" });
		let inviteEmail = "";
		let inviteRole: Member["role"] = "EDITOR";
		new Setting(contentEl)
			.setName("Email address")
			.setDesc("They log in once and their plugin configures itself.")
			.addText((text) => text.setPlaceholder("colleague@example.com").onChange((value) => (inviteEmail = value)))
			.addDropdown((dropdown) => {
				VAULT_ROLES.forEach((role) => dropdown.addOption(role, role.toLowerCase()));
				dropdown.setValue(inviteRole);
				dropdown.onChange((value) => (inviteRole = value as Member["role"]));
			})
			.addButton((button) =>
				button
					.setButtonText("Create invite link")
					.setCta()
					.onClick(async () => {
						if (!inviteEmail.trim()) {
							new Notice("StoneSync: Please enter an email address first.");
							return;
						}
						try {
							const { inviteUrl } = await this.client.invite(inviteEmail.trim(), inviteRole);
							await navigator.clipboard.writeText(inviteUrl);
							new Notice("StoneSync: Invite link copied to your clipboard.");
						} catch (error) {
							console.error("[StoneSync] Failed to create invite", error);
							new Notice("StoneSync: Could not create the invite link.");
						}
					})
			);

		contentEl.createEl("h3", { text: "Folder rules" });
		contentEl.createEl("p", {
			text: "A rule overrides the role above for one folder or note. 'none' hides it entirely - "
				+ "it is not listed, not downloaded, and cannot be opened.",
			cls: "setting-item-description",
		});

		for (const rule of this.rules) {
			new Setting(contentEl)
				.setName(rule.pathPrefix === "" ? "(whole vault)" : rule.pathPrefix)
				.setDesc(`${rule.level.toLowerCase()} for ${rule.email ?? "everyone"}`)
				.addExtraButton((button) =>
					button
						.setIcon("trash")
						.setTooltip("Remove this rule")
						.onClick(async () => {
							await this.guard(() => this.client.removeRule(rule.id), "Rule removed.");
						})
				);
		}

		let rulePath = "";
		let ruleMember: string = "";
		let ruleLevel: AccessLevel = "NONE";
		new Setting(contentEl)
			.setName("Add a rule")
			.addText((text) => text.setPlaceholder("Folder/or/Note.md").onChange((value) => (rulePath = value)))
			.addDropdown((dropdown) => {
				dropdown.addOption("", "everyone");
				this.members.forEach((member) => dropdown.addOption(member.userId, member.email));
				dropdown.onChange((value) => (ruleMember = value));
			})
			.addDropdown((dropdown) => {
				RULE_LEVELS.forEach((level) => dropdown.addOption(level, level.toLowerCase()));
				dropdown.setValue(ruleLevel);
				dropdown.onChange((value) => (ruleLevel = value as AccessLevel));
			})
			.addButton((button) =>
				button
					.setButtonText("Add")
					.setCta()
					.onClick(async () => {
						await this.guard(
							() => this.client.setRule(rulePath.trim(), ruleMember === "" ? null : ruleMember, ruleLevel),
							"Rule saved."
						);
					})
			);
	}

	/** Runs a mutation, reports it, and re-reads the state so the dialog never shows stale data. */
	private async guard(action: () => Promise<unknown>, successMessage: string): Promise<void> {
		try {
			await action();
			new Notice(`StoneSync: ${successMessage}`);
			await this.reload();
		} catch (error) {
			console.error("[StoneSync] Permission change failed", error);
			new Notice("StoneSync: The server refused that change.");
			await this.reload();
		}
	}

	onClose(): void {
		this.contentEl.empty();
	}
}
