import { describe, expect, it } from "vitest";
import { buildMultipartBody } from "./multipart";

describe("buildMultipartBody", () => {
	it("produces a well-formed multipart/form-data body with all fields and the file payload", () => {
		const data = new TextEncoder().encode("binary-content").buffer as ArrayBuffer;
		const body = buildMultipartBody("BOUNDARY123", {
			documentId: "11111111-1111-1111-1111-111111111111",
			hash: "abc123",
			modifiedAt: "2026-01-01T00:00:00Z",
			fileName: "photo.png",
			data,
		});

		const text = new TextDecoder().decode(body);

		expect(text).toContain('--BOUNDARY123\r\n');
		expect(text).toContain('name="documentId"');
		expect(text).toContain("11111111-1111-1111-1111-111111111111");
		expect(text).toContain('name="hash"');
		expect(text).toContain("abc123");
		expect(text).toContain('name="modifiedAt"');
		expect(text).toContain("2026-01-01T00:00:00Z");
		expect(text).toContain('name="file"; filename="photo.png"');
		expect(text).toContain("Content-Type: application/octet-stream");
		expect(text).toContain("binary-content");
		expect(text.trim().endsWith("--BOUNDARY123--")).toBe(true);
	});

	it("preserves exact binary payload bytes (not just as text)", () => {
		const bytes = new Uint8Array([0, 1, 2, 254, 255, 10, 13]);
		const body = buildMultipartBody("B", {
			documentId: "11111111-1111-1111-1111-111111111111",
			hash: "h",
			modifiedAt: "2026-01-01T00:00:00Z",
			fileName: "f.bin",
			data: bytes.buffer,
		});

		const combined = new Uint8Array(body);
		// the raw bytes must appear as a contiguous subsequence somewhere in the body
		const idx = indexOfSubsequence(combined, bytes);
		expect(idx).toBeGreaterThanOrEqual(0);
	});
});

function indexOfSubsequence(haystack: Uint8Array, needle: Uint8Array): number {
	outer: for (let i = 0; i <= haystack.length - needle.length; i++) {
		for (let j = 0; j < needle.length; j++) {
			if (haystack[i + j] !== needle[j]) continue outer;
		}
		return i;
	}
	return -1;
}
