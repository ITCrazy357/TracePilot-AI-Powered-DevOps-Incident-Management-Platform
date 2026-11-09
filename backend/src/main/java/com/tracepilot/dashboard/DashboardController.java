package com.tracepilot.dashboard;

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
@RequestMapping("/api/v1/workspaces/{workspaceId}/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardDtos.Dashboard get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return service.get(UUID.fromString(jwt.getSubject()), workspaceId, days);
    }
}
