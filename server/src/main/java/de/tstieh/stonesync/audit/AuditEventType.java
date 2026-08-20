package de.tstieh.stonesync.audit;

/** What happened. Kept coarse on purpose - the detail column carries the specifics. */
public enum AuditEventType {
    ACCESS_GRANTED,
    ACCESS_ROLE_CHANGED,
    ACCESS_REVOKED,
    PATH_RULE_SET,
    PATH_RULE_REMOVED,
    SYSTEM_ROLE_CHANGED,
    INVITE_CREATED,
    INVITE_REDEEMED,
    DOCUMENT_CREATED,
    DOCUMENT_RENAMED,
    DOCUMENT_DELETED,
    DOCUMENT_EDITED,
    ATTACHMENT_UPLOADED,
    VAULT_RESTORED,
    ACCESS_DENIED
}
