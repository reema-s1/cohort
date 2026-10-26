package dev.cohort.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlagValidatorTest {

    private static final List<Variant> AB = List.of(new Variant("control", 5000), new Variant("treatment", 5000));

    @Test
    void validFlagHasNoErrors() {
        FlagDefinition f = new FlagDefinition("new-search-ranking", true, false, "s", "control", 0, AB,
                List.of(Rule.allowlist(1, List.of("u1"), "treatment"), Rule.percent(2, 10000, null)));
        assertThat(FlagValidator.validate(f)).isEmpty();
    }

    @Test
    void weightsMustSumToTenThousand() {
        FlagDefinition f = new FlagDefinition("f", true, false, "s", "a", 0,
                List.of(new Variant("a", 5000), new Variant("b", 4000)), List.of());
        assertThat(FlagValidator.validate(f)).anyMatch(e -> e.contains("sum to 10000"));
    }

    @Test
    void reportsEveryProblem() {
        FlagDefinition f = new FlagDefinition("Bad Key", true, false, "s", "missing", 0,
                List.of(new Variant("a", 10000), new Variant("a", 0)),
                List.of(new Rule(1, RuleType.PERCENT, Map.of(), "zzz"), Rule.allowlist(1, List.of("u"), null)));
        List<String> errors = FlagValidator.validate(f);
        assertThat(errors).anyMatch(e -> e.startsWith("key"));
        assertThat(errors).anyMatch(e -> e.contains("duplicate variant"));
        assertThat(errors).anyMatch(e -> e.startsWith("defaultVariant"));
        assertThat(errors).anyMatch(e -> e.contains("unknown variant"));
        assertThat(errors).anyMatch(e -> e.contains("basisPoints"));
        assertThat(errors).anyMatch(e -> e.contains("duplicate rule priority"));
    }

    @Test
    void layerSliceMustBeANonEmptyRangeOfBuckets() {
        List<Rule> rules = List.of(Rule.percent(1, 10000, null));
        assertThat(FlagValidator.validate(new FlagDefinition("f", true, false, "s", "control", 0, AB, rules,
                new Layer("search", 0, 5000)))).isEmpty();
        for (Layer bad : List.of(new Layer("search", 5000, 5000), new Layer("search", -1, 10), new Layer("search", 0, 10001))) {
            assertThat(FlagValidator.validate(new FlagDefinition("f", true, false, "s", "control", 0, AB, rules, bad)))
                    .anyMatch(e -> e.startsWith("layer slice"));
        }
        assertThat(FlagValidator.validate(new FlagDefinition("f", true, false, "s", "control", 0, AB, rules,
                new Layer("Bad Layer", 0, 10)))).anyMatch(e -> e.startsWith("layer name"));
    }
}
