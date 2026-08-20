package de.tstieh.stonesync.api;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.history.DocumentHistoryService;
import de.tstieh.stonesync.history.FileHistoryEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Per-note history: the git changes and the audit trail of one document. */
@RestController
@RequestMapping("/api/documents/{documentId}")
public class DocumentHistoryController {

    private final DocumentHistoryService historyService;

    public DocumentHistoryController(DocumentHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/history")
    public List<FileHistoryEntry> history(@PathVariable UUID documentId,
                                           @RequestParam(defaultValue = "50") int limit,
                                           Authentication authentication) {
        return historyService.history(userId(authentication), documentId, limit);
    }

    @GetMapping(value = "/history/{commitId}/diff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diff(@PathVariable UUID documentId, @PathVariable String commitId, Authentication authentication) {
        return historyService.diff(userId(authentication), documentId, commitId);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@PathVariable UUID documentId,
                                   @RequestParam(defaultValue = "50") int limit,
                                   Authentication authentication) {
        return historyService.auditTrail(userId(authentication), documentId, limit).stream()
                .map(entity -> new AuditEntry(entity.getOccurredAt(), entity.getType(), entity.getActorLabel(),
                        entity.getDetail()))
                .toList();
    }

    @ExceptionHandler(VaultAccessDeniedException.class)
    public ResponseEntity<Void> handleDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record AuditEntry(Instant occurredAt, AuditEventType type, String actor, String detail) {
    }
}
