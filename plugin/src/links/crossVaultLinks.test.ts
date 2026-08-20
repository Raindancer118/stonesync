import { describe, expect, it } from "vitest";
import { findCrossVaultLinks, mirrorPathFor, parseCrossVaultLink } from "./crossVaultLinks";

describe("cross-vault link parsing", () => {
	it("leaves every ordinary Obsidian link alone - those must work with no server at all", () => {
		expect(findCrossVaultLinks("[[Meeting Notes]] [[folder/Note|alias]] [[Note#Heading]]")).toEqual([]);
	});

	it("does not mistake a colon in the title for a namespace", () => {
		expect(findCrossVaultLinks("[[Meeting: Q3]] [[C:/notes/thing]] [[Projekt:Alpha]]")).toEqual([]);
	});

	it("finds a namespaced link with its vault and target", () => {
		const links = findCrossVaultLinks("Budget [[sales:Finanzen/Jahresabschluss]] fertig");

		expect(links).toHaveLength(1);
		expect(links[0].vaultSlug).toBe("sales");
		expect(links[0].targetPath).toBe("Finanzen/Jahresabschluss");
		expect(links[0].raw).toBe("[[sales:Finanzen/Jahresabschluss]]");
	});

	it("uses the alias as the label and ignores the heading anchor for the target", () => {
		const link = parseCrossVaultLink("sales:Jahresabschluss#Q3|die Zahlen");

		expect(link?.targetPath).toBe("Jahresabschluss");
		expect(link?.label).toBe("die Zahlen");
	});

	it("labels an alias-less link with its full namespaced target", () => {
		expect(parseCrossVaultLink("sales:Jahresabschluss")?.label).toBe("sales:Jahresabschluss");
	});

	it("drops the .md extension the way Obsidian writes links", () => {
		expect(parseCrossVaultLink("docs:api/Reference.md")?.targetPath).toBe("api/Reference");
	});

	it("puts a mirrored foreign note in a predictable place", () => {
		expect(mirrorPathFor("_shared", "sales", "Finanzen/Jahresabschluss")).toBe(
			"_shared/sales/Finanzen/Jahresabschluss.md"
		);
		expect(mirrorPathFor("/_shared/", "docs", "api/Reference.md")).toBe("_shared/docs/api/Reference.md");
	});
});
