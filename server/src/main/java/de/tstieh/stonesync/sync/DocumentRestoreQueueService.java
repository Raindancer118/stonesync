package de.tstieh.stonesync.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentRestoreQueueService {

    private final DocumentRestoreQueueRepository repository;
    private final Clock clock;

    public DocumentRestoreQueueService(DocumentRestoreQueueRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Replaces any previously pending restore for this document with the new one. */
    @Transactional
    public void enqueue(UUID documentId, String content) {
        repository.deleteById(documentId);
        repository.save(new DocumentRestoreQueueEntity(documentId, content, clock.instant()));
    }

    /** Returns and removes the pending restore for a document, if any (single delivery). */
    @Transactional
    public Optional<String> consumePending(UUID documentId) {
        return repository.findById(documentId).map(entity -> {
            repository.deleteById(documentId);
            return entity.getContent();
        });
    }
}
