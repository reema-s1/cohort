package dev.cohort.common;

import java.util.List;

/** Body of {@code POST /sdk/v1/events}. */
public record EventBatch(List<CohortEvent> events) {

    public static final int MAX_EVENTS = 500;

    public EventBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
