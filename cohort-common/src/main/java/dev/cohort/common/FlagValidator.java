package dev.cohort.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a flag definition before it is saved. Returns every problem rather than the first, so an
 * API caller can fix them all in one go.
 */
public final class FlagValidator {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private FlagValidator() {
    }

    public static List<String> validate(FlagDefinition flag) {
        List<String> errors = new ArrayList<>();
        if (flag.key() == null || !KEY.matcher(flag.key()).matches()) {
            errors.add("key must be 1-100 chars of lowercase letters, digits, '.', '_' or '-'");
        }
        if (flag.variants().isEmpty()) {
            errors.add("at least one variant is required");
        }

        Set<String> names = new HashSet<>();
        int totalWeight = 0;
        for (Variant variant : flag.variants()) {
            if (variant.name() == null || variant.name().isBlank()) {
                errors.add("variant names must not be blank");
            } else if (!names.add(variant.name())) {
                errors.add("duplicate variant '" + variant.name() + "'");
            }
            if (variant.weight() < 0 || variant.weight() > Bucketer.BUCKETS) {
                errors.add("variant '" + variant.name() + "' weight must be 0-10000");
            }
            totalWeight += variant.weight();
        }
        if (!flag.variants().isEmpty() && totalWeight != Bucketer.BUCKETS) {
            errors.add("variant weights must sum to 10000, got " + totalWeight);
        }
        if (flag.defaultVariant() == null || !names.contains(flag.defaultVariant())) {
            errors.add("defaultVariant must name one of the variants");
        }

        Layer layer = flag.layer();
        if (layer != null) {
            if (layer.name() == null || !KEY.matcher(layer.name()).matches()) {
                errors.add("layer name must be 1-100 chars of lowercase letters, digits, '.', '_' or '-'");
            }
            if (layer.start() < 0 || layer.end() > Bucketer.BUCKETS || layer.start() >= layer.end()) {
                errors.add("layer slice must satisfy 0 <= start < end <= 10000, got [" + layer.start() + ", " + layer.end() + ")");
            }
        }

        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : flag.rules()) {
            if (!priorities.add(rule.priority())) {
                errors.add("duplicate rule priority " + rule.priority());
            }
            if (rule.type() == null) {
                errors.add("rule " + rule.priority() + " has no type");
                continue;
            }
            if (rule.variant() != null && !names.contains(rule.variant())) {
                errors.add("rule " + rule.priority() + " serves unknown variant '" + rule.variant() + "'");
            }
            switch (rule.type()) {
                case ALLOWLIST -> {
                    if (!(rule.condition().get("userIds") instanceof Collection<?>)) {
                        errors.add("rule " + rule.priority() + ": ALLOWLIST needs a 'userIds' list");
                    }
                }
                case ATTRIBUTE -> {
                    if (!(rule.condition().get("attribute") instanceof String)
                            || !(rule.condition().get("values") instanceof Collection<?>)) {
                        errors.add("rule " + rule.priority() + ": ATTRIBUTE needs 'attribute' and a 'values' list");
                    }
                }
                case PERCENT -> {
                    if (!(rule.condition().get("basisPoints") instanceof Number n)
                            || n.intValue() < 0 || n.intValue() > Bucketer.BUCKETS) {
                        errors.add("rule " + rule.priority() + ": PERCENT needs 'basisPoints' 0-10000");
                    }
                }
            }
        }
        return errors;
    }
}
