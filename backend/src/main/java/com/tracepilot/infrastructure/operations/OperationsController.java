package com.tracepilot.infrastructure.operations;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/operations")
public class OperationsController {
    private final OperationsService service;

    public OperationsController(OperationsService service) {
        this.service = service;
    }

    @GetMapping
    public List<OperationsDtos.Backlog> backlog(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return service.backlog(UUID.fromString(jwt.getSubject()), workspaceId);
    }

    @GetMapping("/outbox")
    public OperationsDtos.EventPage events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.events(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }

    @PostMapping("/outbox/{eventId}/retry")
    public OperationsDtos.Event retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                     @PathVariable UUID eventId,
                                     @Valid @RequestBody OperationsDtos.RetryRequest request) {
        return service.retry(UUID.fromString(jwt.getSubject()), workspaceId, eventId, request.replayCount());
    }
}
