/**
 * Recognises the one kind of link StoneSync is involved in: `[[slug:Path/Note]]`, pointing into
 * another vault.
 *
 * Everything else - every ordinary `[[Note]]`, `[[folder/Note|alias]]`, `[[Note#Heading]]` - is
 * deliberately left completely alone. A StoneSync vault has to keep working as a plain Obsidian
 * vault with no server in reach, so local links are never parsed, rewritten or resolved by us;
 * Obsidian already does all of that, offline, and does it well.
 *
 * A namespace only counts as one if it looks like a slug (lowercase, digits, dashes), so
 * `[[Meeting: Q3]]` or `[[C:/notes/thing]]` stay local links. Mirrors `WikiLinks.java`.
 */
const LINK = /\[\[([^\[\]]+?)]]/g;
const NAMESPACED = /^([a-z0-9][a-z0-9-]{0,62}):(.+)$/;

export interface CrossVaultLink {
	/** The vault namespace, e.g. "sales". */
	vaultSlug: string;
	/** Target note without the .md extension, heading anchor or alias. */
	targetPath: string;
	/** Display text: the alias if there is one, otherwise the target. */
	label: string;
	/** The literal `[[...]]` text as written. */
	raw: string;
}

export function parseCrossVaultLink(inner: string): CrossVaultLink | null {
	const withoutAlias = inner.split("|", 1)[0];
	const alias = inner.includes("|") ? inner.slice(inner.indexOf("|") + 1) : null;
	const target = withoutAlias.split("#", 1)[0].trim();
	const match = NAMESPACED.exec(target);
	if (!match) return null;
	const targetPath = stripExtension(match[2].trim().replace(/^\/+/, ""));
	return {
		vaultSlug: match[1],
		targetPath,
		label: alias ?? `${match[1]}:${targetPath}`,
		raw: `[[${inner}]]`,
	};
}

/** Every cross-vault link in a piece of Markdown, in order of appearance. */
export function findCrossVaultLinks(markdown: string): CrossVaultLink[] {
	const found: CrossVaultLink[] = [];
	for (const match of markdown.matchAll(LINK)) {
		const link = parseCrossVaultLink(match[1]);
		if (link) found.push(link);
	}
	return found;
}

/** Where a mirrored copy of a foreign note lives inside this vault. */
export function mirrorPathFor(mirrorFolder: string, vaultSlug: string, targetPath: string): string {
	const folder = mirrorFolder.replace(/^\/+|\/+$/g, "");
	return `${folder}/${vaultSlug}/${stripExtension(targetPath)}.md`;
}

function stripExtension(path: string): string {
	return path.endsWith(".md") ? path.slice(0, -3) : path;
}
