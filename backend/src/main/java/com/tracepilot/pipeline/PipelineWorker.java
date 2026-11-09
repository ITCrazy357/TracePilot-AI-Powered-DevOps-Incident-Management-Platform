package com.tracepilot.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.tracepilot.github.GitHubWorkflowClient;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(prefix = "tracepilot.pipeline", name = "enabled", havingValue = "true")
public class PipelineWorker {
    private final OutboxRepository outboxRepository;
    private final PipelineAccess access;
    private final GitHubWorkflowClient workflowClient;
    private final PipelineSyncStore syncStore;

    public PipelineWorker(OutboxRepository outboxRepository, PipelineAccess access,
                          GitHubWorkflowClient workflowClient, PipelineSyncStore syncStore) {
        this.outboxRepository = outboxRepository;
        this.access = access;
        this.workflowClient = workflowClient;
        this.syncStore = syncStore;
    }

    @RabbitListener(queues = PipelineQueues.SYNC_QUEUE, containerFactory = "pipelineListenerContainerFactory")
    public void receive(Message message) {
        var id = eventId(message);
        var pending = outboxRepository.claimProcessing(id);
        if (pending.isEmpty()) {
            return;
        }
        var claim = pending.orElseThrow();
        try {
            var scope = access.currentScope(claim.repositoryId());
            if (scope.isEmpty()) {
                outboxRepository.complete(claim, "IGNORED");
                return;
            }
            var current = scope.orElseThrow();
            var snapshot = workflowClient.fetch(current, claim.work());
            syncStore.apply(claim, current, snapshot);
        } catch (ResponseStatusException | IllegalArgumentException exception) {
            var code = exception instanceof ResponseStatusException response && response.getStatusCode().value() == 409
                ? "SOURCE_CHANGED" : "GITHUB_SYNC_FAILED";
            // Acknowledge only after the retry/DLQ intent is committed in PostgreSQL.
            outboxRepository.processingFailed(claim, code);
        }
    }

    private UUID eventId(Message message) {
        if (message.getBody().length != 36) {
            throw new AmqpRejectAndDontRequeueException("Invalid workflow synchronization message.");
        }
        try {
            return UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid workflow synchronization message.");
        }
    }
}
