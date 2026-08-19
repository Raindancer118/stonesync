import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import {
	computeStateVector,
	computeOfflineDiff,
	isEmptyUpdate,
} from "./stateVectorSync";

describe("stateVectorSync", () => {
	it("computes a state vector for a fresh, empty doc", () => {
		const doc = new Y.Doc();
		const sv = computeStateVector(doc);
		expect(sv).toBeInstanceOf(Uint8Array);
	});

	it("computeOfflineDiff produces an update that only contains changes made after the snapshot", () => {
		const doc = new Y.Doc();
		const text = doc.getText("content");
		text.insert(0, "hello");

		// snapshot state vector "before going offline"
		const offlineStateVector = computeStateVector(doc);
		// full state at that same point in time, to replicate the shared
		// structural history onto a peer (a real peer would have received
		// this via the normal sync flow before the disconnect happened)
		const preOfflineFullUpdate = Y.encodeStateAsUpdate(doc);

		// local edits made while offline
		text.insert(5, " world");

		const diff = computeOfflineDiff(doc, offlineStateVector);

		// A peer that already had the pre-offline content should reach the
		// same full content by applying only the diff, proving the diff
		// carries exactly (and only) the offline changes.
		const peerWithPreOfflineState = new Y.Doc();
		Y.applyUpdate(peerWithPreOfflineState, preOfflineFullUpdate);

		Y.applyUpdate(peerWithPreOfflineState, diff);
		expect(peerWithPreOfflineState.getText("content").toString()).toBe(
			"hello world"
		);
	});

	it("computeOfflineDiff returns an empty-ish update when nothing changed offline", () => {
		const doc = new Y.Doc();
		doc.getText("content").insert(0, "unchanged");
		const sv = computeStateVector(doc);

		const diff = computeOfflineDiff(doc, sv);
		expect(isEmptyUpdate(diff)).toBe(true);
	});

	it("isEmptyUpdate returns false for a non-trivial update", () => {
		const doc = new Y.Doc();
		doc.getText("content").insert(0, "x");
		const update = Y.encodeStateAsUpdate(doc);
		expect(isEmptyUpdate(update)).toBe(false);
	});

	it("full round trip: two peers converge after exchanging an offline diff", () => {
		const local = new Y.Doc();
		const remote = new Y.Doc();

		local.getText("t").insert(0, "shared");
		Y.applyUpdate(remote, Y.encodeStateAsUpdate(local));

		// both now equal; local goes offline and edits further
		const offlineSv = computeStateVector(local);
		local.getText("t").insert(6, "-edit");

		const diff = computeOfflineDiff(local, offlineSv);
		Y.applyUpdate(remote, diff);

		expect(remote.getText("t").toString()).toBe(local.getText("t").toString());
	});
});
