package de.tstieh.stonesync.dashboard;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.UserVaultAccessEntity;
import de.tstieh.stonesync.admin.UserVaultAccessRepository;
import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.invite.HtmlEscaper;
import de.tstieh.stonesync.invite.InviteService;
import de.tstieh.stonesync.invite.PublicUrlProperties;
import de.tstieh.stonesync.logging.AppLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Browser-facing self-service landing page for people who already have a StoneSync account (see
 * {@code AuthentikLoginController}/{@code AuthentikLoginSuccessHandler} for how they get one via
 * an invite): after logging into Authentik with no pending invite token in the session, they land
 * here instead of on the invite-onboarding page. Shows every vault they're a member of, and - for
 * vaults they OWN - a form to create a new invite, so owners no longer need the {@code ss-create-
 * invite} console command for routine onboarding.
 *
 * <p>Deliberately owner-only for invite creation, matching {@link VaultAccessService}'s existing
 * rule that vault administration is never delegated by a path rule or a lesser membership role -
 * see {@link AccessLevel#allows}.</p>
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final UserRepository userRepository;
    private final VaultRepository vaultRepository;
    private final UserVaultAccessRepository accessRepository;
    private final VaultAccessService accessService;
    private final InviteService inviteService;
    private final PublicUrlProperties publicUrlProperties;

    public DashboardController(UserRepository userRepository, VaultRepository vaultRepository,
                                UserVaultAccessRepository accessRepository, VaultAccessService accessService,
                                InviteService inviteService, PublicUrlProperties publicUrlProperties) {
        this.userRepository = userRepository;
        this.vaultRepository = vaultRepository;
        this.accessRepository = accessRepository;
        this.accessService = accessService;
        this.inviteService = inviteService;
        this.publicUrlProperties = publicUrlProperties;
    }

    @GetMapping
    public void dashboard(@AuthenticationPrincipal OidcUser oidcUser, HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        String email = oidcUser.getEmail();
        Optional<UserEntity> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            AppLog.debug("Dashboard login for {} - no StoneSync account yet", email);
            writeNoAccountPage(response, email);
            return;
        }

        List<UserVaultAccessEntity> memberships = accessRepository.findByUserId(user.get().getId());
        Map<UUID, VaultEntity> vaultsById = vaultRepository
                .findAllById(memberships.stream().map(UserVaultAccessEntity::getVaultId).toList())
                .stream().collect(Collectors.toMap(VaultEntity::getId, v -> v));
        writeDashboardPage(response, request, email, memberships, vaultsById);
    }

    @PostMapping("/vaults/{vaultId}/invites")
    public void createInvite(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable UUID vaultId,
                              @RequestParam String inviteeEmail, @RequestParam VaultRole role,
                              HttpServletResponse response) throws IOException {
        UserEntity user = userRepository.findByEmail(oidcUser.getEmail())
                .orElseThrow(() -> new VaultAccessDeniedException("No StoneSync account for " + oidcUser.getEmail()));
        if (accessService.vaultLevel(user.getId(), vaultId) != AccessLevel.OWNER) {
            throw new VaultAccessDeniedException(
                    "User " + user.getId() + " is not the owner of vault " + vaultId);
        }

        String rawToken = inviteService.createInvite(vaultId, role, inviteeEmail, user.getId());
        String inviteUrl = publicUrlProperties.requireUrl() + "/invite/" + rawToken;
        AppLog.info("Dashboard: {} created a {} invite for {} on vault {}",
                oidcUser.getEmail(), role, inviteeEmail, vaultId);
        writeInviteCreatedPage(response, inviteUrl, inviteeEmail);
    }

    private void writeNoAccountPage(HttpServletResponse response, String email) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html><head><meta charset="utf-8"><title>StoneSync - No account</title></head>
                <body style="font-family: sans-serif; max-width: 40em; margin: 4em auto; text-align: center;">
                <h1>No StoneSync account</h1>
                <p>There is no StoneSync account for <strong>%s</strong> yet. Ask a vault owner for an
                invite link (<code>/invite/&lt;token&gt;</code>) to get one.</p>
                </body></html>
                """.formatted(HtmlEscaper.escape(email)));
    }

    private void writeDashboardPage(HttpServletResponse response, HttpServletRequest request, String email,
                                     List<UserVaultAccessEntity> memberships, Map<UUID, VaultEntity> vaultsById)
            throws IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        StringBuilder rows = new StringBuilder();
        memberships.stream()
                .filter(membership -> vaultsById.containsKey(membership.getVaultId()))
                .sorted(Comparator.comparing(m -> vaultsById.get(m.getVaultId()).getName()))
                .forEach(membership -> rows.append(
                        renderVaultRow(vaultsById.get(membership.getVaultId()), membership.getRole(), csrfToken)));
        if (rows.isEmpty()) {
            rows.append("<p style=\"color:#666;\">You don't have access to any vaults yet.</p>");
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html><head><meta charset="utf-8"><title>StoneSync - Your vaults</title></head>
                <body style="font-family: sans-serif; max-width: 40em; margin: 3em auto; padding: 0 1em;">
                <h1>Your vaults</h1>
                <p style="color:#666;">Signed in as %s.</p>
                %s
                </body></html>
                """.formatted(HtmlEscaper.escape(email), rows));
    }

    private String renderVaultRow(VaultEntity vault, VaultRole role, CsrfToken csrfToken) {
        String inviteForm = role == VaultRole.OWNER ? renderInviteForm(vault.getId(), csrfToken) : "";
        return """
                <div style="border:1px solid #ddd; border-radius:6px; padding:1em; margin:1em 0;">
                  <strong>%s</strong> <span style="color:#666;">(%s)</span>
                  &mdash; <a href="/dashboard/vaults/%s">Browse &amp; search</a>
                  %s
                </div>
                """.formatted(HtmlEscaper.escape(vault.getName()), role, vault.getId(), inviteForm);
    }

    private String renderInviteForm(UUID vaultId, CsrfToken csrfToken) {
        return """
                <form method="post" action="/dashboard/vaults/%s/invites"
                      style="margin-top:0.8em; display:flex; gap:0.5em; flex-wrap:wrap; align-items:center;">
                  <input type="hidden" name="%s" value="%s"/>
                  <input type="email" name="inviteeEmail" placeholder="colleague@example.com" required
                         style="flex:1; min-width:12em; padding:0.4em;"/>
                  <select name="role" style="padding:0.4em;">
                    <option value="VIEWER">Viewer</option>
                    <option value="EDITOR">Editor</option>
                    <option value="OWNER">Owner</option>
                  </select>
                  <button type="submit"
                          style="padding:0.4em 0.9em; background:#5865f2; color:white; border:none; border-radius:4px; cursor:pointer;">
                    Create invite
                  </button>
                </form>
                """.formatted(vaultId, HtmlEscaper.escape(csrfToken.getParameterName()),
                HtmlEscaper.escape(csrfToken.getToken()));
    }

    private void writeInviteCreatedPage(HttpServletResponse response, String inviteUrl, String inviteeEmail)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html><head><meta charset="utf-8"><title>StoneSync - Invite created</title></head>
                <body style="font-family: sans-serif; max-width: 40em; margin: 4em auto; text-align: center;">
                <h1>Invite created</h1>
                <p>Send this link to <strong>%s</strong>:</p>
                <p><code style="word-break: break-all;">%s</code></p>
                <p><a href="/dashboard">Back to your vaults</a></p>
                </body></html>
                """.formatted(HtmlEscaper.escape(inviteeEmail), HtmlEscaper.escape(inviteUrl)));
    }
}
