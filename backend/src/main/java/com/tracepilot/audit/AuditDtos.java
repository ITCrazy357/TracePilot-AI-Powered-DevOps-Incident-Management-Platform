package com.tracepilot.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public final class AuditDtos {
    private AuditDtos() {
    }

    public record Entry(UUID id, UUID actorUserId, String action, String entityType, UUID entityId,
                        JsonNode metadata, UUID requestId, Instant createdAt) {
    }

    public record Page(List<Entry> items, int page, int size, long total) {
    }
}
