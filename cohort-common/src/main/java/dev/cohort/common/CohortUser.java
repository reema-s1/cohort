package dev.cohort.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The subject a flag is evaluated for: a stable id plus attributes for targeting rules.
 */
public record CohortUser(String id, Map<String, String> attributes) {

    public CohortUser {
        Objects.requireNonNull(id, "id");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static CohortUser of(String id) {
        return new CohortUser(id, Map.of());
    }

    public CohortUser with(String attribute, String value) {
        Map<String, String> next = new HashMap<>(attributes);
        next.put(attribute, value);
        return new CohortUser(id, next);
    }

    public String attribute(String name) {
        return attributes.get(name);
    }
}
