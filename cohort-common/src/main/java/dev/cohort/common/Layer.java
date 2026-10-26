package dev.cohort.common;

/**
 * A slice of a mutually exclusive layer. Every flag in a layer hashes users with the layer's name
 * rather than its own salt, so all of them agree on each user's layer bucket; give the flags
 * non-overlapping slices and no user can be in two of their experiments at once.
 *
 * <p>With layer {@code search} split as {@code ranking [0, 5000)} and {@code snippets [5000, 10000)},
 * every user is eligible for exactly one of the two experiments.
 *
 * @param start first layer bucket in the slice, inclusive
 * @param end   last layer bucket in the slice, exclusive
 */
public record Layer(String name, int start, int end) {

    public boolean contains(String userId) {
        int bucket = Bucketer.layerBucket(name, userId);
        return bucket >= start && bucket < end;
    }

    public boolean overlaps(Layer other) {
        return name.equals(other.name) && start < other.end && other.start < end;
    }
}
