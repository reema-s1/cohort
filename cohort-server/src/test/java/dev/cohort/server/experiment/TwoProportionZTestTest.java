package dev.cohort.server.experiment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TwoProportionZTestTest {

    @Test
    void normalCdfMatchesKnownValues() {
        assertThat(TwoProportionZTest.normalCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(TwoProportionZTest.normalCdf(1.96)).isCloseTo(0.9750, within(1e-4));
        assertThat(TwoProportionZTest.normalCdf(-1.96)).isCloseTo(0.0250, within(1e-4));
        assertThat(TwoProportionZTest.normalCdf(2.5758)).isCloseTo(0.9950, within(1e-4));
    }

    @Test
    void tenVersusElevenPercentOnFiftyThousandEachIsSignificant() {
        // pooled p = 0.105, se = sqrt(0.105 * 0.895 * 2/50000) = 0.0019388, z = 0.01 / se = 5.158
        TwoProportionZTest.Result result = TwoProportionZTest.test(5000, 50_000, 5500, 50_000);
        assertThat(result.z()).isCloseTo(5.158, within(0.001));
        assertThat(result.pValue()).isLessThan(1e-6);
    }

    @Test
    void smallSamplesAreNotSignificant() {
        TwoProportionZTest.Result result = TwoProportionZTest.test(10, 100, 11, 100);
        assertThat(result.pValue()).isGreaterThan(0.5);
    }

    @Test
    void identicalRatesGiveZeroAndPValueOne() {
        TwoProportionZTest.Result result = TwoProportionZTest.test(100, 1000, 100, 1000);
        assertThat(result.z()).isZero();
        assertThat(result.pValue()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void noDataOrNoVarianceGivesNoResult() {
        assertThat(TwoProportionZTest.test(0, 0, 5, 100)).isNull();
        assertThat(TwoProportionZTest.test(0, 100, 0, 100)).isNull();
    }
}
