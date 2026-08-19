package de.tstieh.stonesync.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compacts a document's append-only update log into a single snapshot.
 *
 * <p>Crash safety: the old log rows are only ever deleted <b>after</b> the new snapshot has
 * been durably saved. If the snapshot save throws, the log is left completely untouched so no
 * data is lost and the next reconnect can still replay it.</p>
 *
 * <p>Data-loss safety: a client only starts building its {@code SNAPSHOT_PAYLOAD} reply after
 * receiving {@code REQUEST_SNAPSHOT}, but other clients can keep sending {@code DOC_UPDATE}s in
 * the meantime - those get appended to the log while the snapshot is in flight. Deleting the
 * *entire* log on save would silently discard those concurrent updates for anyone who later
 * joins and only replays snapshot+log. To prevent this, {@link #markPendingSnapshot} records the
 * log's highest id (the "watermark") at the moment the request is sent, and
 * {@link #replaceLogWithSnapshot} only ever deletes entries up to that watermark - anything
 * appended after it survives and is picked up by the next compaction round.</p>
 */
@Service
public class SnapshotService {

    private final YjsSnapshotRepository snapshotRepository;
    private final YjsUpdateRepository updateRepository;
    private final Clock clock;
    private final Map<UUID, Long> pendingWatermarks = new ConcurrentHashMap<>();

    public SnapshotService(YjsSnapshotRepository snapshotRepository,
                            YjsUpdateRepository updateRepository,
                            Clock clock) {
        this.snapshotRepository = snapshotRepository;
        this.updateRepository = updateRepository;
        this.clock = clock;
    }

    /** Records the log's current high-water mark right before a {@code REQUEST_SNAPSHOT} is sent. */
    public void markPendingSnapshot(UUID documentId, long maxUpdateId) {
        pendingWatermarks.put(documentId, maxUpdateId);
    }

    @Transactional
    public void replaceLogWithSnapshot(UUID documentId, byte[] snapshotBytes) {
        // Upsert: JPA save() on an entity with the document's id as @Id performs
        // an insert-or-update, which is exactly the upsert semantics required here.
        snapshotRepository.save(new YjsSnapshotEntity(documentId, snapshotBytes, clock.instant()));
        // Only reached if the save above succeeded - crash-safe compaction.
        Long watermark = pendingWatermarks.remove(documentId);
        if (watermark != null) {
            updateRepository.deleteByDocumentIdAndIdLessThanEqual(documentId, watermark);
        }
        // No watermark recorded (e.g. an unsolicited snapshot payload): skip deletion rather
        // than guessing - a future compaction round will clean up once a watermark exists.
    }
}
