package dev.cohort.server.experiment;

/**
 * Always-valid p-value for the difference of two conversion rates, from a mixture sequential
 * probability ratio test (mSPRT; Johari, Pekelis and Walsh, "Always Valid Inference", 2017).
 *
 * <p>With {@code theta = p_t - p_c}, its variance {@code V = p_c (1 - p_c) / n_c + p_t (1 - p_t) / n_t}
 * and a normal mixing prior {@code N(0, tau^2)} on the true difference, the likelihood ratio is
 * <pre>
 *   L = sqrt(V / (V + tau^2)) * exp(tau^2 * theta^2 / (2 V (V + tau^2)))
 * </pre>
 * and the p-value is {@code min(1, 1 / L)}. Under no real difference, L is a martingale, so the
 * chance that it <em>ever</em> exceeds {@code 1 / alpha} is at most alpha. Looking at the results
 * as often as you like and stopping the first time the p-value drops below alpha keeps the false
 * positive rate at alpha, which the fixed-sample {@link TwoProportionZTest} does not.
 *
 * <p>The price is power: for the same data the sequential p-value is larger than the z-test's.
 */
public final class SequentialTest {

    /**
     * Standard deviation of the mixing prior: the size of absolute difference the test is tuned
     * to detect fastest. One percentage point suits typical product conversion rates.
     */
    static final double MIXING_SD = 0.01;

    private SequentialTest() {
    }

    /** Returns null when there is no data or no variance to test. */
    public static Double pValue(long conversionsControl, long exposuresControl, long conversionsTreatment, long exposuresTreatment) {
        return pValue(conversionsControl, exposuresControl, conversionsTreatment, exposuresTreatment, MIXING_SD);
    }

    static Double pValue(long conversionsControl, long exposuresControl, long conversionsTreatment, long exposuresTreatment,
                         double mixingSd) {
        if (exposuresControl == 0 || exposuresTreatment == 0) {
            return null;
        }
        double pc = conversionsControl / (double) exposuresControl;
        double pt = conversionsTreatment / (double) exposuresTreatment;
        double variance = pc * (1 - pc) / exposuresControl + pt * (1 - pt) / exposuresTreatment;
        if (variance == 0) {
            return null;
        }
        double tau2 = mixingSd * mixingSd;
        double theta = pt - pc;
        // In log space: with large samples the exponent runs into the hundreds.
        double logRatio = 0.5 * Math.log(variance / (variance + tau2))
                + tau2 * theta * theta / (2 * variance * (variance + tau2));
        return Math.exp(Math.min(0, -logRatio));
    }
}
