package dev.cohort.server.experiment;

/**
 * Two-sided two-proportion z-test with a pooled rate:
 * <pre>
 *   z = (p_t - p_c) / sqrt(p (1 - p) (1/n_t + 1/n_c))
 * </pre>
 * Deliberately simple. It assumes a fixed sample size decided up front; peeking at it repeatedly
 * inflates false positives, which is what sequential testing would fix.
 */
public final class TwoProportionZTest {

    public record Result(double z, double pValue) {
    }

    private TwoProportionZTest() {
    }

    /** Returns null when there is no data or no variance to test (e.g. nobody converted). */
    public static Result test(long conversionsControl, long exposuresControl, long conversionsTreatment, long exposuresTreatment) {
        if (exposuresControl == 0 || exposuresTreatment == 0) {
            return null;
        }
        double pc = conversionsControl / (double) exposuresControl;
        double pt = conversionsTreatment / (double) exposuresTreatment;
        double pooled = (conversionsControl + conversionsTreatment) / (double) (exposuresControl + exposuresTreatment);
        double standardError = Math.sqrt(pooled * (1 - pooled) * (1.0 / exposuresTreatment + 1.0 / exposuresControl));
        if (standardError == 0) {
            return null;
        }
        double z = (pt - pc) / standardError;
        double p = 2 * (1 - normalCdf(Math.abs(z)));
        return new Result(z, Math.max(0, Math.min(1, p)));
    }

    /** Standard normal CDF via the error function. */
    static double normalCdf(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    /** Abramowitz and Stegun 7.1.26; absolute error below 1.5e-7, plenty for a p-value. */
    static double erf(double x) {
        double sign = Math.signum(x);
        x = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * x);
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        return sign * (1 - poly * Math.exp(-x * x));
    }
}
