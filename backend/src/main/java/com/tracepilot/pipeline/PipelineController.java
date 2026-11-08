package com.tracepilot.pipeline;

import java.util.List;
import java.util.UUID;

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
@RequestMapping("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/workflow-runs")
public class PipelineController {
    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PipelineDtos.SyncResponse sync(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID repositoryId,
                                          @Valid @RequestBody PipelineDtos.SyncRequest request) {
        return pipelineService.requestSync(UUID.fromString(jwt.getSubject()), workspaceId, repositoryId, request);
    }

    @GetMapping
    public PipelineDtos.RunPage runs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                     @PathVariable UUID repositoryId,
                                     @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return pipelineService.runs(UUID.fromString(jwt.getSubject()), workspaceId, repositoryId, page, size);
    }

    @GetMapping("/sync-requests")
    public PipelineDtos.SyncPage requests(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                          @PathVariable UUID repositoryId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return pipelineService.requests(UUID.fromString(jwt.getSubject()), workspaceId, repositoryId, page, size);
    }

    @GetMapping("/{runId}")
    public PipelineDtos.RunResponse run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                        @PathVariable UUID repositoryId, @PathVariable UUID runId) {
        return pipelineService.run(UUID.fromString(jwt.getSubject()), workspaceId, repositoryId, runId);
    }

    @GetMapping("/{runId}/jobs")
    public List<PipelineDtos.JobResponse> jobs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                               @PathVariable UUID repositoryId, @PathVariable UUID runId) {
        return pipelineService.jobs(UUID.fromString(jwt.getSubject()), workspaceId, repositoryId, runId);
    }
}
