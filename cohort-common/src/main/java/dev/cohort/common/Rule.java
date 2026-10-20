package dev.cohort.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A targeting rule. Rules are evaluated in ascending priority; the first match wins.
 *
 * @param variant the variant to serve on a match, or {@code null} to serve the weighted split
 *                (which puts the user into the experiment)
 */
public record Rule(int priority, RuleType type, Map<String, Object> condition, String variant) {

    public Rule {
        // Not Map.copyOf: JSON conditions may legitimately contain null values.
        condition = condition == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(condition));
    }

    public static Rule allowlist(int priority, List<String> userIds, String variant) {
        return new Rule(priority, RuleType.ALLOWLIST, Map.of("userIds", userIds), variant);
    }

    public static Rule attribute(int priority, String attribute, List<String> values, String variant) {
        return new Rule(priority, RuleType.ATTRIBUTE, Map.of("attribute", attribute, "values", values), variant);
    }

    public static Rule percent(int priority, int basisPoints, String variant) {
        return new Rule(priority, RuleType.PERCENT, Map.of("basisPoints", basisPoints), variant);
    }
}
