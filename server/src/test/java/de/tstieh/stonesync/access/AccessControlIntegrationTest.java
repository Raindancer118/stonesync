package de.tstieh.stonesync.access;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The permission model through the real HTTP stack: roles, path rules and owner self-service,
 * against a real Postgres. This is the layer where a mistake would be a data leak, so the
 * assertions are about what actually comes back over the wire, not about service calls.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlIntegrationTest {

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
    private AdminService adminService;

    private record Person(UUID id, String key) {
    }

    /** A fresh account with its own API key, optionally already a member of a vault. */
    private Person person(String email, UUID vaultId, VaultRole role) {
        Person person = person(email);
        if (vaultId != null) {
            adminService.grantAccess(person.id(), vaultId, role);
        }
        return person;
    }

    private Person person(String email) {
        UserEntity user = userRepository.save(new UserEntity(UUID.randomUUID(), email, "hash", Instant.now()));
        return new Person(user.getId(), adminService.createApiKey(user.getId(), "device"));
    }

    /** A vault plus the account that owns it - vaults.owner_id is a real foreign key. */
    private VaultEntity vaultOwnedByNewUser(String name) {
        Person nominalOwner = person("nominal-owner-" + UUID.randomUUID() + "@example.com");
        return vault(name, nominalOwner.id());
    }

    private VaultEntity vault(String name, UUID ownerId) {
        return vaultRepository.save(new VaultEntity(UUID.randomUUID(), name, ownerId, Instant.now()));
    }

    private DocumentEntity document(UUID vaultId, String path) {
        return documentRepository.save(new DocumentEntity(UUID.randomUUID(), vaultId, path,
                DocumentEntity.ContentType.TEXT, Instant.now()));
    }

    @Test
    @DisplayName("a VIEWER can read a note but is refused every write over HTTP")
    void viewerIsReadOnlyOverHttp() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("viewer-vault");
        Person viewer = person("viewer-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.VIEWER);
        DocumentEntity note = document(vault.getId(), "Notes/plan.md");

        mockMvc.perform(get("/api/documents").param("vaultId", vault.getId().toString())
                        .header("Authorization", "Bearer " + viewer.key()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/documents/resolve")
                        .header("Authorization", "Bearer " + viewer.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaultId\":\"" + vault.getId() + "\",\"path\":\"Notes/brand-new.md\",\"contentType\":\"TEXT\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/documents/{id}/path", note.getId())
                        .header("Authorization", "Bearer " + viewer.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPath\":\"Notes/renamed.md\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/documents/{id}", note.getId())
                        .header("Authorization", "Bearer " + viewer.key()))
                .andExpect(status().isForbidden());

        // Resolving an existing note must still work - a viewer has to be able to open it.
        mockMvc.perform(post("/api/documents/resolve")
                        .header("Authorization", "Bearer " + viewer.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaultId\":\"" + vault.getId() + "\",\"path\":\"Notes/plan.md\",\"contentType\":\"TEXT\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a note excluded by a path rule is neither listed nor resolvable for that member")
    void pathRuleHidesNotesCompletely() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("rules-vault");
        Person owner = person("owner-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.OWNER);
        Person member = person("member-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.EDITOR);
        document(vault.getId(), "Shared/plan.md");
        DocumentEntity secret = document(vault.getId(), "Privat/diary.md");

        mockMvc.perform(put("/api/vaults/{vaultId}/rules", vault.getId())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pathPrefix\":\"Privat\",\"userId\":null,\"level\":\"NONE\"}"))
                .andExpect(status().isOk());

        String listing = mockMvc.perform(get("/api/documents").param("vaultId", vault.getId().toString())
                        .header("Authorization", "Bearer " + member.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listing).contains("Shared/plan.md");
        assertThat(listing).doesNotContain("Privat/diary.md");
        assertThat(listing).doesNotContain(secret.getId().toString());

        mockMvc.perform(post("/api/documents/resolve")
                        .header("Authorization", "Bearer " + member.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaultId\":\"" + vault.getId() + "\",\"path\":\"Privat/diary.md\",\"contentType\":\"TEXT\"}"))
                .andExpect(status().isForbidden());

        // The owner still sees everything.
        String ownerListing = mockMvc.perform(get("/api/documents").param("vaultId", vault.getId().toString())
                        .header("Authorization", "Bearer " + owner.key()))
                .andReturn().getResponse().getContentAsString();
        assertThat(ownerListing).contains("Privat/diary.md");
    }

    @Test
    @DisplayName("only an owner may manage members - an editor is refused")
    void memberManagementIsOwnerOnly() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("members-vault");
        Person owner = person("owner2-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.OWNER);
        Person editor = person("editor-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.EDITOR);

        mockMvc.perform(get("/api/vaults/{vaultId}/members", vault.getId())
                        .header("Authorization", "Bearer " + editor.key()))
                .andExpect(status().isForbidden());

        String members = mockMvc.perform(get("/api/vaults/{vaultId}/members", vault.getId())
                        .header("Authorization", "Bearer " + owner.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(members).contains("EDITOR", "OWNER");

        mockMvc.perform(put("/api/vaults/{vaultId}/members/{memberId}", vault.getId(), editor.id())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNoContent());

        // Demoted to VIEWER, the former editor may no longer create notes.
        mockMvc.perform(post("/api/documents/resolve")
                        .header("Authorization", "Bearer " + editor.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaultId\":\"" + vault.getId() + "\",\"path\":\"new.md\",\"contentType\":\"TEXT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the permissions endpoint tells a client its own level and the rules that apply to it")
    void permissionsEndpointDrivesTheClient() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("perm-vault");
        Person owner = person("owner3-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.OWNER);
        Person member = person("member2-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.VIEWER);

        mockMvc.perform(put("/api/vaults/{vaultId}/rules", vault.getId())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pathPrefix\":\"Team\",\"userId\":\"" + member.id() + "\",\"level\":\"EDITOR\"}"))
                .andExpect(status().isOk());

        String permissions = mockMvc.perform(get("/api/vaults/{vaultId}/permissions", vault.getId())
                        .header("Authorization", "Bearer " + member.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(permissions).contains("\"vaultLevel\":\"VIEWER\"");
        assertThat(permissions).contains("\"pathPrefix\":\"Team\"", "\"level\":\"EDITOR\"");
    }

    @Test
    @DisplayName("a system admin reaches a vault they were never made a member of")
    void systemAdminReachesEveryVault() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("admin-vault");
        document(vault.getId(), "Notes/whatever.md");
        Person outsider = person("outsider-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(get("/api/documents").param("vaultId", vault.getId().toString())
                        .header("Authorization", "Bearer " + outsider.key()))
                .andExpect(status().isForbidden());

        adminService.changeSystemRole(outsider.id(), de.tstieh.stonesync.admin.SystemRole.ADMIN, null);

        mockMvc.perform(get("/api/documents").param("vaultId", vault.getId().toString())
                        .header("Authorization", "Bearer " + outsider.key()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("permission changes and content changes land in the vault's audit trail")
    void auditTrailRecordsPermissionChanges() throws Exception {
        VaultEntity vault = vaultOwnedByNewUser("audit-vault");
        Person owner = person("owner4-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.OWNER);
        Person member = person("member3-" + UUID.randomUUID() + "@example.com", vault.getId(), VaultRole.VIEWER);

        mockMvc.perform(put("/api/vaults/{vaultId}/members/{memberId}", vault.getId(), member.id())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNoContent());

        String audit = mockMvc.perform(get("/api/vaults/{vaultId}/audit", vault.getId())
                        .header("Authorization", "Bearer " + owner.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(audit).contains("ACCESS_ROLE_CHANGED", "VIEWER -> EDITOR");

        // ... and a member may not read the trail.
        mockMvc.perform(get("/api/vaults/{vaultId}/audit", vault.getId())
                        .header("Authorization", "Bearer " + member.key()))
                .andExpect(status().isForbidden());
    }
}
