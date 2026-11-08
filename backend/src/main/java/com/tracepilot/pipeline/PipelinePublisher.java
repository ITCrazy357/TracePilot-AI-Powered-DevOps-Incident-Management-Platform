package com.tracepilot.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.tracepilot.analysis.AiProperties;

@Component
public class PipelinePublisher {
    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PipelineProperties properties;
    private final AiProperties aiProperties;

    public PipelinePublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate,
                             PipelineProperties properties, AiProperties aiProperties) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    @Scheduled(fixedDelayString = "${tracepilot.pipeline.dispatch-delay-ms:1000}")
    public void dispatch() {
        if (!properties.enabled() && !aiProperties.enabled()) {
            return;
        }
        outboxRepository.recoverExpired();
        for (int index = 0; index < properties.publishBatchSize(); index++) {
            var pending = outboxRepository.claimPublication();
            if (pending.isEmpty()) {
                return;
            }
            publish(pending.orElseThrow());
        }
    }

    private void publish(OutboxRepository.PublicationClaim claim) {
        var messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        messageProperties.setMessageId(claim.id().toString());
        var message = new Message(claim.id().toString().getBytes(StandardCharsets.UTF_8), messageProperties);
        var correlation = new CorrelationData(claim.token().toString());
        try {
            var key = switch (claim.eventType()) {
                case "INCIDENT_LOG_CAPTURE" -> PipelineQueues.LOGS_KEY;
                case "AI_ANALYSIS" -> PipelineQueues.AI_KEY;
                default -> PipelineQueues.SYNC_KEY;
            };
            if (claim.deadLetter()) {
                key = PipelineQueues.DEAD_KEY;
            }
            rabbitTemplate.send(PipelineQueues.EXCHANGE, key, message, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                outboxRepository.publicationFailed(claim);
                return;
            }
            outboxRepository.published(claim);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outboxRepository.publicationFailed(claim);
        } catch (Exception exception) {
            // Persist only a fixed code. Transport exceptions may include infrastructure details.
            outboxRepository.publicationFailed(claim);
        }
    }
}
