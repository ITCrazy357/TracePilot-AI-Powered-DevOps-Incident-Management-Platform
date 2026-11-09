package com.tracepilot.audit;

public enum AuditAction {
    WORKSPACE_CREATED("WORKSPACE"),
    MEMBER_ADDED("MEMBERSHIP"),
    MEMBER_ROLE_CHANGED("MEMBERSHIP"),
    MEMBER_REMOVED("MEMBERSHIP"),
    INCIDENT_STATUS_CHANGED("INCIDENT"),
    INCIDENT_ASSIGNED("INCIDENT"),
    KNOWLEDGE_CREATED("KNOWLEDGE"),
    KNOWLEDGE_EDITED("KNOWLEDGE"),
    KNOWLEDGE_APPROVED("KNOWLEDGE"),
    KNOWLEDGE_ARCHIVED("KNOWLEDGE"),
    ANALYSIS_REQUESTED("ANALYSIS"),
    WORKFLOW_SYNC_REQUESTED("OUTBOX"),
    FEEDBACK_SAVED("FEEDBACK"),
    OUTBOX_REPLAYED("OUTBOX");

    private final String entityType;

    AuditAction(String entityType) {
        this.entityType = entityType;
    }

    public String entityType() {
        return entityType;
    }
}
