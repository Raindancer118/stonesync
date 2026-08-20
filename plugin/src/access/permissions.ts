/**
 * Client-side mirror of the server's permission resolution (`PathRules` on the Java side).
 *
 * The client re-resolves permissions locally instead of asking per note, because the editor has
 * to know *before* the first keystroke whether this note is writable - a round trip per file
 * would either stall opening a note or let the user type into something they cannot save. The
 * server stays the authority: it refuses writes regardless of what the client believes.
 */
export type AccessLevel = "NONE" | "VIEWER" | "EDITOR" | "OWNER";

const ORDER: Record<AccessLevel, number> = { NONE: 0, VIEWER: 1, EDITOR: 2, OWNER: 3 };

export interface EffectiveRule {
	pathPrefix: string;
	level: AccessLevel;
}

export interface VaultPermissions {
	vaultLevel: AccessLevel;
	/** Only the rules that apply to this user - the server has already filtered them. */
	rules: EffectiveRule[];
}

export function normalizePath(path: string): string {
	return path.replace(/^\/+/, "").replace(/\/+$/, "");
}

/** A prefix covers a path if it is the path itself or one of its parent folders. */
export function prefixMatches(prefix: string, path: string): boolean {
	const normalizedPrefix = normalizePath(prefix);
	const normalizedPath = normalizePath(path);
	if (normalizedPrefix.length === 0) return true;
	return normalizedPath === normalizedPrefix || normalizedPath.startsWith(normalizedPrefix + "/");
}

/**
 * The level that applies to one note: the most specific matching rule wins (longest prefix),
 * falling back to the vault-wide level. Mirrors the server exactly - see `PathRules.resolve`.
 */
export function effectiveLevel(permissions: VaultPermissions, path: string): AccessLevel {
	let best: EffectiveRule | null = null;
	for (const rule of permissions.rules) {
		if (!prefixMatches(rule.pathPrefix, path)) continue;
		if (!best || normalizePath(rule.pathPrefix).length > normalizePath(best.pathPrefix).length) {
			best = rule;
		}
	}
	return best ? best.level : permissions.vaultLevel;
}

export function canRead(permissions: VaultPermissions, path: string): boolean {
	return ORDER[effectiveLevel(permissions, path)] >= ORDER.VIEWER;
}

export function canWrite(permissions: VaultPermissions, path: string): boolean {
	return ORDER[effectiveLevel(permissions, path)] >= ORDER.EDITOR;
}

export function canManage(permissions: VaultPermissions): boolean {
	return permissions.vaultLevel === "OWNER";
}

/**
 * Assumed until the server has answered: full access. Deliberately optimistic - a failed
 * permissions call must not lock a legitimate editor out of their own notes, and the server
 * refuses unauthorized writes anyway.
 */
export const UNKNOWN_PERMISSIONS: VaultPermissions = { vaultLevel: "EDITOR", rules: [] };
