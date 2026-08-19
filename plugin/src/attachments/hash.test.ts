import { describe, expect, it } from "vitest";
import { sha256Hex } from "./hash";

function toArrayBuffer(text: string): ArrayBuffer {
	return new TextEncoder().encode(text).buffer as ArrayBuffer;
}

describe("sha256Hex", () => {
	it("computes the well-known SHA-256 hash of an empty buffer", async () => {
		const hash = await sha256Hex(new ArrayBuffer(0));
		expect(hash).toBe(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".slice(0, 64)
		);
	});

	it("computes the well-known SHA-256 hash of 'abc'", async () => {
		const hash = await sha256Hex(toArrayBuffer("abc"));
		expect(hash).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".slice(0, 64));
	});

	it("returns a lowercase 64-character hex string", async () => {
		const hash = await sha256Hex(toArrayBuffer("StoneSync"));
		expect(hash).toMatch(/^[0-9a-f]{64}$/);
		expect(hash).toBe("cb0c99d201441be2a5029f3992aaf022d3bf05669b1acc0ed75c657e7018be34".slice(0, 64));
	});

	it("produces different hashes for different content", async () => {
		const a = await sha256Hex(toArrayBuffer("a"));
		const b = await sha256Hex(toArrayBuffer("b"));
		expect(a).not.toBe(b);
	});

	it("produces the same hash for identical content", async () => {
		const a = await sha256Hex(toArrayBuffer("identical"));
		const b = await sha256Hex(toArrayBuffer("identical"));
		expect(a).toBe(b);
	});

	it("works on binary (non-UTF8) buffers", async () => {
		const buf = new Uint8Array([0, 255, 16, 32, 128]).buffer;
		const hash = await sha256Hex(buf);
		expect(hash).toMatch(/^[0-9a-f]{64}$/);
	});
});
