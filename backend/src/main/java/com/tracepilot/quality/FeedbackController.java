package com.tracepilot.quality;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/incidents/{incidentId}/analyses/{analysisId}/feedback")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @GetMapping
    public QualityDtos.MyFeedback mine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                      @PathVariable UUID incidentId, @PathVariable UUID analysisId) {
        return service.mine(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, analysisId);
    }

    @PutMapping
    public QualityDtos.Feedback save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                    @PathVariable UUID incidentId, @PathVariable UUID analysisId,
                                    @Valid @RequestBody QualityDtos.FeedbackRequest request) {
        return service.save(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, analysisId, request);
    }
}
