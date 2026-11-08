package com.tracepilot.pipeline;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PipelineProperties.class)
public class PipelineConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.pipeline", name = "enabled", havingValue = "true")
    Declarables pipelineTopology() {
        var exchange = new DirectExchange(PipelineQueues.EXCHANGE, true, false);
        var syncQueue = QueueBuilder.durable(PipelineQueues.SYNC_QUEUE)
            .deadLetterExchange(PipelineQueues.EXCHANGE).deadLetterRoutingKey(PipelineQueues.DEAD_KEY).build();
        var deadQueue = QueueBuilder.durable(PipelineQueues.DEAD_QUEUE).build();
        return new Declarables(exchange, syncQueue, deadQueue,
            BindingBuilder.bind(syncQueue).to(exchange).with(PipelineQueues.SYNC_KEY),
            BindingBuilder.bind(deadQueue).to(exchange).with(PipelineQueues.DEAD_KEY));
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracepilot.pipeline", name = "enabled", havingValue = "true")
    SimpleRabbitListenerContainerFactory pipelineListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(2);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
