package dev.cohort.server.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableScheduling
public class KafkaConfig {

    @Bean
    NewTopic eventsTopic(EventProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(properties.partitions()).replicas(1).build();
    }

    /**
     * If the database write fails, the whole batch is retried with backoff and its offsets stay
     * uncommitted, so a crash or outage replays events instead of losing them. Dedup makes the
     * replay harmless.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        return new DefaultErrorHandler(backOff);
    }
}
