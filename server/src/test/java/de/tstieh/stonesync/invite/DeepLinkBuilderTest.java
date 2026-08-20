package de.tstieh.stonesync.invite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeepLinkBuilderTest {

    @Test
    @DisplayName("builds an obsidian:// deep link with the server URL and exchange code correctly URL-encoded")
    void buildsDeepLinkWithEncodedParams() {
        String link = DeepLinkBuilder.build("https://stonesync.tstieh.de", "raw code/with+special=chars");

        assertThat(link).startsWith("obsidian://stonesync-connect?");
        assertThat(link).contains("serverUrl=https%3A%2F%2Fstonesync.tstieh.de");
        assertThat(link).contains("exchangeCode=raw+code%2Fwith%2Bspecial%3Dchars");
        assertThat(link).doesNotContain("apiKey=");
        assertThat(link).doesNotContain("vaultId=");
    }
}
