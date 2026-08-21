import { describe, it, expect } from "vitest";
import { labelForEvent } from "./syncQueueLabels";
import type { VaultEvent } from "../net/VaultEventsSocket";

describe("labelForEvent", () => {
	it("describes a document_created event as a download", () => {
		const event: VaultEvent = {
			type: "document_created",
			documentId: "id-1",
			path: "Notes/new.md",
			contentType: "TEXT",
			originSessionId: null,
		};

		expect(labelForEvent(event)).toContain("Downloading");
		expect(labelForEvent(event)).toContain("Notes/new.md");
	});

	it("describes a document_deleted event as a removal", () => {
		const event: VaultEvent = {
			type: "document_deleted",
			documentId: "id-2",
			path: "Notes/gone.md",
			originSessionId: null,
		};

		expect(labelForEvent(event)).toContain("Removing");
		expect(labelForEvent(event)).toContain("Notes/gone.md");
	});

	it("describes an access_revoked event", () => {
		const event: VaultEvent = {
			type: "access_revoked",
			documentId: null,
			path: "Private/secret.md",
			originSessionId: null,
		};

		expect(labelForEvent(event)).toContain("Revoking");
		expect(labelForEvent(event)).toContain("Private/secret.md");
	});

	it("describes a link_rewrite event", () => {
		const event: VaultEvent = {
			type: "link_rewrite",
			documentId: "id-3",
			path: "Notes/with-link.md",
			rewriteId: 1,
			oldLink: "[[old]]",
			newLink: "[[new]]",
			originSessionId: null,
		};

		expect(labelForEvent(event)).toContain("link rewrite");
		expect(labelForEvent(event)).toContain("Notes/with-link.md");
	});

	it("describes a vault_deleted event as sync stopping", () => {
		const event: VaultEvent = { type: "vault_deleted", originSessionId: null };

		expect(labelForEvent(event)).toContain("Stopping sync");
	});
});
