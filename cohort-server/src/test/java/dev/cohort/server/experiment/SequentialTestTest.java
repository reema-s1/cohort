package dev.cohort.server.experiment;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SequentialTestTest {

    @Test
    void tenVersusElevenPercentOnFiftyThousandEachIsSignificant() {
        assertThat(SequentialTest.pValue(5000, 50_000, 5500, 50_000)).isLessThan(1e-4);
    }

    @Test
    void isMoreConservativeThanTheFixedSampleTest() {
        double sequential = SequentialTest.pValue(500, 5000, 560, 5000);
        double fixed = TwoProportionZTest.test(500, 5000, 560, 5000).pValue();
        assertThat(sequential).isGreaterThan(fixed);
    }

    @Test
    void identicalRatesGivePValueOne() {
        assertThat(SequentialTest.pValue(100, 1000, 100, 1000)).isEqualTo(1.0);
    }

    @Test
    void noDataOrNoVarianceGivesNoResult() {
        assertThat(SequentialTest.pValue(0, 0, 5, 100)).isNull();
        assertThat(SequentialTest.pValue(0, 100, 0, 100)).isNull();
    }

    /**
     * The point of the test: A/A experiments checked after every 500 users per arm, stopping at
     * the first p below 0.05. Peeking at the z-test that way declares a winner far more than 5% of
     * the time; the sequential test stays under 5%.
     */
    @Test
    void peekingAtAnAaTestKeepsTheFalsePositiveRateUnderAlpha() {
        Random random = new Random(42);
        int experiments = 400;
        int looks = 20;
        int usersPerLook = 500;
        double rate = 0.10;
        int fixedFalsePositives = 0;
        int sequentialFalsePositives = 0;
        for (int experiment = 0; experiment < experiments; experiment++) {
            long n = 0;
            long control = 0;
            long treatment = 0;
            boolean fixedRejected = false;
            boolean sequentialRejected = false;
            for (int look = 0; look < looks; look++) {
                for (int i = 0; i < usersPerLook; i++) {
                    control += random.nextDouble() < rate ? 1 : 0;
                    treatment += random.nextDouble() < rate ? 1 : 0;
                }
                n += usersPerLook;
                TwoProportionZTest.Result z = TwoProportionZTest.test(control, n, treatment, n);
                fixedRejected |= z != null && z.pValue() < 0.05;
                Double p = SequentialTest.pValue(control, n, treatment, n);
                sequentialRejected |= p != null && p < 0.05;
            }
            fixedFalsePositives += fixedRejected ? 1 : 0;
            sequentialFalsePositives += sequentialRejected ? 1 : 0;
        }
        assertThat(fixedFalsePositives / (double) experiments).isGreaterThan(0.10);
        assertThat(sequentialFalsePositives / (double) experiments).isLessThan(0.05);
    }
}
