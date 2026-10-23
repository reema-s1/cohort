package dev.cohort.server.experiment;

import dev.cohort.common.Variant;
import dev.cohort.server.flag.FlagDtos.FlagResponse;
import dev.cohort.server.flag.FlagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private static final double SIGNIFICANCE = 0.05;

    private final FlagService flags;
    private final JdbcTemplate jdbc;

    public ExperimentController(FlagService flags, JdbcTemplate jdbc) {
        this.flags = flags;
        this.jdbc = jdbc;
    }

    /**
     * @param liftVsControl relative change in conversion rate against control, e.g. 0.1 for +10%
     * @param pValue        two-proportion z-test against control; null for control itself or when
     *                      there is not enough data
     */
    public record VariantResult(String variant, long exposures, long conversions, double conversionRate,
                                Double liftVsControl, Double zScore, Double pValue, boolean significant) {
    }

    public record ExperimentResults(String flagKey, String control, long totalExposures, List<VariantResult> variants) {
    }

    /** Per-variant results. Control defaults to the flag's default variant. */
    @GetMapping("/{key}/results")
    public ExperimentResults results(@PathVariable String key, @RequestParam(required = false) String control) {
        FlagResponse flag = flags.get(key);
        String controlVariant = control != null ? control : flag.defaultVariant();

        Map<String, long[]> counts = new HashMap<>();
        jdbc.query("SELECT variant, exposures, conversions FROM experiment_stats WHERE flag_key = ?",
                rs -> {
                    counts.put(rs.getString("variant"), new long[]{rs.getLong("exposures"), rs.getLong("conversions")});
                }, key);

        long[] controlCounts = counts.getOrDefault(controlVariant, new long[2]);
        double controlRate = rate(controlCounts);
        List<VariantResult> results = new ArrayList<>();
        long total = 0;
        // Flag order first, then any variant that has data but has since been removed from the flag.
        List<String> names = new ArrayList<>(flag.variants().stream().map(Variant::name).toList());
        counts.keySet().stream().filter(v -> !names.contains(v)).sorted().forEach(names::add);
        for (String variant : names) {
            long[] c = counts.getOrDefault(variant, new long[2]);
            total += c[0];
            if (variant.equals(controlVariant)) {
                results.add(new VariantResult(variant, c[0], c[1], rate(c), null, null, null, false));
                continue;
            }
            TwoProportionZTest.Result test = TwoProportionZTest.test(controlCounts[1], controlCounts[0], c[1], c[0]);
            Double lift = controlRate > 0 ? rate(c) / controlRate - 1 : null;
            results.add(new VariantResult(variant, c[0], c[1], rate(c), lift,
                    test == null ? null : test.z(),
                    test == null ? null : test.pValue(),
                    test != null && test.pValue() < SIGNIFICANCE));
        }
        return new ExperimentResults(key, controlVariant, total, results);
    }

    private static double rate(long[] counts) {
        return counts[0] == 0 ? 0 : counts[1] / (double) counts[0];
    }
}
