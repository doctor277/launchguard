package io.github.doctor277.launchguard.events;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaMonitoringProperties.class)
public class MonitoringKafkaConfiguration {
    @Bean
    EventJson eventJson() { return new EventJson(); }

    @Bean
    KafkaAdmin.NewTopics monitoringTopics(KafkaMonitoringProperties properties) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(properties.requestsTopic()).partitions(properties.partitions()).replicas(properties.replicationFactor()).build(),
                TopicBuilder.name(properties.resultsTopic()).partitions(properties.partitions()).replicas(properties.replicationFactor()).build(),
                TopicBuilder.name(properties.requestsTopic() + ".dlt").partitions(properties.partitions()).replicas(properties.replicationFactor()).build(),
                TopicBuilder.name(properties.resultsTopic() + ".dlt").partitions(properties.partitions()).replicas(properties.replicationFactor()).build());
    }

    @Bean
    DefaultErrorHandler monitoringErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(12));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class, NullPointerException.class);
        return handler;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "launchguard.kafka", name = "health-enabled", havingValue = "true", matchIfMissing = true)
    Admin monitoringAdmin(KafkaAdmin kafkaAdmin) { return Admin.create(kafkaAdmin.getConfigurationProperties()); }

    @Bean
    @ConditionalOnProperty(prefix = "launchguard.kafka", name = "health-enabled", havingValue = "true", matchIfMissing = true)
    HealthIndicator kafkaHealthIndicator(Admin monitoringAdmin) {
        return () -> {
            try {
                monitoringAdmin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
                return Health.up().build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Health.down().build();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                return Health.down().build();
            }
        };
    }
}
