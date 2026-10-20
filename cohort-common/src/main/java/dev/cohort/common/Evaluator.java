package dev.cohort.common;

import dev.cohort.common.Evaluation.Reason;

import java.util.Collection;

/**
 * Pure flag evaluation: a hash and a loop, no I/O. Used by both the SDK and the server's
 * {@code /sdk/v1/evaluate} endpoint so they can never disagree.
 */
public final class Evaluator {

    private Evaluator() {
    }

    /**
     * Evaluation order:
     * <ol>
     *   <li>Flag missing: caller's default ({@code FLAG_NOT_FOUND}, variant null).</li>
     *   <li>Killed or archived: the flag's default variant.</li>
     *   <li>Rules by priority; the first match serves its variant, or the weighted split.</li>
     *   <li>No match: the flag's default variant.</li>
     * </ol>
     */
    public static Evaluation evaluate(FlagDefinition flag, CohortUser user) {
        if (flag == null) {
            return new Evaluation(null, null, Reason.FLAG_NOT_FOUND, false);
        }
        if (!flag.enabled() || flag.archived()) {
            return new Evaluation(flag.key(), flag.defaultVariant(), Reason.DISABLED, false);
        }
        for (Rule rule : flag.rules()) {
            if (matches(rule, flag, user)) {
                if (rule.variant() != null) {
                    return new Evaluation(flag.key(), rule.variant(), Reason.RULE_MATCH, false);
                }
                String variant = Bucketer.pick(flag.variants(), Bucketer.bucket(flag.salt(), user.id()));
                return new Evaluation(flag.key(), variant, Reason.SPLIT, true);
            }
        }
        return new Evaluation(flag.key(), flag.defaultVariant(), Reason.DEFAULT, false);
    }

    static boolean matches(Rule rule, FlagDefinition flag, CohortUser user) {
        return switch (rule.type()) {
            case ALLOWLIST -> contains(rule.condition().get("userIds"), user.id());
            case ATTRIBUTE -> {
                Object attribute = rule.condition().get("attribute");
                String actual = attribute == null ? null : user.attribute(attribute.toString());
                yield actual != null && contains(rule.condition().get("values"), actual);
            }
            case PERCENT -> {
                Object basisPoints = rule.condition().get("basisPoints");
                yield basisPoints instanceof Number n
                        && Bucketer.rolloutBucket(flag.salt(), user.id()) < n.intValue();
            }
        };
    }

    private static boolean contains(Object list, String value) {
        if (!(list instanceof Collection<?> values)) {
            return false;
        }
        for (Object candidate : values) {
            if (value.equals(String.valueOf(candidate))) {
                return true;
            }
        }
        return false;
    }
}
