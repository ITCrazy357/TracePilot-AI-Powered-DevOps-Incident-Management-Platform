package com.tracepilot.analysis;

import java.util.List;
import java.util.UUID;
import com.tracepilot.knowledge.KnowledgeDtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/incidents/{incidentId}/analyses")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisDtos.AnalysisResponse request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                 @PathVariable UUID incidentId,
                                                 @Valid @RequestBody AnalysisDtos.RequestAnalysis request) {
        return analysisService.request(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, request.requestId());
    }

    @GetMapping
    public AnalysisDtos.AnalysisPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID incidentId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return analysisService.list(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, page, size);
    }

    @GetMapping("/{analysisId}")
    public AnalysisDtos.AnalysisResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                             @PathVariable UUID incidentId, @PathVariable UUID analysisId) {
        return analysisService.get(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, analysisId);
    }

    @GetMapping("/{analysisId}/usage")
    public List<AnalysisDtos.UsageResponse> usage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                 @PathVariable UUID incidentId, @PathVariable UUID analysisId) {
        return analysisService.usage(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, analysisId);
    }

    @GetMapping("/{analysisId}/evidence")
    public List<KnowledgeDtos.Evidence> evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                @PathVariable UUID incidentId, @PathVariable UUID analysisId) {
        return analysisService.evidence(UUID.fromString(jwt.getSubject()), workspaceId, incidentId, analysisId);
    }
}
