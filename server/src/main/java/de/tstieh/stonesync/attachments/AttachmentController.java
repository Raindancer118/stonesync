package de.tstieh.stonesync.attachments;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping("/status")
    public StatusResponse status(@RequestParam String hash) {
        return new StatusResponse(attachmentService.isKnown(hash));
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<Void> upload(@RequestParam UUID documentId,
                                        @RequestParam String hash,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant modifiedAt,
                                        @RequestParam MultipartFile file,
                                        Authentication authentication) throws IOException {
        UUID userId = (UUID) authentication.getPrincipal();
        attachmentService.upload(userId, documentId, hash, file.getBytes(), modifiedAt);
        return ResponseEntity.ok().build();
    }

    public record StatusResponse(boolean known) {
    }
}
