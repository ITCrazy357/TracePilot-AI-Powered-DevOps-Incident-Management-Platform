package com.tracepilot.audit;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/audit-logs")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public AuditDtos.Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                              @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }
}
