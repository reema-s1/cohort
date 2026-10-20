package dev.cohort.common;

/**
 * The result of evaluating a flag for a user.
 *
 * @param inExperiment true only when the variant came from the weighted split. Only these
 *                     assignments are logged as exposures, so users served a fixed variant by an
 *                     allowlist or the default never pollute experiment results.
 */
public record Evaluation(String flagKey, String variant, Reason reason, boolean inExperiment) {

    public enum Reason {
        /** The flag is not in the config. The caller's default is served. */
        FLAG_NOT_FOUND,
        /** The flag is killed or archived. Its default variant is served. */
        DISABLED,
        /** A rule matched and served a fixed variant. */
        RULE_MATCH,
        /** A rule matched and served the weighted split. */
        SPLIT,
        /** No rule matched. The default variant is served. */
        DEFAULT,
        /** Evaluation failed. The caller's default is served. */
        ERROR
    }
}
