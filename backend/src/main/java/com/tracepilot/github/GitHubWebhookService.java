package com.tracepilot.github;

import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GitHubWebhookService {
    private static final int MAX_SELECTION_CHANGES = 1000;
    private final WebhookDeliveryRepository deliveryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final TrackedRepositoryRepository repositoryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final GitHubWorkflowIngestion workflowIngestion;
    private final GitHubBindingAccess access;

    public GitHubWebhookService(WebhookDeliveryRepository deliveryRepository,
                                GitHubInstallationRepository installationRepository,
                                TrackedRepositoryRepository repositoryRepository,
                                ObjectMapper objectMapper, Clock clock,
                                GitHubWorkflowIngestion workflowIngestion, GitHubBindingAccess access) {
        this.deliveryRepository = deliveryRepository;
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.workflowIngestion = workflowIngestion;
        this.access = access;
    }

    @Transactional
    public GitHubDtos.WebhookResponse accept(String deliveryId, String eventType, byte[] rawBody, String payloadHash) {
        if (deliveryId == null || !deliveryId.matches("[A-Za-z0-9_-]{1,128}")
            || eventType == null || !eventType.matches("[a-z_]{1,100}")) {
            throw invalidPayload();
        }
        try {
            var payload = objectMapper.readTree(rawBody);
            if (payload == null || !payload.isObject()) {
                throw invalidPayload();
            }
            var action = payload.hasNonNull("action") ? GitHubPayloads.text(payload.path("action"), 100) : "";
            if (!deliveryRepository.begin(deliveryId, eventType, action, payloadHash)) {
                return duplicate(deliveryId, eventType, payloadHash);
            }
            var installations = findInstallations(payload);
            boolean processed = "ping".equals(eventType);
            for (var installation : installations) {
                processed = process(installation, eventType, action, payload, deliveryId) || processed;
            }
            var status = processed ? "PROCESSED" : "IGNORED";
            var singleTarget = installations.size() == 1 ? Optional.of(installations.getFirst().getId()) : Optional.<UUID>empty();
            deliveryRepository.complete(deliveryId, singleTarget, status);
            return new GitHubDtos.WebhookResponse(status, false);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    private GitHubDtos.WebhookResponse duplicate(String deliveryId, String eventType, String hash) {
        var existing = deliveryRepository.find(deliveryId);
        if (!existing.payloadHash().equals(hash) || !existing.eventType().equals(eventType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Delivery ID was already used for different content.");
        }
        return new GitHubDtos.WebhookResponse(existing.status(), true);
    }

    private List<GitHubInstallation> findInstallations(JsonNode payload) {
        if (!payload.hasNonNull("installation")) {
            return List.of();
        }
        long id = GitHubPayloads.positiveId(payload.path("installation").path("id"));
        access.lockWebhookTargets(id);
        return installationRepository.findAllByGithubInstallationIdOrderByWorkspaceIdAsc(id);
    }

    private boolean process(GitHubInstallation installation, String eventType, String action, JsonNode payload,
                            String deliveryId) {
        if ("workflow_run".equals(eventType)
            && ("requested".equals(action) || "in_progress".equals(action) || "completed".equals(action))) {
            return workflowIngestion.enqueue(installation, payload, deliveryId);
        }
        if ("installation".equals(eventType)) {
            return updateInstallation(installation, action);
        }
        if ("installation_repositories".equals(eventType) && installation.getStatus() == InstallationStatus.ACTIVE
            && ("added".equals(action) || "removed".equals(action))) {
            updateRepositorySelection(installation, action, payload);
            return true;
        }
        return false;
    }

    private boolean updateInstallation(GitHubInstallation installation, String action) {
        if (installation.getStatus() == InstallationStatus.REMOVED && !"deleted".equals(action)) {
            return false;
        }
        var status = switch (action) {
            case "deleted" -> InstallationStatus.REMOVED;
            case "suspend" -> InstallationStatus.SUSPENDED;
            case "unsuspend" -> InstallationStatus.ACTIVE;
            default -> installation.getStatus();
        };
        if (!"deleted".equals(action) && !"suspend".equals(action) && !"unsuspend".equals(action)) {
            return false;
        }
        var now = clock.instant();
        installation.update(installation.getAccountLogin(), status, now);
        // Resuming does not restore stale repository grants. An owner must synchronize again.
        for (var repository : repositoryRepository.findByInstallationId(installation.getId())) {
            repository.setActive(false, now);
        }
        return true;
    }

    private void updateRepositorySelection(GitHubInstallation installation, String action, JsonNode payload) {
        var added = payload.path("repositories_added");
        var removed = payload.path("repositories_removed");
        if (!added.isArray() || !removed.isArray()) {
            throw invalidPayload();
        }
        if (added.size() + removed.size() > MAX_SELECTION_CHANGES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                "Repository selection changes exceed the supported batch size.");
        }
        var existing = new HashMap<Long, TrackedRepository>();
        for (var repository : repositoryRepository.findByInstallationId(installation.getId())) {
            existing.put(repository.getGithubRepositoryId(), repository);
        }
        var now = clock.instant();
        installation.update(installation.getAccountLogin(), installation.getStatus(), now);
        // GitHub may omit removed IDs when changing from all repositories to selected.
        // Keep grants inactive until an explicit REST sync can establish the new selection.
        if ("removed".equals(action) && removed.isEmpty()) {
            for (var repository : existing.values()) {
                repository.setActive(false, now);
            }
            return;
        }
        for (var item : added) {
            var data = GitHubPayloads.repository(item);
            var repository = existing.get(data.id());
            if (repository == null) {
                // A GitHub-wide grant is not consent to track a new repository in every workspace.
                continue;
            } else {
                repository.update(data, true, now);
            }
        }
        for (var item : removed) {
            long id = GitHubPayloads.positiveId(item.path("id"));
            var repository = existing.get(id);
            if (repository != null) {
                repository.setActive(false, now);
            }
        }
    }

    private ResponseStatusException invalidPayload() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub webhook metadata.");
    }
}
