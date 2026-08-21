package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.dashboard.PageShell;
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

/**
 * Runs once, right after a colleague successfully logs into Authentik via the invite flow (see
 * {@link AuthentikLoginController}): redeems the invite token stashed in the session, finds or
 * creates the corresponding StoneSync user by their verified email, and grants the invite's role
 * on its vault. Deliberately does NOT mint the device API key / deep link yet - see
 * {@link AuthentikLoginController#connectNow} for why that's a separate "I'm ready" step - it just
 * stashes the (non-secret) userId/vaultId/displayName in the session and shows install
 * instructions plus a "Connect now" link.
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
    private final ApiKeyHasher apiKeyHasher;
    private final SecureRandom random = new SecureRandom();

    public AuthentikLoginSuccessHandler(InviteService inviteService, UserRepository userRepository,
                                         AdminService adminService, ApiKeyHasher apiKeyHasher) {
        this.inviteService = inviteService;
        this.userRepository = userRepository;
        this.adminService = adminService;
        this.apiKeyHasher = apiKeyHasher;
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

        session.setAttribute(AuthentikLoginController.SESSION_KEY_READY_USER_ID, user.getId());
        session.setAttribute(AuthentikLoginController.SESSION_KEY_READY_VAULT_ID, redeemed.vaultId());
        session.setAttribute(AuthentikLoginController.SESSION_KEY_READY_DISPLAY_NAME, displayName);

        AppLog.info("Invite onboarding complete for {} - showing install instructions", email);
        writeGetReadyPage(response, displayName);
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

    private void writeGetReadyPage(HttpServletResponse response, String displayName) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(PageShell.centered("StoneSync - Almost there", """
                <h1>Welcome, %s!</h1>
                <p class="lede">You now have access to the vault. Before you connect, make sure you have:</p>
                <ol>
                  <li>
                    <strong>Obsidian</strong> - if you don't have it yet, download it from
                    <a href="https://obsidian.md/download">obsidian.md/download</a> and install it.
                  </li>
                  <li>
                    <strong>The StoneSync plugin</strong> - it isn't in Obsidian's official Community
                    Plugins store yet, so install it via <strong>BRAT</strong>:
                    <ol>
                      <li>In Obsidian: Settings &rarr; Community plugins &rarr; Browse &rarr; search for
                        "BRAT" &rarr; install and enable it.</li>
                      <li>Open BRAT's settings, click "Add Beta plugin", and paste
                        <code>Raindancer118/stonesync</code>.</li>
                      <li>Enable "StoneSync" in Community plugins once BRAT has added it.</li>
                    </ol>
                  </li>
                </ol>
                <p class="lede">Already have both? Click below - this is the step that actually connects you to the vault.</p>
                <p><a href="/invite/connect" class="btn">Connect now</a></p>
                <p class="lede" style="font-size:0.9em;">Not ready yet? No problem - leave this tab/browser open,
                install everything, then click "Connect now" whenever you are. (If you close the browser and come
                back much later and "Connect now" says your session expired, just ask whoever invited you for a
                fresh invite link - this one has already been used to log you in.)</p>
                """.formatted(HtmlEscaper.escape(displayName))));
    }

    private void writeErrorPage(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(PageShell.centered("StoneSync - Invite problem", """
                <h1>Something went wrong</h1>
                <p class="lede">%s</p>
                """.formatted(message)));
    }
}
