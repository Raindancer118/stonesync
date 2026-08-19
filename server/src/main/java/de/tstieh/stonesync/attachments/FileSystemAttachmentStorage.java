package de.tstieh.stonesync.attachments;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes attachment bytes to the local filesystem under the configured storage root. */
@Component
public class FileSystemAttachmentStorage {

    private final Path root;

    public FileSystemAttachmentStorage(StorageProperties properties) {
        this.root = Path.of(properties.path());
    }

    /**
     * Stores the bytes content-addressed by hash and returns the absolute storage path.
     *
     * <p>{@code contentHash} is expected to already be a verified SHA-256 hex digest (see
     * {@link AttachmentService#upload}) - this is defense-in-depth against path traversal, not
     * the primary safeguard, in case a future caller forgets to validate first.</p>
     */
    public String store(String contentHash, byte[] bytes) {
        try {
            Files.createDirectories(root);
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path target = normalizedRoot.resolve(contentHash).normalize();
            if (!target.startsWith(normalizedRoot)) {
                throw new AttachmentStorageException("Rejected attachment path outside the storage root: " + contentHash);
            }
            Files.write(target, bytes);
            return target.toString();
        } catch (IOException e) {
            throw new AttachmentStorageException("Failed to store attachment " + contentHash, e);
        }
    }
}
