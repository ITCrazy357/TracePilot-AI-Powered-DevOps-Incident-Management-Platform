package com.tracepilot.knowledge;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge-articles/search-history")
public class SearchHistoryController {
    private final SearchHistoryService service;

    public SearchHistoryController(SearchHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public SearchHistoryRepository.Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                             @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }

    @GetMapping("/{id}")
    public SearchHistoryRepository.Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID id) {
        return service.get(UUID.fromString(jwt.getSubject()), workspaceId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID id) {
        service.delete(UUID.fromString(jwt.getSubject()), workspaceId, id);
    }
}
