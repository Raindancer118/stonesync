package de.tstieh.stonesync.history;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface for the console's {@code ss-git-log}/{@code ss-vault-restore} commands
 * (Bearer-protected, same convention as every other {@code /api/admin/**} endpoint - see
 * {@code AdminController}, which likewise doesn't scope admin operations per-vault beyond
 * requiring a valid API key at all).
 */
@RestController
@RequestMapping("/api/admin/vaults/{vaultId}")
public class HistoryAdminController {

    private final VaultGitRepository gitRepository;
    private final RestoreService restoreService;

    public HistoryAdminController(VaultGitRepository gitRepository, RestoreService restoreService) {
        this.gitRepository = gitRepository;
        this.restoreService = restoreService;
    }

    @GetMapping("/git-log")
    public List<GitLogEntry> gitLog(@PathVariable UUID vaultId) {
        return gitRepository.log(vaultId);
    }

    @PostMapping("/restore")
    public RestoreService.RestoreResult restore(@PathVariable UUID vaultId, @Valid @RequestBody RestoreRequest request) {
        return restoreService.restore(vaultId, request.commitIsh());
    }

    public record RestoreRequest(@NotBlank String commitIsh) {
    }
}
