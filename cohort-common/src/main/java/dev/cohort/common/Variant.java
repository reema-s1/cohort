package dev.cohort.common;

/**
 * One arm of a flag. Weights are basis points (0..10000) and must sum to 10000 across a flag.
 */
public record Variant(String name, int weight) {
}
