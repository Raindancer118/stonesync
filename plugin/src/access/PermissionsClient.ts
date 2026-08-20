import { requestUrl } from "obsidian";
import type { VaultPermissions } from "./permissions";

/** Everything the plugin needs to know about the current user's standing on the server. */
export interface Me {
	userId: string;
	email: string;
	systemRole: "USER" | "ADMIN";
	vaults: Array<{ vaultId: string; name: string; role: "OWNER" | "EDITOR" | "VIEWER" }>;
}

export interface Member {
	userId: string;
	email: string;
	role: "OWNER" | "EDITOR" | "VIEWER";
}

export interface Rule {
	id: string;
	pathPrefix: string;
	userId: string | null;
	email: string | null;
	level: "NONE" | "VIEWER" | "EDITOR" | "OWNER";
}

export interface AuditEntry {
	id: number;
	occurredAt: string;
	type: string;
	actor: string;
	subjectId: string | null;
	path: string | null;
	documentId: string | null;
	detail: string | null;
}

export interface HistoryEntry {
	commitId: string;
	authorEmail: string;
	changedAt: string;
	message: string;
}

/**
 * Thin client for the permission/audit surface. Kept separate from the sync path on purpose:
 * none of this is required for editing to work, so a failure here degrades the UI (no member
 * list, assumed-writable editor) instead of breaking synchronization.
 */
export class PermissionsClient {
	constructor(
		private readonly serverUrl: string,
		private readonly apiKey: string,
		private readonly vaultId: string
	) {}

	me(): Promise<Me> {
		return this.request<Me>("GET", "/api/me");
	}

	permissions(): Promise<VaultPermissions> {
		return this.request<VaultPermissions>("GET", `/api/vaults/${encodeURIComponent(this.vaultId)}/permissions`);
	}

	members(): Promise<Member[]> {
		return this.request<Member[]>("GET", `/api/vaults/${encodeURIComponent(this.vaultId)}/members`);
	}

	setMemberRole(memberId: string, role: Member["role"]): Promise<void> {
		return this.request<void>("PUT", `/api/vaults/${encodeURIComponent(this.vaultId)}/members/${memberId}`, { role });
	}

	removeMember(memberId: string): Promise<void> {
		return this.request<void>("DELETE", `/api/vaults/${encodeURIComponent(this.vaultId)}/members/${memberId}`);
	}

	invite(email: string, role: Member["role"]): Promise<{ inviteUrl: string }> {
		return this.request<{ inviteUrl: string }>("POST", `/api/vaults/${encodeURIComponent(this.vaultId)}/invites`, {
			email,
			role,
		});
	}

	rules(): Promise<Rule[]> {
		return this.request<Rule[]>("GET", `/api/vaults/${encodeURIComponent(this.vaultId)}/rules`);
	}

	setRule(pathPrefix: string, userId: string | null, level: Rule["level"]): Promise<Rule> {
		return this.request<Rule>("PUT", `/api/vaults/${encodeURIComponent(this.vaultId)}/rules`, {
			pathPrefix,
			userId,
			level,
		});
	}

	removeRule(ruleId: string): Promise<void> {
		return this.request<void>("DELETE", `/api/vaults/${encodeURIComponent(this.vaultId)}/rules/${ruleId}`);
	}

	audit(limit = 100): Promise<AuditEntry[]> {
		return this.request<AuditEntry[]>("GET", `/api/vaults/${encodeURIComponent(this.vaultId)}/audit?limit=${limit}`);
	}

	history(documentId: string, limit = 50): Promise<HistoryEntry[]> {
		return this.request<HistoryEntry[]>("GET", `/api/documents/${documentId}/history?limit=${limit}`);
	}

	diff(documentId: string, commitId: string): Promise<string> {
		return this.requestText("GET", `/api/documents/${documentId}/history/${commitId}/diff`);
	}

	private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
		const response = await this.send(method, path, body);
		return response.json as T;
	}

	private async requestText(method: string, path: string): Promise<string> {
		const response = await this.send(method, path);
		return response.text;
	}

	private async send(method: string, path: string, body?: unknown) {
		const base = this.serverUrl.trim().replace(/\/+$/, "");
		const response = await requestUrl({
			url: `${base}${path}`,
			method,
			headers: {
				Authorization: `Bearer ${this.apiKey}`,
				"Content-Type": "application/json",
			},
			body: body === undefined ? undefined : JSON.stringify(body),
			throw: false,
		});
		if (response.status < 200 || response.status >= 300) {
			throw new PermissionRequestError(`${method} ${path} failed (HTTP ${response.status}).`, response.status);
		}
		return response;
	}
}

export class PermissionRequestError extends Error {
	constructor(message: string, readonly status: number) {
		super(message);
		this.name = "PermissionRequestError";
	}
}
