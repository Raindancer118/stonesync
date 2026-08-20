package de.tstieh.stonesync.links;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WikiLinksTest {

    @Test
    @DisplayName("a plain Obsidian link is never treated as cross-vault - it must keep working with no server")
    void localLinksAreIgnored() {
        assertThat(WikiLinks.crossVaultLinks("See [[Meeting Notes]] and [[folder/Other Note|the other one]].")).isEmpty();
    }

    @Test
    @DisplayName("something that merely contains a colon stays a local link")
    void colonAloneDoesNotMakeItCrossVault() {
        assertThat(WikiLinks.crossVaultLinks("[[Meeting: Q3]] [[C:/notes/thing]] [[Projekt:Alpha]]")).isEmpty();
    }

    @Test
    @DisplayName("a namespaced link is recognised with its vault slug and target path")
    void findsCrossVaultLinks() {
        var links = WikiLinks.crossVaultLinks("Budget: [[sales:Jahresabschluss]] and [[project-alpha:docs/Architektur.md]]");

        assertThat(links).hasSize(2);
        assertThat(links.get(0).vaultSlug()).isEqualTo("sales");
        assertThat(links.get(0).targetPath()).isEqualTo("Jahresabschluss");
        assertThat(links.get(1).vaultSlug()).isEqualTo("project-alpha");
        assertThat(links.get(1).targetPath()).isEqualTo("docs/Architektur");
    }

    @Test
    @DisplayName("alias and heading anchors do not change what a link points at")
    void aliasAndHeadingAreStrippedFromTheTarget() {
        var links = WikiLinks.crossVaultLinks("[[sales:Jahresabschluss#Q3|die Zahlen]]");

        assertThat(links).hasSize(1);
        assertThat(links.get(0).targetPath()).isEqualTo("Jahresabschluss");
        assertThat(links.get(0).linkText()).isEqualTo("[[sales:Jahresabschluss#Q3|die Zahlen]]");
    }

    @Test
    @DisplayName("the same link twice is indexed once")
    void duplicatesAreCollapsed() {
        assertThat(WikiLinks.crossVaultLinks("[[sales:X]] ... [[sales:X]]")).hasSize(1);
    }

    @Test
    @DisplayName("rewriting a link keeps the author's alias and heading intact")
    void rewriteKeepsAliasAndHeading() {
        assertThat(WikiLinks.rewriteTarget("[[sales:Jahresabschluss#Q3|die Zahlen]]", "sales", "Finanzen/Jahresabschluss 2026.md"))
                .isEqualTo("[[sales:Finanzen/Jahresabschluss 2026#Q3|die Zahlen]]");
        assertThat(WikiLinks.rewriteTarget("[[sales:Alt]]", "finance", "Neu.md"))
                .isEqualTo("[[finance:Neu]]");
    }

    @Test
    @DisplayName("a link knows whether it points at a given note")
    void pointsAtComparesVaultAndPath() {
        var link = WikiLinks.crossVaultLinks("[[sales:Finanzen/Jahresabschluss]]").get(0);

        assertThat(WikiLinks.pointsAt(link, "sales", "Finanzen/Jahresabschluss.md")).isTrue();
        assertThat(WikiLinks.pointsAt(link, "sales", "Finanzen/Anderes.md")).isFalse();
        assertThat(WikiLinks.pointsAt(link, "hr", "Finanzen/Jahresabschluss.md")).isFalse();
    }
}
