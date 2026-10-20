package dev.cohort.common;

import java.util.Map;

/**
 * An immutable snapshot of every live flag at one global config version.
 */
public record FlagConfig(long version, Map<String, FlagDefinition> flags) {

    public static final FlagConfig EMPTY = new FlagConfig(0, Map.of());

    public FlagConfig {
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    public FlagDefinition flag(String key) {
        return flags.get(key);
    }
}
