package de.tstieh.stonesync.access;

/** The distinct things a caller can want to do; checked against an {@link AccessLevel}. */
public enum Permission {
    /** See a note/attachment at all: list it, download it, open its sync socket. */
    READ,
    /** Change content: create, edit, rename, delete, upload an attachment. */
    WRITE,
    /** Add/remove collaborators, change their roles, manage path rules. */
    MANAGE_MEMBERS,
    /** Vault-wide operations: history restore, deleting the vault. */
    MANAGE_VAULT
}
