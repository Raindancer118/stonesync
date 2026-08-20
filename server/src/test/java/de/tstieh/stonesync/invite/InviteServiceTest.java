package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.auth.ApiKeyHasher;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private VaultInviteRepository repository;

    private final ApiKeyHasher hasher = new ApiKeyHasher();
    private InviteService service;
    private final UUID vaultId = UUID.randomUUID();
    private final UUID createdBy = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private static final String INVITEE_EMAIL = "colleague@example.com";

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new InviteService(repository, hasher, clock);
    }

    @Test
    @DisplayName("creating an invite stores only the hashed token, the normalized invitee email, and expires 7 days later")
    void createInviteStoresHashedTokenNotRawValue() {
        String rawToken = service.createInvite(vaultId, VaultRole.EDITOR, "  Colleague@Example.com  ", createdBy);

        ArgumentCaptor<VaultInviteEntity> captor = ArgumentCaptor.forClass(VaultInviteEntity.class);
        verify(repository).save(captor.capture());

        VaultInviteEntity saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo(hasher.hash(rawToken));
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getInviteeEmail()).isEqualTo(INVITEE_EMAIL);
        assertThat(saved.getVaultId()).isEqualTo(vaultId);
        assertThat(saved.getRole()).isEqualTo(VaultRole.EDITOR);
        assertThat(saved.getCreatedBy()).isEqualTo(createdBy);
        assertThat(saved.getExpiresAt()).isEqualTo(now.plusSeconds(7 * 24 * 3600));
    }

    @Test
    @DisplayName("redeeming an unknown token fails")
    void redeemUnknownTokenThrows() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem("not-a-real-token", INVITEE_EMAIL))
                .isInstanceOf(InviteNotFoundException.class);
    }

    @Test
    @DisplayName("redeeming an expired token fails, even if never consumed before")
    void redeemExpiredTokenThrows() {
        String rawToken = "some-token";
        VaultInviteEntity invite = new VaultInviteEntity(UUID.randomUUID(), vaultId, VaultRole.VIEWER,
                hasher.hash(rawToken), INVITEE_EMAIL, createdBy, now.minusSeconds(8 * 24 * 3600), now.minusSeconds(3600));
        when(repository.findByTokenHash(hasher.hash(rawToken))).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> service.redeem(rawToken, INVITEE_EMAIL))
                .isInstanceOf(InviteNoLongerValidException.class);
    }

    @Test
    @DisplayName("redeeming an already-consumed token fails (single-use enforcement)")
    void redeemAlreadyConsumedTokenThrows() {
        String rawToken = "some-token";
        VaultInviteEntity invite = new VaultInviteEntity(UUID.randomUUID(), vaultId, VaultRole.VIEWER,
                hasher.hash(rawToken), INVITEE_EMAIL, createdBy, now.minusSeconds(3600), now.plusSeconds(3600));
        invite.markConsumed(now.minusSeconds(60));
        when(repository.findByTokenHash(hasher.hash(rawToken))).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> service.redeem(rawToken, INVITEE_EMAIL))
                .isInstanceOf(InviteNoLongerValidException.class);
    }

    @Test
    @DisplayName("redeeming a valid token with a different email fails and does NOT consume the invite")
    void redeemWithMismatchedEmailThrowsAndLeavesInviteUsable() {
        String rawToken = "some-token";
        VaultInviteEntity invite = new VaultInviteEntity(UUID.randomUUID(), vaultId, VaultRole.EDITOR,
                hasher.hash(rawToken), INVITEE_EMAIL, createdBy, now.minusSeconds(3600), now.plusSeconds(3600));
        when(repository.findByTokenHash(hasher.hash(rawToken))).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> service.redeem(rawToken, "attacker@evil.com"))
                .isInstanceOf(InviteEmailMismatchException.class);

        assertThat(invite.isConsumed()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("redeeming with an email that only differs by case/whitespace still succeeds")
    void redeemWithDifferentlyCasedEmailSucceeds() {
        String rawToken = "some-token";
        VaultInviteEntity invite = new VaultInviteEntity(UUID.randomUUID(), vaultId, VaultRole.EDITOR,
                hasher.hash(rawToken), INVITEE_EMAIL, createdBy, now.minusSeconds(3600), now.plusSeconds(3600));
        when(repository.findByTokenHash(hasher.hash(rawToken))).thenReturn(Optional.of(invite));

        RedeemedInvite redeemed = service.redeem(rawToken, "  Colleague@Example.com  ");

        assertThat(redeemed.vaultId()).isEqualTo(vaultId);
        assertThat(invite.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("redeeming a valid, unexpired, unconsumed token with the matching email succeeds and marks it consumed")
    void redeemValidTokenSucceedsAndMarksConsumed() {
        String rawToken = "some-token";
        VaultInviteEntity invite = new VaultInviteEntity(UUID.randomUUID(), vaultId, VaultRole.EDITOR,
                hasher.hash(rawToken), INVITEE_EMAIL, createdBy, now.minusSeconds(3600), now.plusSeconds(3600));
        when(repository.findByTokenHash(hasher.hash(rawToken))).thenReturn(Optional.of(invite));

        RedeemedInvite redeemed = service.redeem(rawToken, INVITEE_EMAIL);

        assertThat(redeemed.vaultId()).isEqualTo(vaultId);
        assertThat(redeemed.role()).isEqualTo(VaultRole.EDITOR);
        assertThat(invite.isConsumed()).isTrue();
        assertThat(invite.getConsumedAt()).isEqualTo(now);
        verify(repository).save(invite);
    }
}
