package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Map;

/**
 * Immutable, validated configuration for one phonetic identity: named fragment pools
 * (e.g. PREFIX, VOWEL, MIDDLE, SUFFIX) plus one or more weighted patterns describing
 * in which order pools are concatenated. A pool may list the same fragment twice to
 * make it proportionally more likely.
 */
public final class NameStyle {

    /** One way of assembling a name: the pool keys to concatenate, drawn with this weight. */
    public record Pattern(int weight, List<String> poolKeys) {
        public Pattern {
            poolKeys = List.copyOf(poolKeys);
        }
    }

    private final String id;
    private final Map<String, List<String>> pools;
    private final List<Pattern> patterns;
    private final int totalWeight;

    private NameStyle(String id, Map<String, List<String>> pools, List<Pattern> patterns, int totalWeight) {
        this.id = id;
        this.pools = pools;
        this.patterns = patterns;
        this.totalWeight = totalWeight;
    }

    /**
     * Builds a style, rejecting configurations that could ever produce a null, empty
     * or unresolvable name.
     *
     * @throws IllegalArgumentException on a blank id, empty/blank pools or fragments,
     *         no patterns, a non-positive weight, an empty pattern, or a pattern
     *         referencing a pool key that does not exist.
     */
    public static NameStyle of(String id, Map<String, List<String>> pools, List<Pattern> patterns) {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("NameStyle id must not be blank");
        if (pools == null || pools.isEmpty())
            throw new IllegalArgumentException("NameStyle '" + id + "' must define at least one fragment pool");
        for (Map.Entry<String, List<String>> pool : pools.entrySet()) {
            if (pool.getValue() == null || pool.getValue().isEmpty())
                throw new IllegalArgumentException("NameStyle '" + id + "': pool '" + pool.getKey() + "' is empty");
            for (String fragment : pool.getValue())
                if (fragment == null || fragment.isBlank())
                    throw new IllegalArgumentException(
                            "NameStyle '" + id + "': pool '" + pool.getKey() + "' contains a blank fragment");
        }
        if (patterns == null || patterns.isEmpty())
            throw new IllegalArgumentException("NameStyle '" + id + "' must define at least one pattern");
        int totalWeight = 0;
        for (Pattern pattern : patterns) {
            if (pattern.weight() <= 0)
                throw new IllegalArgumentException("NameStyle '" + id + "': pattern weight must be positive");
            if (pattern.poolKeys().isEmpty())
                throw new IllegalArgumentException("NameStyle '" + id + "': pattern has no pool keys");
            for (String key : pattern.poolKeys())
                if (!pools.containsKey(key))
                    throw new IllegalArgumentException(
                            "NameStyle '" + id + "': pattern references unknown pool '" + key + "'");
            totalWeight += pattern.weight();
        }
        Map<String, List<String>> frozenPools = pools.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        return new NameStyle(id, frozenPools, List.copyOf(patterns), totalWeight);
    }

    public String id() {
        return id;
    }

    public List<String> pool(String key) {
        return pools.get(key);
    }

    public List<Pattern> patterns() {
        return patterns;
    }

    public int totalWeight() {
        return totalWeight;
    }
}
