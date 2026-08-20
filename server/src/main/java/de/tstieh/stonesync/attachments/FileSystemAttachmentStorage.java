package de.tstieh.stonesync.attachments;

import de.tstieh.stonesync.logging.AppLog;
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
                AppLog.error("Rejected attachment path outside the storage root: {}", contentHash);
                throw new AttachmentStorageException("Rejected attachment path outside the storage root: " + contentHash);
            }
            Files.write(target, bytes);
            AppLog.debug("Stored {} bytes at {}", bytes.length, target);
            return target.toString();
        } catch (IOException e) {
            AppLog.error("Failed to store attachment {}: {}", contentHash, e.getMessage());
            throw new AttachmentStorageException("Failed to store attachment " + contentHash, e);
        }
    }

    /**
     * Reads back the bytes at a previously stored path (as recorded in {@link AttachmentEntity#getStoragePath()}).
     * The path comes from our own database, not from user input, so no further traversal check is needed here -
     * the one at {@link #store} is what keeps a bad path from ever being persisted in the first place.
     */
    public byte[] load(String storagePath) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(storagePath));
            AppLog.debug("Loaded {} bytes from {}", bytes.length, storagePath);
            return bytes;
        } catch (IOException e) {
            AppLog.error("Failed to read attachment from {}: {}", storagePath, e.getMessage());
            throw new AttachmentStorageException("Failed to read attachment from " + storagePath, e);
        }
    }
}
