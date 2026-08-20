package de.tstieh.stonesync.access;

import de.tstieh.stonesync.admin.VaultRole;

/**
 * What a user may actually do with a given piece of content, after vault membership *and* any
 * path rules have been taken into account (see {@code VaultAccessService}).
 *
 * <p>Deliberately separate from {@link VaultRole}: a membership is always one of OWNER/EDITOR/
 * VIEWER, whereas an effective level can also be {@link #NONE} - a path rule can take access to
 * a subtree away from someone who is otherwise a member of the vault.</p>
 */
public enum AccessLevel {
    NONE,
    VIEWER,
    EDITOR,
    OWNER;

    public boolean atLeast(AccessLevel other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * Content permissions only. Managing a vault (members, rules, restores) is never granted by
     * a path rule - it comes from the vault membership role alone, so that handing someone
     * OWNER-level editing rights on one folder can never turn them into a vault administrator.
     */
    public boolean allows(Permission permission) {
        return switch (permission) {
            case READ -> atLeast(VIEWER);
            case WRITE -> atLeast(EDITOR);
            case MANAGE_MEMBERS, MANAGE_VAULT -> this == OWNER;
        };
    }

    public static AccessLevel of(VaultRole role) {
        return switch (role) {
            case OWNER -> OWNER;
            case EDITOR -> EDITOR;
            case VIEWER -> VIEWER;
        };
    }
}
