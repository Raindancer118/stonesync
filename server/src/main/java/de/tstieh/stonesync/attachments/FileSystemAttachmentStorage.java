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

    /** Stores the bytes content-addressed by hash and returns the absolute storage path. */
    public String store(String contentHash, byte[] bytes) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(contentHash);
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new AttachmentStorageException("Failed to store attachment " + contentHash, e);
        }
    }
}
