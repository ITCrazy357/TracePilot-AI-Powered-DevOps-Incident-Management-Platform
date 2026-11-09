package com.tracepilot.knowledge;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge-articles")
public class KnowledgeController {
    private final KnowledgeService service;
    private final KnowledgePublication publication;
    private final KnowledgeSearch search;

    public KnowledgeController(KnowledgeService service, KnowledgePublication publication, KnowledgeSearch search) {
        this.service = service;
        this.publication = publication;
        this.search = search;
    }

    @GetMapping
    public KnowledgeDtos.ArticlePage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                          @Valid @ModelAttribute KnowledgeFilters filters) {
        return service.list(UUID.fromString(jwt.getSubject()), workspaceId, page, size, filters);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDtos.Article create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                         @Valid @RequestBody KnowledgeDtos.CreateRequest request) {
        return service.create(UUID.fromString(jwt.getSubject()), workspaceId, request);
    }

    @GetMapping("/{articleId}")
    public KnowledgeDtos.Article get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                      @PathVariable UUID articleId) {
        return service.get(UUID.fromString(jwt.getSubject()), workspaceId, articleId);
    }

    @PatchMapping("/{articleId}")
    public KnowledgeDtos.Article edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                       @PathVariable UUID articleId, @Valid @RequestBody KnowledgeDtos.EditRequest request) {
        return service.edit(UUID.fromString(jwt.getSubject()), workspaceId, articleId, request);
    }

    @PostMapping("/{articleId}/approve")
    public KnowledgeDtos.Article approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID articleId,
                                          @Valid @RequestBody KnowledgeDtos.VersionRequest request) {
        return publication.approve(UUID.fromString(jwt.getSubject()), workspaceId, articleId, request.version());
    }

    @PostMapping("/{articleId}/archive")
    public KnowledgeDtos.Article archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID articleId,
                                          @Valid @RequestBody KnowledgeDtos.VersionRequest request) {
        return service.archive(UUID.fromString(jwt.getSubject()), workspaceId, articleId, request.version());
    }

    @PostMapping("/search")
    public KnowledgeDtos.SearchResponse search(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                               @Valid @RequestBody KnowledgeDtos.SearchRequest request) {
        return search.search(UUID.fromString(jwt.getSubject()), workspaceId, request);
    }
}
