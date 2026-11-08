package com.tracepilot.analysis;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import com.tracepilot.pipeline.PipelineQueues;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
    Declarables analysisTopology() {
        var exchange = new DirectExchange(PipelineQueues.EXCHANGE, true, false);
        var queue = QueueBuilder.durable(PipelineQueues.AI_QUEUE).deadLetterExchange(PipelineQueues.EXCHANGE)
            .deadLetterRoutingKey(PipelineQueues.DEAD_KEY).build();
        var dead = QueueBuilder.durable(PipelineQueues.DEAD_QUEUE).build();
        return new Declarables(exchange, queue, dead,
            BindingBuilder.bind(queue).to(exchange).with(PipelineQueues.AI_KEY),
            BindingBuilder.bind(dead).to(exchange).with(PipelineQueues.DEAD_KEY));
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
    SimpleRabbitListenerContainerFactory analysisListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
    AiHttpClient aiHttpClient(AiProperties properties) {
        return new AiHttpClient(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
    OpenAIClient incidentAiClient(AiProperties properties, AiHttpClient aiHttpClient) {
        var options = ClientOptions.builder().apiKey(properties.apiKey()).baseUrl(properties.baseUrl().toString())
            .maxRetries(0).logLevel(LogLevel.OFF).timeout(properties.timeout()).httpClient(aiHttpClient).build();
        return new OpenAIClientImpl(options);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.ai", name = "enabled", havingValue = "true")
    OpenAiChatModel incidentChatModel(AiProperties properties, OpenAIClient incidentAiClient) {
        var format = OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(AnalysisPrompt.SCHEMA).strict(true).build();
        var options = OpenAiChatOptions.builder().apiKey(properties.apiKey()).baseUrl(properties.baseUrl().toString())
            .model(properties.model()).timeout(properties.timeout()).maxCompletionTokens(properties.maxOutputTokens())
            .temperature(null).responseFormat(format).toolChoice("none").parallelToolCalls(false)
            .store(false).n(1).maxRetries(0).build();
        return OpenAiChatModel.builder().openAiClient(incidentAiClient).openAiClientAsync(incidentAiClient.async())
            .options(options).build();
    }
}
