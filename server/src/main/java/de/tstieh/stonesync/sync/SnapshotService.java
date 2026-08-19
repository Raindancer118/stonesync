package de.tstieh.stonesync.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Compacts a document's append-only update log into a single snapshot.
 *
 * <p>Crash safety is the whole point here: the old log rows are only ever deleted <b>after</b>
 * the new snapshot has been durably saved. If the snapshot save throws, the log is left
 * completely untouched so no data is lost and the next reconnect can still replay it.</p>
 */
@Service
public class SnapshotService {

    private final YjsSnapshotRepository snapshotRepository;
    private final YjsUpdateRepository updateRepository;
    private final Clock clock;

    public SnapshotService(YjsSnapshotRepository snapshotRepository,
                            YjsUpdateRepository updateRepository,
                            Clock clock) {
        this.snapshotRepository = snapshotRepository;
        this.updateRepository = updateRepository;
        this.clock = clock;
    }

    @Transactional
    public void replaceLogWithSnapshot(UUID documentId, byte[] snapshotBytes) {
        // Upsert: JPA save() on an entity with the document's id as @Id performs
        // an insert-or-update, which is exactly the upsert semantics required here.
        snapshotRepository.save(new YjsSnapshotEntity(documentId, snapshotBytes, clock.instant()));
        // Only reached if the save above succeeded - crash-safe compaction.
        updateRepository.deleteByDocumentId(documentId);
    }
}
