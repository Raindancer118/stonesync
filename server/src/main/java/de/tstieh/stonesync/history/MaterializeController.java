package de.tstieh.stonesync.history;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Receives the plugin's debounced, materialized plaintext for a document (see
 * {@code DocumentSession}'s push after ~3s of no further edits) - a pure durability/history
 * side-channel, entirely separate from the Yjs sync WebSocket.
 */
@RestController
@RequestMapping("/api/documents")
public class MaterializeController {

    private final MaterializeService materializeService;

    public MaterializeController(MaterializeService materializeService) {
        this.materializeService = materializeService;
    }

    @PostMapping(value = "/{documentId}/materialize", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> materialize(@PathVariable UUID documentId, @RequestBody String content,
                                             Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        materializeService.materialize(userId, documentId, content);
        return ResponseEntity.noContent().build();
    }
}
