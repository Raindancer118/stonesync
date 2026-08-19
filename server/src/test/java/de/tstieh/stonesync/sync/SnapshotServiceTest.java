package de.tstieh.stonesync.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    @Mock
    private YjsSnapshotRepository snapshotRepository;

    @Mock
    private YjsUpdateRepository updateRepository;

    private SnapshotService service;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new SnapshotService(snapshotRepository, updateRepository, clock);
    }

    @Test
    @DisplayName("with a previously marked watermark, the snapshot save only deletes log entries up to that watermark")
    void logIsDeletedOnlyUpToTheRecordedWatermarkAfterSuccessfulSnapshotSave() {
        byte[] snapshotBytes = {9, 9, 9};
        service.markPendingSnapshot(documentId, 42L);

        service.replaceLogWithSnapshot(documentId, snapshotBytes);

        InOrder order = inOrder(snapshotRepository, updateRepository);
        order.verify(snapshotRepository).save(any(YjsSnapshotEntity.class));
        order.verify(updateRepository).deleteByDocumentIdAndIdLessThanEqual(documentId, 42L);
        verify(updateRepository, never()).deleteByDocumentId(any());
    }

    @Test
    @DisplayName("without a marked watermark, nothing is deleted during the snapshot save (no blind discarding)")
    void doesNotDeleteAnythingWithoutARecordedWatermark() {
        service.replaceLogWithSnapshot(documentId, new byte[]{1});

        verify(updateRepository, never()).deleteByDocumentId(any());
        verify(updateRepository, never()).deleteByDocumentIdAndIdLessThanEqual(any(), any());
    }

    @Test
    @DisplayName("updates that arrive concurrently while a snapshot is being built (id > watermark) survive compaction")
    void updatesAppendedAfterTheWatermarkSurviveCompaction() {
        // Watermark was set at id=5 (state at the time of REQUEST_SNAPSHOT).
        // A concurrent update with id=6 arrived WHILE the client was building the snapshot.
        service.markPendingSnapshot(documentId, 5L);

        service.replaceLogWithSnapshot(documentId, new byte[]{1, 2, 3});

        // Only deleted up to id=5 - id=6 (the concurrently added update) is preserved.
        verify(updateRepository).deleteByDocumentIdAndIdLessThanEqual(documentId, 5L);
        verify(updateRepository, never()).deleteByDocumentId(any());
    }

    @Test
    @DisplayName("the watermark is consumed after use and does not affect the next snapshot")
    void watermarkIsConsumedAfterUse() {
        service.markPendingSnapshot(documentId, 5L);
        service.replaceLogWithSnapshot(documentId, new byte[]{1});

        service.replaceLogWithSnapshot(documentId, new byte[]{2});

        verify(updateRepository, org.mockito.Mockito.times(1)).deleteByDocumentIdAndIdLessThanEqual(documentId, 5L);
    }

    @Test
    @DisplayName("if saving the snapshot fails, the update log remains untouched")
    void logIsNotDeletedWhenSnapshotSaveFails() {
        when(snapshotRepository.save(any(YjsSnapshotEntity.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(() -> service.replaceLogWithSnapshot(documentId, new byte[]{1}))
                .isInstanceOf(RuntimeException.class);

        verify(updateRepository, never()).deleteByDocumentId(any());
    }
}
