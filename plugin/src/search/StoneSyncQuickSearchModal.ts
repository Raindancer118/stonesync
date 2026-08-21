import { SuggestModal, type App } from "obsidian";
import { searchVault, type SearchHit } from "./SearchClient";
import { openSearchHit } from "./openSearchHit";
import { StaleGuard } from "./staleGuard";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";
import { pickUserColor } from "../settings/userColor";

const DEBOUNCE_MS = 150;

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Fast, keyboard-first search over the vault's own server-side index (Postgres full-text +
 * fuzzy trigram search - see migrations V7/V8, `DocumentSearchService`) - the same idea as
 * Obsidian's Quick Switcher or a fuzzy-finder plugin, but backed by an index that understands
 * PDF/OCR-extracted attachment text and typos, which Obsidian's own local search does not.
 *
 * Debounced (150ms) and stale-response-guarded (`StaleGuard`): typing fast fires fewer server
 * round-trips, and a slow response for an earlier keystroke can never overwrite a newer one's
 * results after the user has already kept typing.
 *
 * Tab is bound to the same action as Enter (`selectActiveSuggestion`) - the fastest "just open
 * the top/highlighted match" gesture, matching how fuzzy-finder tools (fzf, Alfred, VS Code's
 * quick-open) commonly use Tab/Enter interchangeably to confirm.
 */
export class StoneSyncQuickSearchModal extends SuggestModal<SearchHit> {
	private readonly staleGuard = new StaleGuard();

	constructor(app: App, private readonly getSettings: () => StoneSyncSettings) {
		super(app);
		this.setPlaceholder("Search notes, PDFs, screenshots… (server-side, typo-tolerant)");
		this.setInstructions([
			{ command: "↑↓", purpose: "navigate" },
			{ command: "↵ / Tab", purpose: "open" },
			{ command: "esc", purpose: "dismiss" },
		]);
	}

	onOpen(): void {
		super.onOpen();
		this.scope.register([], "Tab", (evt) => {
			evt.preventDefault();
			this.selectActiveSuggestion(evt);
		});
	}

	async getSuggestions(query: string): Promise<SearchHit[]> {
		const token = this.staleGuard.start();
		await sleep(DEBOUNCE_MS);
		if (!this.staleGuard.isCurrent(token)) return [];

		const settings = this.getSettings();
		if (!isConfigured(settings) || query.trim().length === 0) return [];

		try {
			const hits = await searchVault(settings.serverUrl, settings.apiKey, settings.vaultId, query);
			return this.staleGuard.isCurrent(token) ? hits : [];
		} catch (error) {
			console.error("[StoneSync] Quick search failed", error);
			return [];
		}
	}

	renderSuggestion(hit: SearchHit, el: HTMLElement): void {
		el.addClass("stonesync-quicksearch-suggestion");
		el.createDiv({ cls: "stonesync-quicksearch-path", text: hit.path });
		const snippet = el.createDiv({ cls: "stonesync-quicksearch-snippet" });
		snippet.innerHTML = hit.snippetHtml;
	}

	async onChooseSuggestion(hit: SearchHit): Promise<void> {
		const settings = this.getSettings();
		await openSearchHit(
			{ app: this.app, settings, userName: settings.displayName, userColor: pickUserColor(settings.displayName) },
			hit
		);
	}
}
