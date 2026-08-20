/**
 * Modules that must NEVER be bundled into main.js.
 *
 * Obsidian ships its own CodeMirror 6 instance and hands it to plugins through its
 * `require()` shim. Bundling a second copy is not merely wasteful - CM6 resolves facets,
 * state fields and compartments by *object identity*, so extensions built against a bundled
 * copy are foreign objects to Obsidian's editor: registering them throws "Unrecognized
 * extension value in extension set" and the whole editor extension (here: the Yjs collab
 * binding incl. remote cursors) silently never attaches. Kept in its own module so the
 * list is testable (see externals.test.ts).
 */
export const OBSIDIAN_PROVIDED_MODULES = [
	"obsidian",
	"electron",
	"@codemirror/autocomplete",
	"@codemirror/collab",
	"@codemirror/commands",
	"@codemirror/language",
	"@codemirror/lint",
	"@codemirror/search",
	"@codemirror/state",
	"@codemirror/view",
	"@lezer/common",
	"@lezer/highlight",
	"@lezer/lr",
];
