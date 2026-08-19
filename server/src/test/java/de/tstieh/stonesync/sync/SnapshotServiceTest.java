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
    @DisplayName("nach erfolgreichem Snapshot-Save wird das alte Update-Log geloescht")
    void logIsDeletedOnlyAfterSuccessfulSnapshotSave() {
        byte[] snapshotBytes = {9, 9, 9};

        service.replaceLogWithSnapshot(documentId, snapshotBytes);

        InOrder order = inOrder(snapshotRepository, updateRepository);
        order.verify(snapshotRepository).save(any(YjsSnapshotEntity.class));
        order.verify(updateRepository).deleteByDocumentId(documentId);
    }

    @Test
    @DisplayName("schlaegt das Speichern des Snapshots fehl, bleibt das Update-Log unangetastet")
    void logIsNotDeletedWhenSnapshotSaveFails() {
        when(snapshotRepository.save(any(YjsSnapshotEntity.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(() -> service.replaceLogWithSnapshot(documentId, new byte[]{1}))
                .isInstanceOf(RuntimeException.class);

        verify(updateRepository, never()).deleteByDocumentId(any());
    }
}
