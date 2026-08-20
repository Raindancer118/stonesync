package de.tstieh.stonesync.api;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.access.VaultAccessAdminService;
import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.audit.AuditEventEntity;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.invite.InviteService;
import de.tstieh.stonesync.invite.PublicUrlProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Permission management for the people who actually own a vault, without the server-wide admin
 * key: everything here is authorized by the caller's own role on that vault (see
 * {@link VaultAccessAdminService}), which is what makes StoneSync usable by a team whose members
 * were never given server administration rights.
 */
@RestController
@RequestMapping("/api/vaults/{vaultId}")
public class VaultAccessController {

    private final VaultAccessAdminService accessAdminService;
    private final VaultAccessService vaultAccessService;
    private final AuditService auditService;
    private final InviteService inviteService;
    private final PublicUrlProperties publicUrlProperties;

    public VaultAccessController(VaultAccessAdminService accessAdminService, VaultAccessService vaultAccessService,
                                  AuditService auditService, InviteService inviteService,
                                  PublicUrlProperties publicUrlProperties) {
        this.accessAdminService = accessAdminService;
        this.vaultAccessService = vaultAccessService;
        this.auditService = auditService;
        this.inviteService = inviteService;
        this.publicUrlProperties = publicUrlProperties;
    }

    /** What the caller themselves may do here - polled by the plugin to switch the editor to read-only. */
    @GetMapping("/permissions")
    public VaultAccessAdminService.MyPermissions myPermissions(@PathVariable UUID vaultId,
                                                                Authentication authentication) {
        return accessAdminService.permissionsOf(userId(authentication), vaultId);
    }

    /** The vault's link namespace - what other vaults write as [[slug:Note]]. */
    @GetMapping("/slug")
    public SlugResponse slug(@PathVariable UUID vaultId, Authentication authentication) {
        return new SlugResponse(accessAdminService.slugOf(userId(authentication), vaultId));
    }

    @PutMapping("/slug")
    public SlugResponse setSlug(@PathVariable UUID vaultId, @RequestBody SlugRequest request,
                                 Authentication authentication) {
        return new SlugResponse(accessAdminService.setSlug(userId(authentication), vaultId, request.slug()));
    }

    /** Who may do what with one specific note or folder - drives the per-file access dialog. */
    @GetMapping("/access")
    public VaultAccessAdminService.PathAccess accessForPath(@PathVariable UUID vaultId, @RequestParam String path,
                                                             Authentication authentication) {
        return accessAdminService.accessFor(userId(authentication), vaultId, path);
    }

    @GetMapping("/members")
    public List<VaultAccessAdminService.Member> members(@PathVariable UUID vaultId, Authentication authentication) {
        return accessAdminService.listMembers(userId(authentication), vaultId);
    }

    @PutMapping("/members/{memberId}")
    public ResponseEntity<Void> setMemberRole(@PathVariable UUID vaultId, @PathVariable UUID memberId,
                                               @Valid @RequestBody SetRoleRequest request,
                                               Authentication authentication) {
        accessAdminService.setMemberRole(userId(authentication), vaultId, memberId, request.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID vaultId, @PathVariable UUID memberId,
                                              Authentication authentication) {
        accessAdminService.removeMember(userId(authentication), vaultId, memberId);
        return ResponseEntity.noContent().build();
    }

    /** Invites a colleague by email; the returned link carries them through login and auto-setup. */
    @PostMapping("/invites")
    public InviteResponse invite(@PathVariable UUID vaultId, @Valid @RequestBody InviteRequest request,
                                  Authentication authentication) {
        UUID actorId = userId(authentication);
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        String rawToken = inviteService.createInvite(vaultId, request.role(), request.email(), actorId);
        auditService.recordAccessChange(AuditEventType.INVITE_CREATED, actorId, vaultId, null,
                request.email() + " as " + request.role());
        return new InviteResponse(publicUrlProperties.requireUrl() + "/invite/" + rawToken);
    }

    @GetMapping("/rules")
    public List<VaultAccessAdminService.Rule> rules(@PathVariable UUID vaultId, Authentication authentication) {
        return accessAdminService.listRules(userId(authentication), vaultId);
    }

    @PutMapping("/rules")
    public VaultAccessAdminService.Rule setRule(@PathVariable UUID vaultId, @Valid @RequestBody SetRuleRequest request,
                                                 Authentication authentication) {
        return accessAdminService.setRule(userId(authentication), vaultId, request.pathPrefix(), request.userId(),
                request.level());
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> removeRule(@PathVariable UUID vaultId, @PathVariable UUID ruleId,
                                            Authentication authentication) {
        accessAdminService.removeRule(userId(authentication), vaultId, ruleId);
        return ResponseEntity.noContent().build();
    }

    /** The vault's audit trail - owner material, since it spans notes the reader may not see. */
    @GetMapping("/audit")
    public List<AuditEntry> audit(@PathVariable UUID vaultId,
                                   @RequestParam(required = false) AuditEventType type,
                                   @RequestParam(defaultValue = "100") int limit,
                                   Authentication authentication) {
        vaultAccessService.requireVaultPermission(userId(authentication), vaultId, Permission.MANAGE_VAULT);
        return auditService.recentForVault(vaultId, type, limit).stream().map(AuditEntry::of).toList();
    }

    @ExceptionHandler(VaultAccessDeniedException.class)
    public ResponseEntity<Void> handleDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record SlugRequest(String slug) {
    }

    public record SlugResponse(String slug) {
    }

    public record SetRoleRequest(@NotNull VaultRole role) {
    }

    public record InviteRequest(@NotNull VaultRole role, @NotBlank @Email String email) {
    }

    public record InviteResponse(String inviteUrl) {
    }

    /** {@code userId} null = the rule applies to everyone with access to the vault. */
    public record SetRuleRequest(@NotNull String pathPrefix, UUID userId, @NotNull AccessLevel level) {
    }

    public record AuditEntry(Long id, Instant occurredAt, AuditEventType type, String actor, UUID subjectId,
                              String path, UUID documentId, String detail) {
        static AuditEntry of(AuditEventEntity entity) {
            return new AuditEntry(entity.getId(), entity.getOccurredAt(), entity.getType(), entity.getActorLabel(),
                    entity.getSubjectId(), entity.getPath(), entity.getDocumentId(), entity.getDetail());
        }
    }
}
