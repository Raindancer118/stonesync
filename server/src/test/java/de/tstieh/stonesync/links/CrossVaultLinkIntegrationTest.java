package de.tstieh.stonesync.links;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Linking across vault boundaries, end to end: a note in one vault links into another, the link
 * resolves only for people who may follow it, backlinks are filtered the same way, and renaming
 * the target queues a repair instead of leaving a dead link behind.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CrossVaultLinkIntegrationTest {

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
        registry.add("stonesync.storage.path", () -> System.getProperty("java.io.tmpdir") + "/stonesync-link-test");
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

    private Person person(String prefix) {
        UserEntity user = userRepository.save(new UserEntity(UUID.randomUUID(),
                prefix + "-" + UUID.randomUUID() + "@example.com", "hash", Instant.now()));
        return new Person(user.getId(), adminService.createApiKey(user.getId(), "device"));
    }

    private VaultEntity vault(String name, String slug, UUID ownerId) {
        VaultEntity vault = vaultRepository.save(new VaultEntity(UUID.randomUUID(), name, ownerId, Instant.now()));
        vault.changeSlug(slug);
        return vaultRepository.save(vault);
    }

    private DocumentEntity document(UUID vaultId, String path) {
        return documentRepository.save(new DocumentEntity(UUID.randomUUID(), vaultId, path,
                DocumentEntity.ContentType.TEXT, Instant.now()));
    }

    @Test
    @DisplayName("a cross-vault link resolves for someone who may read the target, and is 'restricted' for everyone else")
    void resolveRespectsPermissions() throws Exception {
        Person insider = person("insider");
        Person outsider = person("outsider");
        VaultEntity sales = vault("Sales", "sales-" + UUID.randomUUID().toString().substring(0, 6), insider.id());
        VaultEntity engineering = vault("Engineering", null, outsider.id());
        adminService.grantAccess(insider.id(), sales.getId(), VaultRole.OWNER);
        adminService.grantAccess(insider.id(), engineering.getId(), VaultRole.EDITOR);
        adminService.grantAccess(outsider.id(), engineering.getId(), VaultRole.EDITOR);
        document(sales.getId(), "Finanzen/Jahresabschluss.md");

        String insiderResolve = mockMvc.perform(get("/api/links/resolve")
                        .param("vault", sales.getSlug()).param("path", "Finanzen/Jahresabschluss")
                        .header("Authorization", "Bearer " + insider.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(insiderResolve).contains("\"status\":\"AVAILABLE\"", "Finanzen/Jahresabschluss.md");

        String outsiderResolve = mockMvc.perform(get("/api/links/resolve")
                        .param("vault", sales.getSlug()).param("path", "Finanzen/Jahresabschluss")
                        .header("Authorization", "Bearer " + outsider.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Neither the id nor the path may leak - not even the fact that it exists.
        assertThat(outsiderResolve).contains("\"status\":\"RESTRICTED\"");
        assertThat(outsiderResolve).doesNotContain("Jahresabschluss");

        String missing = mockMvc.perform(get("/api/links/resolve")
                        .param("vault", sales.getSlug()).param("path", "Nichts/Da")
                        .header("Authorization", "Bearer " + insider.key()))
                .andReturn().getResponse().getContentAsString();
        assertThat(missing).contains("\"status\":\"NOT_FOUND\"");
    }

    @Test
    @DisplayName("only cross-vault links are indexed - plain Obsidian links never reach the server's index")
    void backlinksComeFromMaterializedContentAndAreFiltered() throws Exception {
        Person author = person("author");
        Person stranger = person("stranger");
        String slug = "hr-" + UUID.randomUUID().toString().substring(0, 6);
        VaultEntity hr = vault("HR", slug, author.id());
        VaultEntity engineering = vault("Engineering", null, author.id());
        adminService.grantAccess(author.id(), hr.getId(), VaultRole.OWNER);
        adminService.grantAccess(author.id(), engineering.getId(), VaultRole.OWNER);
        adminService.grantAccess(stranger.id(), hr.getId(), VaultRole.VIEWER);

        DocumentEntity target = document(hr.getId(), "Onboarding.md");
        DocumentEntity source = document(engineering.getId(), "Notes/Setup.md");

        mockMvc.perform(post("/api/documents/{id}/materialize", source.getId())
                        .header("Authorization", "Bearer " + author.key())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("See [[" + slug + ":Onboarding]] and also [[Some Local Note]]."))
                .andExpect(status().isNoContent());

        String backlinks = mockMvc.perform(get("/api/links/backlinks/{id}", target.getId())
                        .header("Authorization", "Bearer " + author.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(backlinks).contains("Notes/Setup.md");
        assertThat(backlinks).doesNotContain("Some Local Note");

        // The stranger may read the target but not the linking note, so they see no backlink.
        String strangerBacklinks = mockMvc.perform(get("/api/links/backlinks/{id}", target.getId())
                        .header("Authorization", "Bearer " + stranger.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(strangerBacklinks).isEqualTo("[]");
    }

    @Test
    @DisplayName("renaming a linked note queues a link repair for the linking note in the other vault")
    void renameQueuesLinkRewrites() throws Exception {
        Person author = person("renamer");
        String slug = "docs-" + UUID.randomUUID().toString().substring(0, 6);
        VaultEntity docs = vault("Docs", slug, author.id());
        VaultEntity team = vault("Team", null, author.id());
        adminService.grantAccess(author.id(), docs.getId(), VaultRole.OWNER);
        adminService.grantAccess(author.id(), team.getId(), VaultRole.OWNER);

        DocumentEntity target = document(docs.getId(), "API.md");
        DocumentEntity source = document(team.getId(), "Team/Plan.md");

        mockMvc.perform(post("/api/documents/{id}/materialize", source.getId())
                        .header("Authorization", "Bearer " + author.key())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Siehe [[" + slug + ":API|die API-Doku]]."))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/documents/{id}/path", target.getId())
                        .header("Authorization", "Bearer " + author.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPath\":\"Referenz/API v2.md\"}"))
                .andExpect(status().isNoContent());

        String rewrites = mockMvc.perform(get("/api/links/rewrites/{id}", source.getId())
                        .header("Authorization", "Bearer " + author.key()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(rewrites).contains("[[" + slug + ":API|die API-Doku]]");
        assertThat(rewrites).contains("[[" + slug + ":Referenz/API v2|die API-Doku]]");

        long rewriteId = Long.parseLong(rewrites.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/links/rewrites/{doc}/{id}/applied", source.getId(), rewriteId)
                        .header("Authorization", "Bearer " + author.key()))
                .andExpect(status().isNoContent());

        String afterApplying = mockMvc.perform(get("/api/links/rewrites/{id}", source.getId())
                        .header("Authorization", "Bearer " + author.key()))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterApplying).isEqualTo("[]");
    }

    @Test
    @DisplayName("a vault namespace must look like a namespace, and only an owner may set it")
    void slugIsValidatedAndOwnerOnly() throws Exception {
        Person owner = person("slug-owner");
        Person member = person("slug-member");
        VaultEntity vault = vault("Slugless", null, owner.id());
        adminService.grantAccess(owner.id(), vault.getId(), VaultRole.OWNER);
        adminService.grantAccess(member.id(), vault.getId(), VaultRole.EDITOR);

        mockMvc.perform(put("/api/vaults/{id}/slug", vault.getId())
                        .header("Authorization", "Bearer " + member.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"whatever\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/vaults/{id}/slug", vault.getId())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"Not A Slug\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/vaults/{id}/slug", vault.getId())
                        .header("Authorization", "Bearer " + owner.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"engineering\"}"))
                .andExpect(status().isOk());
    }
}
