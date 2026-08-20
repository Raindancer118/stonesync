package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.VaultRole;

import java.util.UUID;

/** Result of successfully redeeming a {@link VaultInviteEntity}. */
public record RedeemedInvite(UUID vaultId, VaultRole role) {
}
