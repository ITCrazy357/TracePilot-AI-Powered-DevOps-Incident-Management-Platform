package com.tracepilot.knowledge;

import com.openai.client.OpenAIClient;
import com.tracepilot.analysis.AiProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.rag", name = "enabled", havingValue = "true")
    OpenAiEmbeddingModel knowledgeEmbeddingModel(AiProperties ai, RagProperties rag, OpenAIClient incidentAiClient) {
        if (!ai.enabled()) {
            throw new IllegalArgumentException("RAG requires AI_ENABLED=true.");
        }
        var options = OpenAiEmbeddingOptions.builder().apiKey(ai.apiKey()).baseUrl(ai.baseUrl().toString())
            .model(rag.embeddingModel()).dimensions(rag.dimensions()).maxRetries(0).timeout(ai.timeout())
            .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT).build();
        return OpenAiEmbeddingModel.builder().openAiClient(incidentAiClient).options(options).build();
    }
}
