package com.tracepilot.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.openai.errors.OpenAIServiceException;
import com.tracepilot.analysis.AnalysisFailure;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingClient {
    private final ObjectProvider<OpenAiEmbeddingModel> modelProvider;
    private final EmbeddingUsageStore usageStore;
    private final KnowledgeText text;

    public EmbeddingClient(ObjectProvider<OpenAiEmbeddingModel> modelProvider,
                           EmbeddingUsageStore usageStore, KnowledgeText text) {
        this.modelProvider = modelProvider;
        this.usageStore = usageStore;
        this.text = text;
    }

    public List<float[]> embed(UUID workspaceId, Optional<UUID> analysisId, Optional<UUID> articleId,
                               int attempt, List<String> inputs) {
        if (inputs.isEmpty() || inputs.size() > 12 || inputs.stream().anyMatch(value -> value.isBlank()
            || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8000)) {
            throw new AnalysisFailure("INVALID_EMBEDDING_INPUT", false);
        }
        var model = modelProvider.getIfAvailable();
        if (model == null) {
            throw new AnalysisFailure("RAG_DISABLED", false);
        }
        var usageId = usageStore.reserve(workspaceId, analysisId, articleId, attempt);
        long started = System.nanoTime();
        Long tokens = null;
        try {
            var response = model.call(new EmbeddingRequest(inputs, null));
            if (response == null || !RagProperties.MODEL.equals(response.getMetadata().getModel())
                || response.getResults().size() != inputs.size()) {
                throw new AnalysisFailure("INVALID_EMBEDDING_RESPONSE", true);
            }
            var usage = response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                tokens = usage.getPromptTokens().longValue();
            }
            var vectors = new ArrayList<float[]>(java.util.Collections.nCopies(inputs.size(), null));
            for (var result : response.getResults()) {
                int index = result.getIndex();
                if (index < 0 || index >= inputs.size() || vectors.get(index) != null) {
                    throw new AnalysisFailure("INVALID_EMBEDDING_RESPONSE", true);
                }
                text.vector(result.getOutput());
                vectors.set(index, result.getOutput());
            }
            if (vectors.stream().anyMatch(java.util.Objects::isNull)) {
                throw new AnalysisFailure("INVALID_EMBEDDING_RESPONSE", true);
            }
            usageStore.finish(usageId, tokens, "SUCCEEDED", null, elapsed(started));
            return List.copyOf(vectors);
        } catch (AnalysisFailure failure) {
            usageStore.finish(usageId, tokens, tokens == null ? "UNKNOWN" : "FAILED", failure.code(), elapsed(started));
            throw failure;
        } catch (OpenAIServiceException exception) {
            int code = exception.statusCode();
            usageStore.finish(usageId, tokens, "UNKNOWN", "EMBEDDING_PROVIDER_FAILED", elapsed(started));
            throw new AnalysisFailure("EMBEDDING_PROVIDER_FAILED", code == 429 || code >= 500);
        } catch (RuntimeException exception) {
            usageStore.finish(usageId, tokens, "UNKNOWN", "INVALID_EMBEDDING_RESPONSE", elapsed(started));
            throw new AnalysisFailure("INVALID_EMBEDDING_RESPONSE", true);
        }
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
