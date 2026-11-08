package com.tracepilot.analysis;

import java.util.List;

import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
public class AnalysisClient {
    private final OpenAiChatModel chatModel;

    public AnalysisClient(OpenAiChatModel incidentChatModel) {
        this.chatModel = incidentChatModel;
    }

    public AnalysisDtos.ProviderResult analyze(String safeLog) {
        try {
            var response = chatModel.call(new Prompt(List.of(new SystemMessage(AnalysisPrompt.SYSTEM),
                new UserMessage(safeLog))));
            if (response == null || response.getResults().size() != 1) {
                throw new AnalysisFailure("INVALID_AI_RESPONSE", true);
            }
            var generation = response.getResult();
            var usage = response.getMetadata().getUsage();
            boolean reported = usage != null && !(usage instanceof EmptyUsage);
            Long input = reported ? usage.getPromptTokens().longValue() : null;
            Long output = reported ? usage.getCompletionTokens().longValue() : null;
            var finish = generation.getMetadata().getFinishReason();
            if (generation.getOutput().hasToolCalls()) {
                finish = "tool_calls";
            }
            return new AnalysisDtos.ProviderResult(generation.getOutput().getText(), input, output, finish);
        } catch (AnalysisFailure exception) {
            throw exception;
        } catch (OpenAIServiceException exception) {
            int status = exception.statusCode();
            boolean retryable = status == 429 || status >= 500;
            throw new AnalysisFailure(retryable ? "AI_PROVIDER_UNAVAILABLE" : "AI_PROVIDER_REJECTED", retryable);
        } catch (RuntimeException exception) {
            // SDK parsing/transport errors may contain provider bodies or credentials. Do not retain their causes.
            throw new AnalysisFailure("AI_PROTOCOL_FAILED", true);
        }
    }
}
