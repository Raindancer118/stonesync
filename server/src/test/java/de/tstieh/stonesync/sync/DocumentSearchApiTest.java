package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.access.VaultPathRuleEntity;
import de.tstieh.stonesync.access.VaultPathRuleRepository;
import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JSON search endpoint (`GET /api/documents/search`) the plugin's own quick-search and home
 * view call directly - Bearer-authenticated, same {@code DocumentSearchService}/GIN-index as the
 * HTML dashboard search, so access scoping is exercised the same way as everywhere else.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DocumentSearchApiTest {

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
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VaultRepository vaultRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private VaultPathRuleRepository pathRuleRepository;
    @Autowired
    private AdminService adminService;

    private record Person(UUID id, String key) {
    }

    private Person person(String email, UUID vaultId, VaultRole role) {
        UserEntity user = userRepository.save(new UserEntity(UUID.randomUUID(), email, "hash", Instant.now()));
        Person person = new Person(user.getId(), adminService.createApiKey(user.getId(), "device"));
        if (vaultId != null) {
            adminService.grantAccess(person.id(), vaultId, role);
        }
        return person;
    }

    private VaultEntity vault(String name, UUID ownerId) {
        return vaultRepository.save(new VaultEntity(UUID.randomUUID(), name, ownerId, Instant.now()));
    }

    private DocumentEntity note(UUID vaultId, String path, String plainText) {
        DocumentEntity document = new DocumentEntity(UUID.randomUUID(), vaultId, path,
                DocumentEntity.ContentType.TEXT, Instant.now());
        document.updatePlainText(plainText);
        return documentRepository.save(document);
    }

    @Test
    @DisplayName("finds a note by content and returns it as JSON with an HTML-highlighted snippet")
    void searchFindsNoteByContent() throws Exception {
        Person owner = person("owner-" + UUID.randomUUID() + "@example.com", null, null);
        VaultEntity vault = vault("search-api-vault", owner.id());
        adminService.grantAccess(owner.id(), vault.getId(), VaultRole.OWNER);
        DocumentEntity note = note(vault.getId(), "Notes/quarterly.md",
                "The quarterly budget review is scheduled for Monday.");

        mockMvc.perform(get("/api/documents/search")
                        .param("vaultId", vault.getId().toString())
                        .param("q", "budget")
                        .header("Authorization", "Bearer " + owner.key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(note.getId().toString()))
                .andExpect(jsonPath("$[0].path").value("Notes/quarterly.md"))
                .andExpect(jsonPath("$[0].contentType").value("TEXT"))
                .andExpect(jsonPath("$[0].snippetHtml", org.hamcrest.Matchers.containsString("<mark>budget</mark>")));
    }

    @Test
    @DisplayName("a garbled typo still finds the note - fuzzy trigram matching, not just exact stemmed words")
    void searchToleratesTypos() throws Exception {
        Person owner = person("owner-typo-" + UUID.randomUUID() + "@example.com", null, null);
        VaultEntity vault = vault("search-api-fuzzy-vault", owner.id());
        adminService.grantAccess(owner.id(), vault.getId(), VaultRole.OWNER);
        note(vault.getId(), "Notes/Monatsmeeting.md",
                "Agenda for the monthly Monatsmeeting: budget review, roadmap update, open questions.");

        mockMvc.perform(get("/api/documents/search")
                        .param("vaultId", vault.getId().toString())
                        .param("q", "Moatseting") // real-world typo: transposed/missing letters
                        .header("Authorization", "Bearer " + owner.key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].path").value("Notes/Monatsmeeting.md"));
    }

    @Test
    @DisplayName("a document hidden by a path rule never appears in another member's search results")
    void searchRespectsPathRules() throws Exception {
        Person owner = person("owner2-" + UUID.randomUUID() + "@example.com", null, null);
        VaultEntity vault = vault("search-api-rules-vault", owner.id());
        adminService.grantAccess(owner.id(), vault.getId(), VaultRole.OWNER);
        Person member = person("member-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.EDITOR);
        note(vault.getId(), "Shared/plan.md", "The budget plan is shared with the team.");
        note(vault.getId(), "Private/salary.md", "Confidential budget details for salary planning.");
        pathRuleRepository.save(new VaultPathRuleEntity(UUID.randomUUID(), vault.getId(), "Private", null,
                AccessLevel.NONE, Instant.now(), owner.id()));

        mockMvc.perform(get("/api/documents/search")
                        .param("vaultId", vault.getId().toString())
                        .param("q", "budget")
                        .header("Authorization", "Bearer " + member.key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].path").value("Shared/plan.md"));
    }

    @Test
    @DisplayName("a caller with no membership in the vault gets no results, not an error")
    void searchWithoutVaultAccessReturnsNothing() throws Exception {
        Person someoneElse = person("owner3-" + UUID.randomUUID() + "@example.com", null, null);
        VaultEntity vault = vault("search-api-private-vault", someoneElse.id());
        Person stranger = person("stranger-" + UUID.randomUUID() + "@example.com", null, null);
        note(vault.getId(), "secret.md", "budget details nobody outside should see");

        mockMvc.perform(get("/api/documents/search")
                        .param("vaultId", vault.getId().toString())
                        .param("q", "budget")
                        .header("Authorization", "Bearer " + stranger.key()))
                .andExpect(status().isForbidden());
    }
}
