package de.tstieh.stonesync.invite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeepLinkBuilderTest {

    @Test
    @DisplayName("builds an obsidian:// deep link with all four values correctly URL-encoded")
    void buildsDeepLinkWithEncodedParams() {
        UUID vaultId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        String link = DeepLinkBuilder.build("https://stonesync.tstieh.de", "raw api key/with+special=chars",
                vaultId, "Jane Doe & Co");

        assertThat(link).startsWith("obsidian://stonesync-connect?");
        assertThat(link).contains("serverUrl=https%3A%2F%2Fstonesync.tstieh.de");
        assertThat(link).contains("apiKey=raw+api+key%2Fwith%2Bspecial%3Dchars");
        assertThat(link).contains("vaultId=" + vaultId);
        assertThat(link).contains("displayName=Jane+Doe+%26+Co");
    }
}
