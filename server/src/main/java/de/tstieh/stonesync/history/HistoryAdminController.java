package de.tstieh.stonesync.history;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface for the console's {@code ss-git-log}/{@code ss-vault-restore} commands. Unlike
 * most of {@code /api/admin/**}, both operations here enforce vault-level access in
 * {@link RestoreService} (not just "any valid API key") - see its class javadoc.
 */
@RestController
@RequestMapping("/api/admin/vaults/{vaultId}")
public class HistoryAdminController {

    private final RestoreService restoreService;

    public HistoryAdminController(RestoreService restoreService) {
        this.restoreService = restoreService;
    }

    @GetMapping("/git-log")
    public List<GitLogEntry> gitLog(@PathVariable UUID vaultId, Authentication authentication) {
        return restoreService.log((UUID) authentication.getPrincipal(), vaultId);
    }

    @PostMapping("/restore")
    public RestoreService.RestoreResult restore(@PathVariable UUID vaultId, @Valid @RequestBody RestoreRequest request,
                                                  Authentication authentication) {
        return restoreService.restore((UUID) authentication.getPrincipal(), vaultId, request.commitIsh());
    }

    public record RestoreRequest(@NotBlank String commitIsh) {
    }
}
