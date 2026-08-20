package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.logging.AppLog;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the append-only Yjs update log per document. Updates are opaque binary blobs -
 * the server never interprets CRDT semantics, it only persists and relays them.
 */
@Service
public class UpdateLogService {

    private final YjsUpdateRepository repository;
    private final SyncProperties properties;
    private final Clock clock;

    public UpdateLogService(YjsUpdateRepository repository, SyncProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public void append(UUID documentId, byte[] updateBytes) {
        repository.save(new YjsUpdateEntity(documentId, updateBytes, clock.instant()));
        AppLog.debug("Appended a {}-byte update to document {}'s log", updateBytes.length, documentId);
    }

    /** True once the append log for a document should be compacted into a snapshot. */
    public boolean exceedsSnapshotThreshold(UUID documentId) {
        long count = repository.countByDocumentId(documentId);
        if (count > properties.snapshotThresholdCount()) {
            AppLog.debug("Document {} exceeds the snapshot count threshold ({} entries)", documentId, count);
            return true;
        }
        long totalBytes = totalSize(documentId);
        boolean exceedsBytes = totalBytes > properties.snapshotThresholdBytes();
        if (exceedsBytes) {
            AppLog.debug("Document {} exceeds the snapshot byte threshold ({} bytes)", documentId, totalBytes);
        }
        return exceedsBytes;
    }

    /**
     * The highest log entry id currently persisted for a document, i.e. a safe upper bound for
     * what a snapshot compaction is allowed to delete (see {@link SnapshotService}).
     */
    public Optional<Long> currentMaxId(UUID documentId) {
        return repository.findTopByDocumentIdOrderByIdDesc(documentId).map(YjsUpdateEntity::getId);
    }

    private long totalSize(UUID documentId) {
        List<YjsUpdateEntity> updates = repository.findByDocumentIdOrderByIdAsc(documentId);
        long total = 0;
        for (YjsUpdateEntity update : updates) {
            total += update.getUpdateBytes().length;
        }
        return total;
    }
}
