package com.tracepilot.incident;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.tracepilot.github.GitHubJobLogClient;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineAccess;
import com.tracepilot.pipeline.PipelineQueues;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(prefix = "tracepilot.pipeline", name = "enabled", havingValue = "true")
public class IncidentLogWorker {
    private final OutboxRepository outboxRepository;
    private final PipelineAccess access;
    private final IncidentRepository incidentRepository;
    private final GitHubJobLogClient logClient;
    private final IncidentLogStore logStore;

    public IncidentLogWorker(OutboxRepository outboxRepository, PipelineAccess access,
                             IncidentRepository incidentRepository, GitHubJobLogClient logClient,
                             IncidentLogStore logStore) {
        this.outboxRepository = outboxRepository;
        this.access = access;
        this.incidentRepository = incidentRepository;
        this.logClient = logClient;
        this.logStore = logStore;
    }

    @RabbitListener(queues = PipelineQueues.LOGS_QUEUE, containerFactory = "pipelineListenerContainerFactory")
    public void receive(Message message) {
        var pending = outboxRepository.claimProcessing(eventId(message), "INCIDENT_LOG_CAPTURE");
        if (pending.isEmpty()) {
            return;
        }
        var claim = pending.orElseThrow();
        try {
            var scope = access.currentScope(claim.repositoryId());
            var incidentId = outboxRepository.logIncidentId(claim);
            var source = incidentRepository.logSource(incidentId);
            if (scope.isEmpty() || source.isEmpty()) {
                outboxRepository.complete(claim, "IGNORED");
                return;
            }
            var current = scope.orElseThrow();
            var logSource = source.orElseThrow();
            if (!logSource.repositoryId().equals(claim.repositoryId())
                || !logSource.workspaceId().equals(current.workspaceId())) {
                throw new IllegalArgumentException("Invalid incident log scope.");
            }
            var redacted = logClient.fetchRedacted(current, logSource.githubJobId());
            logStore.save(claim, current, logSource, redacted);
        } catch (ResponseStatusException | IllegalArgumentException exception) {
            outboxRepository.processingFailed(claim, "GITHUB_LOG_CAPTURE_FAILED");
        }
    }

    private UUID eventId(Message message) {
        try {
            if (message.getBody().length != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid incident log capture message.");
        }
    }
}
