package dev.cohort.server.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cohort.events")
public record EventProperties(String topic, int partitions) {
}
