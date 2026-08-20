package de.tstieh.stonesync.admin;

/**
 * Account-wide role, orthogonal to per-vault membership: an {@link #ADMIN} may administer every
 * vault and user without being a member of any of them.
 */
public enum SystemRole {
    USER, ADMIN
}
