package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.VaultRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin surface for creating vault invites (Bearer-protected, same convention as every other
 * {@code /api/admin/**} endpoint - see {@code AdminController}, which likewise doesn't scope
 * admin operations per-vault beyond requiring a valid API key at all).
 */
@RestController
@RequestMapping("/api/admin")
public class InviteAdminController {

    private final InviteService inviteService;
    private final PublicUrlProperties publicUrlProperties;

    public InviteAdminController(InviteService inviteService, PublicUrlProperties publicUrlProperties) {
        this.inviteService = inviteService;
        this.publicUrlProperties = publicUrlProperties;
    }

    @PostMapping("/vaults/{vaultId}/invites")
    public InviteResponse createInvite(@PathVariable UUID vaultId, @Valid @RequestBody CreateInviteRequest request,
                                        Authentication authentication) {
        UUID createdBy = (UUID) authentication.getPrincipal();
        String rawToken = inviteService.createInvite(vaultId, request.role(), request.inviteeEmail(), createdBy);
        String inviteUrl = publicUrlProperties.requireUrl() + "/invite/" + rawToken;
        return new InviteResponse(inviteUrl);
    }

    public record CreateInviteRequest(@NotNull VaultRole role, @NotBlank @Email String inviteeEmail) {
    }

    public record InviteResponse(String inviteUrl) {
    }
}
