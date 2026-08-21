import { ItemView, type WorkspaceLeaf } from "obsidian";
import { searchVault, type SearchHit } from "./SearchClient";
import { openSearchHit } from "./openSearchHit";
import { StaleGuard } from "./staleGuard";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";
import { pickUserColor } from "../settings/userColor";

export const STONESYNC_HOME_VIEW_TYPE = "stonesync-home";

const DEBOUNCE_MS = 150;

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * A persistent "home" tab, auto-opened on startup (see `main.ts`'s `openHomeOnStartup` setting) -
 * the StoneSync-branded landing page a colleague sees instead of an arbitrary note, with a big
 * centered search bar (same look as the web dashboard's hero search - see `Design.md`/`PageShell`
 * server-side) wired to the same server-side full-text/fuzzy index as
 * `StoneSyncQuickSearchModal`, so it behaves the same way whether opened as a quick modal or as
 * this standing tab.
 */
export class StoneSyncHomeView extends ItemView {
	private readonly staleGuard = new StaleGuard();
	private hits: SearchHit[] = [];
	private highlightedIndex = 0;
	private inputEl!: HTMLInputElement;
	private resultsEl!: HTMLElement;

	constructor(leaf: WorkspaceLeaf, private readonly getSettings: () => StoneSyncSettings) {
		super(leaf);
		this.icon = "gem";
	}

	getViewType(): string {
		return STONESYNC_HOME_VIEW_TYPE;
	}

	getDisplayText(): string {
		return "StoneSync";
	}

	async onOpen(): Promise<void> {
		this.containerEl.addClass("stonesync-home-view");
		const content = this.contentEl;
		content.empty();

		// Built defensively: a thrown error here used to leave a silent, unstyled blank pane with
		// no indication anything went wrong - now it renders a visible, readable message instead.
		try {
			const hero = content.createDiv({ cls: "stonesync-home-hero" });
			const wordmark = hero.createDiv({ cls: "stonesync-home-wordmark" });
			wordmark.createSpan({ text: "Stone", cls: "stonesync-home-wordmark-accent" });
			wordmark.createSpan({ text: "Sync" });

			const form = hero.createEl("form");
			form.addEventListener("submit", (evt) => evt.preventDefault());
			this.inputEl = form.createEl("input", {
				type: "text",
				placeholder: "Search notes, PDFs, screenshots…",
				cls: "stonesync-home-input",
			});
			const button = form.createEl("button", { type: "submit", cls: "stonesync-home-search-btn", text: "⌕" });
			button.setAttr("aria-label", "Search");

			hero.createDiv({
				cls: "stonesync-home-hint",
				text: "Searches every note and attachment in this vault, OCR included - typos are okay.",
			});

			this.resultsEl = content.createDiv({ cls: "stonesync-home-results" });

			this.inputEl.addEventListener("input", () => void this.runSearch(this.inputEl.value));
			this.inputEl.addEventListener("keydown", (evt) => this.onKeyDown(evt));
			window.setTimeout(() => this.inputEl.focus(), 0);
		} catch (error) {
			console.error("[StoneSync] Failed to render the home view", error);
			content.empty();
			content.createDiv({
				cls: "stonesync-home-error",
				text: `StoneSync couldn't render the home view: ${error instanceof Error ? error.message : String(error)}`,
			});
		}
	}

	async onClose(): Promise<void> {
		this.contentEl.empty();
	}

	private onKeyDown(evt: KeyboardEvent): void {
		if (this.hits.length === 0) return;

		if (evt.key === "ArrowDown") {
			evt.preventDefault();
			this.highlightedIndex = (this.highlightedIndex + 1) % this.hits.length;
			this.renderResults();
		} else if (evt.key === "ArrowUp") {
			evt.preventDefault();
			this.highlightedIndex = (this.highlightedIndex - 1 + this.hits.length) % this.hits.length;
			this.renderResults();
		} else if (evt.key === "Enter" || evt.key === "Tab") {
			// Tab bound to the same action as Enter - open the highlighted result immediately,
			// the fastest keyboard gesture (matching StoneSyncQuickSearchModal's same choice).
			evt.preventDefault();
			void this.open(this.hits[this.highlightedIndex]);
		}
	}

	private async runSearch(query: string): Promise<void> {
		const token = this.staleGuard.start();
		await sleep(DEBOUNCE_MS);
		if (!this.staleGuard.isCurrent(token)) return;

		const settings = this.getSettings();
		if (!isConfigured(settings) || query.trim().length === 0) {
			this.hits = [];
			this.highlightedIndex = 0;
			this.renderResults();
			return;
		}

		try {
			const hits = await searchVault(settings.serverUrl, settings.apiKey, settings.vaultId, query);
			if (!this.staleGuard.isCurrent(token)) return;
			this.hits = hits;
			this.highlightedIndex = 0;
			this.renderResults();
		} catch (error) {
			console.error("[StoneSync] Home search failed", error);
		}
	}

	private renderResults(): void {
		this.resultsEl.empty();
		this.hits.forEach((hit, index) => {
			const row = this.resultsEl.createDiv({
				cls: `stonesync-home-hit${index === this.highlightedIndex ? " is-highlighted" : ""}`,
			});
			row.createDiv({ cls: "stonesync-home-hit-path", text: hit.path });
			const snippet = row.createDiv({ cls: "stonesync-home-hit-snippet" });
			snippet.innerHTML = hit.snippetHtml;
			row.addEventListener("click", () => void this.open(hit));
			row.addEventListener("mouseenter", () => {
				this.highlightedIndex = index;
				this.renderResults();
			});
		});
	}

	private async open(hit: SearchHit): Promise<void> {
		const settings = this.getSettings();
		await openSearchHit(
			{ app: this.app, settings, userName: settings.displayName, userColor: pickUserColor(settings.displayName) },
			hit
		);
	}
}
