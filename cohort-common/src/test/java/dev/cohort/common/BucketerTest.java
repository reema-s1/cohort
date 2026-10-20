package dev.cohort.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BucketerTest {

    private static final List<Variant> FIFTY_FIFTY = List.of(new Variant("control", 5000), new Variant("treatment", 5000));

    @Test
    void sameUserAlwaysGetsSameBucket() {
        int first = Bucketer.bucket("salt-1", "user_42");
        for (int i = 0; i < 100; i++) {
            assertThat(Bucketer.bucket("salt-1", "user_42")).isEqualTo(first);
        }
    }

    @Test
    void bucketsAreInRange() {
        for (int i = 0; i < 100_000; i++) {
            assertThat(Bucketer.bucket("s", "user_" + i)).isBetween(0, Bucketer.BUCKETS - 1);
        }
    }

    @Test
    void fiftyFiftySplitIsWithinHalfAPercentOverOneMillionUsers() {
        int users = 1_000_000;
        int control = 0;
        for (int i = 0; i < users; i++) {
            if ("control".equals(Bucketer.pick(FIFTY_FIFTY, Bucketer.bucket("new-search-ranking", "user_" + i)))) {
                control++;
            }
        }
        double share = control / (double) users;
        assertThat(share).isBetween(0.495, 0.505);
    }

    @Test
    void pickWalksCumulativeWeights() {
        assertThat(Bucketer.pick(FIFTY_FIFTY, 0)).isEqualTo("control");
        assertThat(Bucketer.pick(FIFTY_FIFTY, 3712)).isEqualTo("control");
        assertThat(Bucketer.pick(FIFTY_FIFTY, 4999)).isEqualTo("control");
        assertThat(Bucketer.pick(FIFTY_FIFTY, 5000)).isEqualTo("treatment");
        assertThat(Bucketer.pick(FIFTY_FIFTY, 8120)).isEqualTo("treatment");
        assertThat(Bucketer.pick(FIFTY_FIFTY, 9999)).isEqualTo("treatment");
    }

    @Test
    void zeroWeightVariantIsNeverPicked() {
        List<Variant> variants = List.of(new Variant("a", 0), new Variant("b", 10000));
        for (int bucket = 0; bucket < Bucketer.BUCKETS; bucket++) {
            assertThat(Bucketer.pick(variants, bucket)).isEqualTo("b");
        }
    }

    @Test
    void differentSaltsGiveIndependentAssignments() {
        // Without a per-flag salt the same users would land in treatment of every experiment.
        int users = 100_000;
        int bothTreatment = 0;
        for (int i = 0; i < users; i++) {
            String id = "user_" + i;
            boolean a = Bucketer.bucket("flag-a", id) >= 5000;
            boolean b = Bucketer.bucket("flag-b", id) >= 5000;
            if (a && b) {
                bothTreatment++;
            }
        }
        // Independent 50/50 assignments overlap on ~25% of users, not ~50%.
        assertThat(bothTreatment / (double) users).isBetween(0.24, 0.26);
    }

    @Test
    void rolloutBucketIsIndependentOfSplitBucket() {
        int users = 100_000;
        int inRollout = 0;
        int controlInRollout = 0;
        for (int i = 0; i < users; i++) {
            String id = "user_" + i;
            if (Bucketer.rolloutBucket("salt", id) < 1000) {
                inRollout++;
                if (Bucketer.bucket("salt", id) < 5000) {
                    controlInRollout++;
                }
            }
        }
        assertThat(controlInRollout / (double) inRollout).isBetween(0.47, 0.53);
    }
}
