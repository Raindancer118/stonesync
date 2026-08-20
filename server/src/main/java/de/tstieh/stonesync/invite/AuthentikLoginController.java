package de.tstieh.stonesync.invite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Entry point for the collaborator-invite flow: a colleague opens
 * {@code https://.../invite/{token}} in any browser (no Obsidian/plugin state needed yet),
 * gets sent through an Authentik login, and - on success - {@link AuthentikLoginSuccessHandler}
 * redeems the token and hands back an {@code obsidian://} deep link with everything the plugin
 * needs to auto-configure itself.
 *
 * <p>The token is deliberately NOT validated here - only stashed in the session for the success
 * handler to redeem after a real login. Pre-validating here would duplicate
 * {@link InviteService#redeem}'s validity checks in two places; an invalid/expired token simply
 * surfaces as a clear error page after login instead of before it.</p>
 */
@RestController
public class AuthentikLoginController {

    static final String SESSION_KEY_PENDING_INVITE_TOKEN = "stonesync.invite.pendingToken";

    @GetMapping("/invite/{token}")
    public void startInviteLogin(@PathVariable String token, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY_PENDING_INVITE_TOKEN, token);
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/authentik");
    }
}
