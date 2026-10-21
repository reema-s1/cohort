package dev.cohort.server.flag;

/** Published inside a flag-changing transaction; listeners act on it after commit. */
public record FlagChangedEvent(String flagKey, long configVersion) {
}
