package com.tracepilot.incident;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/incidents")
public class IncidentController {
    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public IncidentDtos.IncidentPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return incidentService.list(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }

    @GetMapping("/{incidentId}")
    public IncidentDtos.IncidentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                             @PathVariable UUID incidentId) {
        return incidentService.get(UUID.fromString(jwt.getSubject()), workspaceId, incidentId);
    }

    @PatchMapping("/{incidentId}/status")
    public IncidentDtos.IncidentResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                @PathVariable UUID incidentId,
                                                @Valid @RequestBody IncidentDtos.ChangeStatusRequest request) {
        return incidentService.changeStatus(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, request);
    }

    @PatchMapping("/{incidentId}/assignee")
    public IncidentDtos.IncidentResponse assign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                @PathVariable UUID incidentId,
                                                @Valid @RequestBody IncidentDtos.AssignRequest request) {
        return incidentService.assign(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, request);
    }

    @GetMapping("/{incidentId}/logs")
    public IncidentDtos.LogsResponse logs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID incidentId) {
        return incidentService.logs(UUID.fromString(jwt.getSubject()), workspaceId, incidentId);
    }
}
