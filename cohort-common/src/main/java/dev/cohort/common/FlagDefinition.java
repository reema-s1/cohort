package dev.cohort.common;

import java.util.Comparator;
import java.util.List;

/**
 * Everything needed to evaluate one flag, with no database access.
 *
 * <p>Variant order matters: bucketing walks the variants in this order, so it must never be
 * reshuffled once an experiment is running.
 */
public record FlagDefinition(
        String key,
        boolean enabled,
        boolean archived,
        String salt,
        String defaultVariant,
        long version,
        List<Variant> variants,
        List<Rule> rules) {

    public FlagDefinition {
        variants = variants == null ? List.of() : List.copyOf(variants);
        rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }
}
