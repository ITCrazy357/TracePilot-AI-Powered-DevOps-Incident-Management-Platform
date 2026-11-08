package com.tracepilot.analysis;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineQueues;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
public class AnalysisWorker {
    private final OutboxRepository outboxRepository;
    private final AnalysisStore analysisStore;
    private final AnalysisClient client;
    private final AnalysisOutput output;

    public AnalysisWorker(OutboxRepository outboxRepository, AnalysisStore analysisStore,
                          AnalysisClient client, AnalysisOutput output) {
        this.outboxRepository = outboxRepository;
        this.analysisStore = analysisStore;
        this.client = client;
        this.output = output;
    }

    @RabbitListener(id = "incident-analysis", queues = PipelineQueues.AI_QUEUE,
        containerFactory = "analysisListenerContainerFactory")
    public void receive(Message message) {
        var pending = outboxRepository.claimProcessing(eventId(message), "AI_ANALYSIS");
        if (pending.isEmpty()) {
            return;
        }
        var claim = pending.orElseThrow();
        var analysisId = outboxRepository.analysisId(claim);
        Optional<AnalysisDtos.ProviderResult> result = Optional.empty();
        long started = System.nanoTime();
        try {
            var prepared = analysisStore.begin(claim, analysisId);
            if (prepared.isEmpty()) {
                return;
            }
            var providerResult = client.analyze(prepared.orElseThrow().content());
            result = Optional.of(providerResult);
            if (!"stop".equalsIgnoreCase(providerResult.finishReason())) {
                throw new AnalysisFailure("AI_REFUSED_OR_TRUNCATED", false);
            }
            var report = output.parse(providerResult.content(), prepared.orElseThrow().content());
            analysisStore.succeed(claim, analysisId, providerResult, report, elapsed(started));
        } catch (AnalysisFailure failure) {
            analysisStore.fail(claim, analysisId, failure, result, elapsed(started));
        }
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private UUID eventId(Message message) {
        try {
            if (message.getBody().length != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid analysis message.");
        }
    }
}
