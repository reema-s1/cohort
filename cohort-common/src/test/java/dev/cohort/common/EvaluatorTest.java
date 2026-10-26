package dev.cohort.common;

import dev.cohort.common.Evaluation.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluatorTest {

    private static final List<Variant> AB = List.of(new Variant("control", 5000), new Variant("treatment", 5000));

    private static FlagDefinition flag(boolean enabled, List<Rule> rules) {
        return new FlagDefinition("new-search-ranking", enabled, false, "salt", "control", 1, AB, rules);
    }

    @Test
    void missingFlagReturnsNotFound() {
        Evaluation result = Evaluator.evaluate(null, CohortUser.of("u1"));
        assertThat(result.reason()).isEqualTo(Reason.FLAG_NOT_FOUND);
        assertThat(result.variant()).isNull();
    }

    @Test
    void killedFlagServesDefaultEvenWhenRulesMatch() {
        FlagDefinition killed = flag(false, List.of(Rule.allowlist(1, List.of("u1"), "treatment")));
        Evaluation result = Evaluator.evaluate(killed, CohortUser.of("u1"));
        assertThat(result.variant()).isEqualTo("control");
        assertThat(result.reason()).isEqualTo(Reason.DISABLED);
        assertThat(result.inExperiment()).isFalse();
    }

    @Test
    void archivedFlagServesDefault() {
        FlagDefinition archived = new FlagDefinition("f", true, true, "salt", "control", 1, AB,
                List.of(Rule.percent(1, 10000, null)));
        assertThat(Evaluator.evaluate(archived, CohortUser.of("u1")).reason()).isEqualTo(Reason.DISABLED);
    }

    @Test
    void noMatchingRuleServesDefault() {
        FlagDefinition f = flag(true, List.of(Rule.attribute(1, "country", List.of("IN"), "treatment")));
        Evaluation result = Evaluator.evaluate(f, CohortUser.of("u1").with("country", "US"));
        assertThat(result.variant()).isEqualTo("control");
        assertThat(result.reason()).isEqualTo(Reason.DEFAULT);
    }

    @Test
    void allowlistServesFixedVariantOutsideTheExperiment() {
        FlagDefinition f = flag(true, List.of(Rule.allowlist(1, List.of("qa-1", "qa-2"), "treatment")));
        Evaluation result = Evaluator.evaluate(f, CohortUser.of("qa-2"));
        assertThat(result.variant()).isEqualTo("treatment");
        assertThat(result.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(result.inExperiment()).isFalse();
    }

    @Test
    void attributeRuleMatchesAnyListedValue() {
        FlagDefinition f = flag(true, List.of(Rule.attribute(1, "platform", List.of("android", "ios"), "treatment")));
        assertThat(Evaluator.evaluate(f, CohortUser.of("u").with("platform", "ios")).variant()).isEqualTo("treatment");
        assertThat(Evaluator.evaluate(f, CohortUser.of("u").with("platform", "web")).variant()).isEqualTo("control");
        assertThat(Evaluator.evaluate(f, CohortUser.of("u")).variant()).isEqualTo("control");
    }

    @Test
    void rulesAreEvaluatedByPriorityNotListOrder() {
        // Declared out of order; priority 1 must win for an allowlisted user in India.
        FlagDefinition f = flag(true, List.of(
                Rule.attribute(2, "country", List.of("IN"), "control"),
                Rule.allowlist(1, List.of("u1"), "treatment")));
        Evaluation result = Evaluator.evaluate(f, CohortUser.of("u1").with("country", "IN"));
        assertThat(result.variant()).isEqualTo("treatment");
    }

    @Test
    void percentRuleWithoutVariantServesSplitAndMarksExposure() {
        FlagDefinition f = flag(true, List.of(Rule.percent(1, 10000, null)));
        Evaluation result = Evaluator.evaluate(f, CohortUser.of("u1"));
        assertThat(result.reason()).isEqualTo(Reason.SPLIT);
        assertThat(result.inExperiment()).isTrue();
        assertThat(result.variant()).isIn("control", "treatment");
    }

    @Test
    void percentRolloutCoversRoughlyThatShareOfUsers() {
        FlagDefinition f = flag(true, List.of(Rule.percent(1, 1000, "treatment")));
        int users = 100_000;
        int treated = 0;
        for (int i = 0; i < users; i++) {
            if ("treatment".equals(Evaluator.evaluate(f, CohortUser.of("user_" + i)).variant())) {
                treated++;
            }
        }
        assertThat(treated / (double) users).isBetween(0.095, 0.105);
    }

    @Test
    void rampingUpOnlyAddsUsers() {
        FlagDefinition tenPercent = flag(true, List.of(Rule.percent(1, 1000, "treatment")));
        FlagDefinition twentyPercent = flag(true, List.of(Rule.percent(1, 2000, "treatment")));
        for (int i = 0; i < 50_000; i++) {
            CohortUser user = CohortUser.of("user_" + i);
            if ("treatment".equals(Evaluator.evaluate(tenPercent, user).variant())) {
                assertThat(Evaluator.evaluate(twentyPercent, user).variant()).isEqualTo("treatment");
            }
        }
    }

    @Test
    void weightedSplitHonoursUnevenWeights() {
        List<Variant> abc = List.of(new Variant("a", 2000), new Variant("b", 3000), new Variant("c", 5000));
        FlagDefinition f = new FlagDefinition("abc", true, false, "salt", "a", 1, abc, List.of(Rule.percent(1, 10000, null)));
        int[] counts = new int[3];
        int users = 200_000;
        for (int i = 0; i < users; i++) {
            String variant = Evaluator.evaluate(f, CohortUser.of("user_" + i)).variant();
            counts[variant.charAt(0) - 'a']++;
        }
        assertThat(counts[0] / (double) users).isBetween(0.19, 0.21);
        assertThat(counts[1] / (double) users).isBetween(0.29, 0.31);
        assertThat(counts[2] / (double) users).isBetween(0.49, 0.51);
    }

    private static FlagDefinition layered(String key, String salt, Layer layer, List<Rule> rules) {
        return new FlagDefinition(key, true, false, salt, "control", 1, AB, rules, layer);
    }

    @Test
    void flagsInOneLayerNeverShareAUser() {
        List<Rule> everyone = List.of(Rule.percent(1, 10000, null));
        FlagDefinition ranking = layered("ranking", "salt-a", new Layer("search", 0, 4000), everyone);
        FlagDefinition snippets = layered("snippets", "salt-b", new Layer("search", 4000, 10000), everyone);
        int users = 100_000;
        int inRanking = 0;
        int inSnippets = 0;
        for (int i = 0; i < users; i++) {
            CohortUser user = CohortUser.of("user_" + i);
            boolean a = Evaluator.evaluate(ranking, user).inExperiment();
            boolean b = Evaluator.evaluate(snippets, user).inExperiment();
            assertThat(a && b).as("user_%d is in both experiments", i).isFalse();
            assertThat(a || b).as("user_%d is in neither experiment", i).isTrue();
            inRanking += a ? 1 : 0;
            inSnippets += b ? 1 : 0;
        }
        assertThat(inRanking / (double) users).isBetween(0.39, 0.41);
        assertThat(inSnippets / (double) users).isBetween(0.59, 0.61);
    }

    @Test
    void usersOutsideTheLayerSliceGetTheDefault() {
        FlagDefinition f = layered("ranking", "salt", new Layer("search", 0, 1), List.of(Rule.percent(1, 10000, null)));
        String outside = IntStream.range(0, 1000).mapToObj(i -> "user_" + i)
                .filter(id -> Bucketer.layerBucket("search", id) != 0)
                .findFirst().orElseThrow();
        Evaluation result = Evaluator.evaluate(f, CohortUser.of(outside));
        assertThat(result.reason()).isEqualTo(Reason.LAYER_EXCLUDED);
        assertThat(result.variant()).isEqualTo("control");
        assertThat(result.inExperiment()).isFalse();
    }

    @Test
    void allowlistStillAppliesOutsideTheLayerSlice() {
        FlagDefinition f = layered("ranking", "salt", new Layer("search", 0, 1),
                List.of(Rule.allowlist(1, List.of("qa-1"), "treatment"), Rule.percent(2, 10000, null)));
        assertThat(Evaluator.evaluate(f, CohortUser.of("qa-1")).variant()).isEqualTo("treatment");
    }

    @Test
    void theLayerSliceDoesNotSkewTheSplitWithinIt() {
        FlagDefinition f = layered("ranking", "salt", new Layer("search", 0, 5000), List.of(Rule.percent(1, 10000, null)));
        int inExperiment = 0;
        int treatment = 0;
        for (int i = 0; i < 200_000; i++) {
            Evaluation result = Evaluator.evaluate(f, CohortUser.of("user_" + i));
            if (result.inExperiment()) {
                inExperiment++;
                treatment += "treatment".equals(result.variant()) ? 1 : 0;
            }
        }
        assertThat(treatment / (double) inExperiment).isBetween(0.49, 0.51);
    }
}
