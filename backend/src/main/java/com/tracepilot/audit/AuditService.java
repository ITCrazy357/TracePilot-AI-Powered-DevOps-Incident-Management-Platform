package com.tracepilot.audit;

import java.util.UUID;

import com.tracepilot.infrastructure.security.WorkspaceManagerAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final WorkspaceManagerAccess access;

    public AuditService(AuditRepository repository, WorkspaceManagerAccess access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditDtos.Page list(UUID actorId, UUID workspaceId, int page, int size) {
        access.require(actorId, workspaceId);
        return repository.list(workspaceId, page, size);
    }
}
