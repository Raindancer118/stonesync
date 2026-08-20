package de.tstieh.stonesync.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentRestoreQueueServiceTest {

    @Mock
    private DocumentRestoreQueueRepository repository;

    private DocumentRestoreQueueService service;
    private final UUID documentId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DocumentRestoreQueueService(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("enqueue replaces any previously pending restore for the same document")
    void enqueueReplacesExisting() {
        service.enqueue(documentId, "new content");

        verify(repository).deleteById(documentId);
        ArgumentCaptor<DocumentRestoreQueueEntity> captor = ArgumentCaptor.forClass(DocumentRestoreQueueEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo(documentId);
        assertThat(captor.getValue().getContent()).isEqualTo("new content");
        assertThat(captor.getValue().getRequestedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("consumePending returns empty when nothing is queued")
    void consumePendingReturnsEmptyWhenNothingQueued() {
        when(repository.findById(documentId)).thenReturn(Optional.empty());

        assertThat(service.consumePending(documentId)).isEmpty();
    }

    @Test
    @DisplayName("consumePending returns the content and deletes the row (single delivery)")
    void consumePendingReturnsContentAndDeletes() {
        DocumentRestoreQueueEntity entity = new DocumentRestoreQueueEntity(documentId, "queued content", now);
        when(repository.findById(documentId)).thenReturn(Optional.of(entity));

        Optional<String> result = service.consumePending(documentId);

        assertThat(result).contains("queued content");
        verify(repository).deleteById(documentId);
    }
}
