const CURSOR_COLORS = [
	"#e57373",
	"#64b5f6",
	"#81c784",
	"#ffb74d",
	"#ba68c8",
	"#4db6ac",
	"#f06292",
	"#a1887f",
];

/**
 * Deterministic cursor color for a display name, shared by every place that needs to show a
 * collaborator's presence (live cursors, awareness) - same name always gets the same color,
 * with no server round-trip or stored assignment needed.
 */
export function pickUserColor(seed: string): string {
	let hash = 0;
	for (let i = 0; i < seed.length; i++) {
		hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
	}
	return CURSOR_COLORS[hash % CURSOR_COLORS.length];
}
