import { requestUrl } from "obsidian";

export type LinkStatus = "AVAILABLE" | "RESTRICTED" | "NOT_FOUND";

export interface ResolvedLink {
	status: LinkStatus;
	documentId: string | null;
	vaultId: string | null;
	path: string | null;
	writable: boolean;
}

export interface Backlink {
	documentId: string;
	vaultId: string;
	path: string;
	vaultSlug: string | null;
	linkText: string;
}

export interface PendingRewrite {
	id: number;
	oldLink: string;
	newLink: string;
}

/** Talks to the cross-vault link endpoints. Only ever used for namespaced links. */
export class LinkClient {
	constructor(
		private readonly serverUrl: string,
		private readonly apiKey: string
	) {}

	resolve(vaultSlug: string, path: string): Promise<ResolvedLink> {
		const query = `vault=${encodeURIComponent(vaultSlug)}&path=${encodeURIComponent(path)}`;
		return this.request<ResolvedLink>("GET", `/api/links/resolve?${query}`);
	}

	backlinks(documentId: string): Promise<Backlink[]> {
		return this.request<Backlink[]>("GET", `/api/links/backlinks/${documentId}`);
	}

	pendingRewrites(documentId: string): Promise<PendingRewrite[]> {
		return this.request<PendingRewrite[]>("GET", `/api/links/rewrites/${documentId}`);
	}

	markRewriteApplied(documentId: string, rewriteId: number): Promise<void> {
		return this.request<void>("POST", `/api/links/rewrites/${documentId}/${rewriteId}/applied`);
	}

	private async request<T>(method: string, path: string): Promise<T> {
		const base = this.serverUrl.trim().replace(/\/+$/, "");
		const response = await requestUrl({
			url: `${base}${path}`,
			method,
			headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
			throw: false,
		});
		if (response.status < 200 || response.status >= 300) {
			throw new Error(`${method} ${path} failed (HTTP ${response.status}).`);
		}
		return response.json as T;
	}
}
