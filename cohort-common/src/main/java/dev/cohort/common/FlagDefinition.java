package dev.cohort.common;

import java.util.Comparator;
import java.util.List;

/**
 * Everything needed to evaluate one flag, with no database access.
 *
 * <p>Variant order matters: bucketing walks the variants in this order, so it must never be
 * reshuffled once an experiment is running.
 *
 * @param layer the slice of a mutually exclusive layer this flag's experiment runs in, or null
 *              for no layer
 */
public record FlagDefinition(
        String key,
        boolean enabled,
        boolean archived,
        String salt,
        String defaultVariant,
        long version,
        List<Variant> variants,
        List<Rule> rules,
        Layer layer) {

    public FlagDefinition {
        variants = variants == null ? List.of() : List.copyOf(variants);
        rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }

    /** A flag outside any layer. */
    public FlagDefinition(String key, boolean enabled, boolean archived, String salt, String defaultVariant,
                          long version, List<Variant> variants, List<Rule> rules) {
        this(key, enabled, archived, salt, defaultVariant, version, variants, rules, null);
    }
}
