package dev.cohort.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cohort.security")
public record SecurityProperties(String adminKey, String sdkKey) {
}
