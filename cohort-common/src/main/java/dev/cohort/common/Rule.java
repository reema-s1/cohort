package dev.cohort.common;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A targeting rule. Rules are evaluated in ascending priority; the first match wins.
 *
 * @param variant the variant to serve on a match, or {@code null} to serve the weighted split
 *                (which puts the user into the experiment)
 */
public record Rule(int priority, RuleType type, Map<String, Object> condition, String variant) {

    public Rule {
        // Not Map.copyOf: JSON conditions may legitimately contain null values.
        Map<String, Object> copy = new LinkedHashMap<>();
        if (condition != null) {
            condition.forEach((key, value) -> copy.put(key, value instanceof Collection<?> c ? toStringSet(c) : value));
        }
        condition = Collections.unmodifiableMap(copy);
    }

    /**
     * List conditions (allowlisted ids, attribute values) become a set of strings once, here,
     * so matching is a hash lookup per evaluation rather than a scan of the list.
     */
    private static Set<String> toStringSet(Collection<?> values) {
        Set<String> set = new LinkedHashSet<>();
        for (Object value : values) {
            set.add(String.valueOf(value));
        }
        return Collections.unmodifiableSet(set);
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
