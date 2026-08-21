package de.tstieh.stonesync.invite;

/**
 * Minimal HTML-escaping for the small amount of raw HTML this server hand-writes directly to a
 * {@link jakarta.servlet.http.HttpServletResponse} (no templating engine is configured). Shared
 * by every place that builds such a page - see {@link AuthentikLoginSuccessHandler} and
 * {@link de.tstieh.stonesync.dashboard.DashboardController}.
 */
public final class HtmlEscaper {

    private HtmlEscaper() {
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
