package de.tstieh.stonesync.dashboard;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders a note's materialized plaintext to HTML for the read-only vault viewer. {@code Parser}
 * and {@code HtmlRenderer} instances are documented as thread-safe/reusable, so both are built
 * once. {@code escapeHtml(true)} is the important part: without it, raw HTML embedded in a note
 * (commonmark passes it through by default) would render/execute in another vault member's
 * browser - one collaborator's note content must never be able to script another's session.
 */
final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().escapeHtml(true).build();

    private MarkdownRenderer() {
    }

    static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p style=\"color:#666;\"><em>(empty note)</em></p>";
        }
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }
}
