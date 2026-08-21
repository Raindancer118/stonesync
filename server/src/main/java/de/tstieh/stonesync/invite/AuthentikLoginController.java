package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.dashboard.PageShell;
import de.tstieh.stonesync.logging.AppLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * Entry points for the collaborator-invite flow: a colleague opens
 * {@code https://.../invite/{token}} in any browser (no Obsidian/plugin state needed yet),
 * gets sent through an Authentik login, and - on success - {@link AuthentikLoginSuccessHandler}
 * redeems the token, grants vault access, and stashes just enough in the session for
 * {@link #connectNow} to hand back an {@code obsidian://} deep link whenever they actually click
 * "Connect now" - see that method's javadoc for why that's a separate step.
 *
 * <p>The token is deliberately NOT validated here - only stashed in the session for the success
 * handler to redeem after a real login. Pre-validating here would duplicate
 * {@link InviteService#redeem}'s validity checks in two places; an invalid/expired token simply
 * surfaces as a clear error page after login instead of before it.</p>
 */
@RestController
public class AuthentikLoginController {

    static final String SESSION_KEY_PENDING_INVITE_TOKEN = "stonesync.invite.pendingToken";
    static final String SESSION_KEY_READY_USER_ID = "stonesync.invite.readyUserId";
    static final String SESSION_KEY_READY_VAULT_ID = "stonesync.invite.readyVaultId";
    static final String SESSION_KEY_READY_DISPLAY_NAME = "stonesync.invite.readyDisplayName";

    private final AdminService adminService;
    private final ApiKeyExchangeService exchangeService;
    private final PublicUrlProperties publicUrlProperties;

    public AuthentikLoginController(AdminService adminService, ApiKeyExchangeService exchangeService,
                                     PublicUrlProperties publicUrlProperties) {
        this.adminService = adminService;
        this.exchangeService = exchangeService;
        this.publicUrlProperties = publicUrlProperties;
    }

    @GetMapping("/invite/{token}")
    public void startInviteLogin(@PathVariable String token, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY_PENDING_INVITE_TOKEN, token);
        AppLog.debug("Starting Authentik login for a pending invite token");
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/authentik");
    }

    /**
     * Mints a fresh device API key and its one-time exchange code, right when the colleague is
     * actually ready to open Obsidian - not at the moment they finished the Authentik login.
     *
     * <p>The original design minted both immediately after login and handed back a deep link
     * behind a 2-minute exchange-code window ({@link ApiKeyExchangeService}) - too short for
     * anyone who doesn't already have Obsidian and the StoneSync plugin installed (real feedback:
     * "what if you don't have that yet"). {@link AuthentikLoginSuccessHandler} now only stashes
     * the already-granted access (userId/vaultId/displayName - none of it secret) in the session
     * and shows install instructions; this endpoint does the actual minting, so the 2-minute
     * window starts only once they click "Connect now", however long installing took. Can be
     * clicked more than once (e.g. after installing on a second device) - each click is a fresh,
     * independently revocable device key, same as any other {@code invite-*} key.</p>
     */
    @GetMapping("/invite/connect")
    public void connectNow(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        UUID userId = session != null ? (UUID) session.getAttribute(SESSION_KEY_READY_USER_ID) : null;
        UUID vaultId = session != null ? (UUID) session.getAttribute(SESSION_KEY_READY_VAULT_ID) : null;
        String displayName = session != null ? (String) session.getAttribute(SESSION_KEY_READY_DISPLAY_NAME) : null;

        if (userId == null || vaultId == null) {
            AppLog.warn("'/invite/connect' visited with no pending connection in the session");
            writeConnectErrorPage(response, "Your session has expired, and your invite link has already been "
                    + "used to log you in once, so it won't work a second time. Please ask whoever invited you "
                    + "for a fresh invite link.");
            return;
        }

        String rawApiKey = adminService.createApiKey(userId, "invite-" + UUID.randomUUID());
        String exchangeCode = exchangeService.create(rawApiKey, vaultId, displayName);
        String deepLink = DeepLinkBuilder.build(publicUrlProperties.requireUrl(), exchangeCode);
        AppLog.info("Minted a connect link for {} on vault {}", displayName, vaultId);
        writeConnectSuccessPage(response, deepLink, displayName);
    }

    private void writeConnectSuccessPage(HttpServletResponse response, String deepLink, String displayName)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(PageShell.centered("StoneSync - You're in!", """
                <h1>Welcome, %s!</h1>
                <p class="lede">Click below to open Obsidian - StoneSync will configure itself and download the vault automatically.</p>
                <p><a href="%s" class="btn">Open in Obsidian</a></p>
                <p class="lede" style="font-size:0.9em;">This link works for the next 2 minutes. If it expires before you click it,
                just come back and click "Connect now" again.</p>
                """.formatted(HtmlEscaper.escape(displayName), deepLink)));
    }

    private void writeConnectErrorPage(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(PageShell.centered("StoneSync - Invite problem", """
                <h1>Something went wrong</h1>
                <p class="lede">%s</p>
                """.formatted(HtmlEscaper.escape(message))));
    }
}
