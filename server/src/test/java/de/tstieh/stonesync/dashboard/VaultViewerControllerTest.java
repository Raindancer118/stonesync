package de.tstieh.stonesync.dashboard;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.access.VaultPathRuleEntity;
import de.tstieh.stonesync.access.VaultPathRuleRepository;
import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.attachments.AttachmentEntity;
import de.tstieh.stonesync.attachments.AttachmentRepository;
import de.tstieh.stonesync.attachments.FileSystemAttachmentStorage;
import de.tstieh.stonesync.auth.AuthentikProfileActivator;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only vault viewer (browse/note/attachment/search) through the real HTTP + security
 * stack - same Testcontainers/OIDC-fixture pattern as {@link DashboardControllerTest}.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({AuthentikProfileActivator.PROFILE, "test"})
class VaultViewerControllerTest {

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
        registry.add("stonesync.public.url", () -> "https://stonesync.test");
        registry.add("spring.security.oauth2.client.registration.authentik.client-id", () -> "test-client");
        registry.add("spring.security.oauth2.client.registration.authentik.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.registration.authentik.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.authentik.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.authentik.scope", () -> "openid,email,profile");
        registry.add("spring.security.oauth2.client.provider.authentik.authorization-uri",
                () -> "https://authentik.test/application/o/authorize/");
        registry.add("spring.security.oauth2.client.provider.authentik.token-uri",
                () -> "https://authentik.test/application/o/token/");
        registry.add("spring.security.oauth2.client.provider.authentik.user-info-uri",
                () -> "https://authentik.test/application/o/userinfo/");
        registry.add("spring.security.oauth2.client.provider.authentik.jwk-set-uri",
                () -> "https://authentik.test/application/o/stonesync/jwks/");
        registry.add("spring.security.oauth2.client.provider.authentik.user-name-attribute", () -> "email");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VaultRepository vaultRepository;
    @Autowired
    private AdminService adminService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private FileSystemAttachmentStorage storage;
    @Autowired
    private VaultPathRuleRepository pathRuleRepository;

    private UserEntity user(String email) {
        return userRepository.save(new UserEntity(UUID.randomUUID(), email, "hash", Instant.now()));
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

    private DocumentEntity attachment(UUID vaultId, String path, byte[] bytes) {
        DocumentEntity document = documentRepository.save(new DocumentEntity(UUID.randomUUID(), vaultId, path,
                DocumentEntity.ContentType.ATTACHMENT, Instant.now()));
        String storagePath = storage.store("hash-" + document.getId(), bytes);
        attachmentRepository.save(new AttachmentEntity(document.getId(), "hash-" + document.getId(), bytes.length,
                storagePath, Instant.now()));
        return document;
    }

    @Test
    @DisplayName("browsing a vault lists its notes and attachments, linking to each")
    void browseIsSearchOnlyAndNeverListsIndividualFiles() throws Exception {
        UserEntity owner = user("browse-owner-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("browse-vault", owner.getId());
        adminService.grantAccess(owner.getId(), vault.getId(), VaultRole.OWNER);
        DocumentEntity noteDoc = note(vault.getId(), "Notes/plan.md", "# Plan\nDo things.");
        DocumentEntity attachmentDoc = attachment(vault.getId(), "Assets/report.pdf", new byte[]{1, 2, 3});

        mockMvc.perform(get("/dashboard/vaults/{vaultId}", vault.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Search notes, PDFs, screenshots"),
                        not(containsString("Notes/plan.md")),
                        not(containsString("Assets/report.pdf")),
                        not(containsString("/dashboard/vaults/" + vault.getId() + "/notes/" + noteDoc.getId())),
                        not(containsString("/dashboard/vaults/" + vault.getId() + "/attachments/" + attachmentDoc.getId())))));
    }

    @Test
    @DisplayName("a note renders as HTML, and embedded script content is escaped rather than executed")
    void noteRendersMarkdownAndEscapesEmbeddedHtml() throws Exception {
        UserEntity owner = user("note-owner-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("note-vault", owner.getId());
        adminService.grantAccess(owner.getId(), vault.getId(), VaultRole.OWNER);
        DocumentEntity doc = note(vault.getId(), "Notes/x.md",
                "# Hello\n\n<script>alert('xss')</script>\n\nSome text.");

        mockMvc.perform(get("/dashboard/vaults/{vaultId}/notes/{id}", vault.getId(), doc.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("<h1>Hello</h1>"),
                        containsString("Some text."),
                        containsString("&lt;script&gt;"),
                        not(containsString("<script>alert")))));
    }

    @Test
    @DisplayName("a viewer without vault access cannot download an attachment")
    void attachmentDownloadDeniedWithoutAccess() throws Exception {
        UserEntity stranger = user("stranger-" + UUID.randomUUID() + "@example.com");
        UserEntity someoneElse = user("private-owner-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("private-vault", someoneElse.getId());
        DocumentEntity attachmentDoc = attachment(vault.getId(), "secret.pdf", new byte[]{9, 9, 9});

        mockMvc.perform(get("/dashboard/vaults/{vaultId}/attachments/{id}", vault.getId(), attachmentDoc.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", stranger.getEmail()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an authorized member downloads the exact stored bytes, served inline with the right content type")
    void attachmentDownloadServesStoredBytesInline() throws Exception {
        UserEntity owner = user("dl-owner-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("dl-vault", owner.getId());
        adminService.grantAccess(owner.getId(), vault.getId(), VaultRole.OWNER);
        byte[] bytes = "%PDF-1.4 fake pdf bytes".getBytes();
        DocumentEntity attachmentDoc = attachment(vault.getId(), "Reports/q1.pdf", bytes);

        mockMvc.perform(get("/dashboard/vaults/{vaultId}/attachments/{id}", vault.getId(), attachmentDoc.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    @DisplayName("search finds a note by content and highlights the match, but not a note hidden by a path rule")
    void searchFindsContentAndRespectsPathRules() throws Exception {
        UserEntity owner = user("search-owner-" + UUID.randomUUID() + "@example.com");
        UserEntity member = user("search-member-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("search-vault", owner.getId());
        adminService.grantAccess(owner.getId(), vault.getId(), VaultRole.OWNER);
        adminService.grantAccess(member.getId(), vault.getId(), VaultRole.EDITOR);
        note(vault.getId(), "Shared/budget.md", "The quarterly budget review is scheduled for Monday.");
        note(vault.getId(), "Private/salary.md", "Confidential budget details for salary planning.");
        pathRuleRepository.save(new VaultPathRuleEntity(UUID.randomUUID(), vault.getId(), "Private", null,
                AccessLevel.NONE, Instant.now(), owner.getId()));

        String body = mockMvc.perform(get("/dashboard/vaults/{vaultId}/search", vault.getId())
                        .param("q", "budget")
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", member.getEmail()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Shared/budget.md");
        assertThat(body).contains("<mark>budget</mark>");
        assertThat(body).doesNotContain("Private/salary.md");
    }
}
