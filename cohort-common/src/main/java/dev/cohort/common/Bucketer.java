package dev.cohort.common;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deterministic user bucketing. A user's bucket is a pure function of (salt, userId), so every
 * SDK instance computes the same answer with no shared state and no database call.
 */
public final class Bucketer {

    /** 10000 buckets gives 0.01% rollout granularity; weights are in the same basis points. */
    public static final int BUCKETS = 10_000;

    private static final HashFunction MURMUR3 = Hashing.murmur3_32_fixed();

    private Bucketer() {
    }

    /** {@code murmur3_32(salt + ":" + userId) mod 10000}, treating the hash as unsigned. */
    public static int bucket(String salt, String userId) {
        int hash = MURMUR3.hashString(salt + ":" + userId, StandardCharsets.UTF_8).asInt();
        return Integer.remainderUnsigned(hash, BUCKETS);
    }

    /**
     * Rollout bucket for PERCENT rules. It is hashed with a different suffix than the split bucket
     * on purpose: if both used the same bucket, a 10% rollout (buckets 0..999) of a 50/50 split
     * would put every rolled-out user in the first variant.
     */
    public static int rolloutBucket(String salt, String userId) {
        return bucket(salt + ":rollout", userId);
    }

    /**
     * Layer bucket: keyed by the layer name, not a flag's salt, so every flag in a layer sees the
     * same bucket for a user and their slices partition the users between them.
     */
    public static int layerBucket(String layer, String userId) {
        return bucket("layer:" + layer, userId);
    }

    /**
     * Walks variants in order, summing weights, and returns the first whose running total exceeds
     * the bucket. With control=5000, treatment=5000: bucket 3712 is control, 8120 is treatment.
     */
    public static String pick(List<Variant> variants, int bucket) {
        int runningTotal = 0;
        for (Variant variant : variants) {
            runningTotal += variant.weight();
            if (bucket < runningTotal) {
                return variant.name();
            }
        }
        return null;
    }
}
