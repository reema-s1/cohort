package dev.cohort.common;

public enum RuleType {
    /** {@code {"userIds": ["u1", "u2"]}} */
    ALLOWLIST,
    /** {@code {"attribute": "country", "values": ["IN", "US"]}} */
    ATTRIBUTE,
    /** {@code {"basisPoints": 1000}} — the first 10% of rollout buckets match. */
    PERCENT
}
