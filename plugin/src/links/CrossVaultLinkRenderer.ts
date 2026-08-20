import { MarkdownRenderChild, type App } from "obsidian";
import { parseCrossVaultLink, type CrossVaultLink } from "./crossVaultLinks";

/**
 * Renders `[[slug:Note]]` as a real, clickable link in preview/reading mode.
 *
 * Obsidian would show these as unresolved links, since the target is not in this vault. Ordinary
 * links are never touched here - the post-processor only replaces text that parses as a
 * namespaced link, and leaves the DOM alone otherwise.
 */
export class CrossVaultLinkRenderer {
	constructor(
		private readonly app: App,
		private readonly onFollow: (link: CrossVaultLink) => void
	) {}

	/** Obsidian markdown post-processor: swaps namespaced links for clickable elements. */
	process = (element: HTMLElement, context: { addChild: (child: MarkdownRenderChild) => void }): void => {
		// Obsidian renders an unresolved [[x]] as <a class="internal-link is-unresolved">x</a>,
		// so the namespaced ones can be picked out precisely instead of walking raw text.
		const anchors = Array.from(element.querySelectorAll<HTMLAnchorElement>("a.internal-link"));
		for (const anchor of anchors) {
			const target = anchor.getAttribute("data-href") ?? anchor.getAttribute("href") ?? anchor.textContent ?? "";
			const link = parseCrossVaultLink(anchor.textContent === target ? target : `${target}|${anchor.textContent}`);
			if (!link) continue;

			const replacement = document.createElement("a");
			replacement.addClass("stonesync-cross-link");
			replacement.setText(anchor.textContent ?? link.label);
			replacement.setAttribute("aria-label", `${link.vaultSlug}: ${link.targetPath} (another vault)`);
			replacement.onclick = (event) => {
				event.preventDefault();
				this.onFollow(link);
			};
			anchor.replaceWith(replacement);
		}
	};
}
