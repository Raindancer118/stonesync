package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.logging.AppLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs once, right after a colleague successfully logs into Authentik via the invite flow (see
 * {@link AuthentikLoginController}): redeems the invite token stashed in the session, finds or
 * creates the corresponding StoneSync user by their verified email, grants the invite's role on
 * its vault, mints a fresh device API key, and hands back a small HTML page whose one link is
 * an {@code obsidian://} deep link carrying everything the plugin needs to auto-configure
 * itself - no manual copy-pasting of server URL/API key/vault ID.
 *
 * <p>A login with no pending invite token in the session (i.e. one that didn't start at
 * {@link AuthentikLoginController#startInviteLogin}) is a regular dashboard login instead - see
 * {@link de.tstieh.stonesync.dashboard.DashboardController}.</p>
 */
@Component
public class AuthentikLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final InviteService inviteService;
    private final UserRepository userRepository;
    private final AdminService adminService;
    private final ApiKeyExchangeService exchangeService;
    private final ApiKeyHasher apiKeyHasher;
    private final PublicUrlProperties publicUrlProperties;
    private final SecureRandom random = new SecureRandom();

    public AuthentikLoginSuccessHandler(InviteService inviteService, UserRepository userRepository,
                                         AdminService adminService, ApiKeyExchangeService exchangeService,
                                         ApiKeyHasher apiKeyHasher, PublicUrlProperties publicUrlProperties) {
        this.inviteService = inviteService;
        this.userRepository = userRepository;
        this.adminService = adminService;
        this.exchangeService = exchangeService;
        this.apiKeyHasher = apiKeyHasher;
        this.publicUrlProperties = publicUrlProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        HttpSession session = request.getSession(false);
        Object pendingToken = session != null
                ? session.getAttribute(AuthentikLoginController.SESSION_KEY_PENDING_INVITE_TOKEN)
                : null;
        if (session != null) {
            session.removeAttribute(AuthentikLoginController.SESSION_KEY_PENDING_INVITE_TOKEN);
        }

        if (!(pendingToken instanceof String rawToken)) {
            AppLog.debug("Authentik login succeeded with no pending invite token - routing to the dashboard");
            response.sendRedirect(request.getContextPath() + "/dashboard");
            return;
        }

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        String displayName = oidcUser.getFullName() != null ? oidcUser.getFullName() : email;

        RedeemedInvite redeemed;
        try {
            redeemed = inviteService.redeem(rawToken, email);
        } catch (InviteNotFoundException | InviteNoLongerValidException e) {
            writeErrorPage(response, "This invite link is no longer valid: " + HtmlEscaper.escape(e.getMessage())
                    + ". Please ask for a new one.");
            return;
        } catch (InviteEmailMismatchException e) {
            writeErrorPage(response, "This invite was created for a different email address than the "
                    + "one you just logged in with (" + HtmlEscaper.escape(email) + "). Please ask for a new invite "
                    + "addressed to you, or log into Authentik with the correct account.");
            return;
        }

        Optional<UserEntity> existingUser = userRepository.findByEmail(email);
        UserEntity user = existingUser.orElseGet(() -> adminService.createUser(email, randomPlaceholderPasswordHash()));
        AppLog.info("Invite onboarding for {}: {} user {}", email, existingUser.isPresent() ? "existing" : "new", user.getId());

        adminService.grantAccess(user.getId(), redeemed.vaultId(), redeemed.role());
        String rawApiKey = adminService.createApiKey(user.getId(), "invite-" + UUID.randomUUID());
        String exchangeCode = exchangeService.create(rawApiKey, redeemed.vaultId(), displayName);

        String deepLink = DeepLinkBuilder.build(publicUrlProperties.requireUrl(), exchangeCode);
        AppLog.info("Invite onboarding complete for {} - handing back connect deep link", email);
        writeSuccessPage(response, deepLink, displayName);
    }

    /**
     * Password login is not implemented anywhere in this server (auth is exclusively via API
     * keys) - {@code users.password_hash} is NOT NULL for future use, so a random,
     * never-disclosed value is stored rather than a real credential (same approach as
     * {@code BootstrapService}).
     */
    private String randomPlaceholderPasswordHash() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return apiKeyHasher.hash(Base64.getEncoder().encodeToString(bytes));
    }

    private void writeSuccessPage(HttpServletResponse response, String deepLink, String displayName) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html><head><meta charset="utf-8"><title>StoneSync - You're in!</title></head>
                <body style="font-family: sans-serif; max-width: 40em; margin: 4em auto; text-align: center;">
                <h1>Welcome, %s!</h1>
                <p>Click below to open Obsidian - StoneSync will configure itself and download the vault automatically.</p>
                <p><a href="%s" style="display:inline-block; padding: 0.8em 1.5em; background:#5865f2; color:white; text-decoration:none; border-radius:6px; font-size:1.1em;">Open in Obsidian</a></p>
                <p style="color:#666; font-size:0.9em;">(Requires Obsidian and the StoneSync plugin to already be installed.)</p>
                </body></html>
                """.formatted(HtmlEscaper.escape(displayName), deepLink));
    }

    private void writeErrorPage(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html><head><meta charset="utf-8"><title>StoneSync - Invite problem</title></head>
                <body style="font-family: sans-serif; max-width: 40em; margin: 4em auto; text-align: center;">
                <h1>Something went wrong</h1>
                <p>%s</p>
                </body></html>
                """.formatted(message));
    }

}
