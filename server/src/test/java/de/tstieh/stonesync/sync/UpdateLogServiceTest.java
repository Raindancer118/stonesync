package de.tstieh.stonesync.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateLogServiceTest {

    @Mock
    private YjsUpdateRepository repository;

    private UpdateLogService service;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new UpdateLogService(repository, new SyncProperties(200, 512_000), clock);
    }

    @Test
    @DisplayName("a document update is stored as a new row in the append log")
    void appendPersistsUpdate() {
        byte[] payload = {1, 2, 3};

        service.append(documentId, payload);

        verify(repository).save(any(YjsUpdateEntity.class));
    }

    @Test
    @DisplayName("the update count stays below the threshold as long as the log is small")
    void thresholdNotExceededWhenLogSmall() {
        when(repository.countByDocumentId(documentId)).thenReturn(5L);
        when(repository.findByDocumentIdOrderByIdAsc(documentId))
                .thenReturn(List.of(new YjsUpdateEntity(documentId, new byte[]{1}, Instant.now())));

        assertThat(service.exceedsSnapshotThreshold(documentId)).isFalse();
    }

    @Test
    @DisplayName("when the number of log entries exceeds the threshold, a snapshot becomes due")
    void thresholdExceededByCount() {
        when(repository.countByDocumentId(documentId)).thenReturn(201L);

        assertThat(service.exceedsSnapshotThreshold(documentId)).isTrue();
    }

    @Test
    @DisplayName("when the total size of the log exceeds the threshold, a snapshot becomes due")
    void thresholdExceededBySize() {
        when(repository.countByDocumentId(documentId)).thenReturn(3L);
        byte[] big = new byte[600_000];
        when(repository.findByDocumentIdOrderByIdAsc(documentId))
                .thenReturn(List.of(new YjsUpdateEntity(documentId, big, Instant.now())));

        assertThat(service.exceedsSnapshotThreshold(documentId)).isTrue();
    }
}
