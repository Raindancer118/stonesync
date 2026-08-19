package de.tstieh.stonesync;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import de.tstieh.stonesync.sync.SnapshotService;
import de.tstieh.stonesync.sync.UpdateLogService;
import de.tstieh.stonesync.sync.YjsSnapshotRepository;
import de.tstieh.stonesync.sync.YjsUpdateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification against a real Postgres instance (Testcontainers): the Flyway
 * migration applies cleanly and the append-log -&gt; snapshot compaction flow actually
 * persists data correctly through the real JPA/Postgres stack (bytea columns etc.), not
 * just against mocks.
 */
@Testcontainers
@SpringBootTest
class StoneSyncIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stonesync")
            .withUsername("stonesync")
            .withPassword("stonesync");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VaultRepository vaultRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private UpdateLogService updateLogService;
    @Autowired
    private SnapshotService snapshotService;
    @Autowired
    private YjsUpdateRepository yjsUpdateRepository;
    @Autowired
    private YjsSnapshotRepository yjsSnapshotRepository;

    @Test
    void contextLoadsAndFlywayMigrationApplied() {
        assertThat(userRepository).isNotNull();
    }

    @Test
    void fullPersistenceRoundTripThroughRealPostgres() {
        Instant now = Instant.now();
        UserEntity user = userRepository.save(new UserEntity(UUID.randomUUID(), "test@example.com", "hash", now));
        VaultEntity vault = vaultRepository.save(new VaultEntity(UUID.randomUUID(), "my-vault", user.getId(), now));
        DocumentEntity document = documentRepository.save(new DocumentEntity(
                UUID.randomUUID(), vault.getId(), "notes/a.md", DocumentEntity.ContentType.TEXT, now));

        updateLogService.append(document.getId(), new byte[]{1, 2, 3});
        updateLogService.append(document.getId(), new byte[]{4, 5, 6});

        assertThat(yjsUpdateRepository.countByDocumentId(document.getId())).isEqualTo(2);

        snapshotService.replaceLogWithSnapshot(document.getId(), new byte[]{9, 9, 9, 9});

        assertThat(yjsUpdateRepository.countByDocumentId(document.getId())).isZero();
        assertThat(yjsSnapshotRepository.findById(document.getId())).isPresent();
        assertThat(yjsSnapshotRepository.findById(document.getId()).get().getStateBytes())
                .containsExactly(9, 9, 9, 9);
    }
}
