package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PatternLibrary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact-version registry; future versions are added beside frozen v1. */
final class GeneratorCatalog {
    record Generator(int version, FrameCompiler compiler, List<PlayPattern> patterns, PlayPattern fallback) {
        PlayPattern find(PatternId id) {
            if (fallback.id() == id) return fallback;
            return patterns.stream().filter(pattern -> pattern.id() == id).findFirst().orElse(null);
        }
    }

    /** Newest installed generator; new moments render with this version. */
    static final int CURRENT_VERSION = 1;

    private final Map<Integer, Generator> generators = new LinkedHashMap<>();

    GeneratorCatalog(AnimationPhysicsProfile profile) {
        // Two independently frozen implementations coexist. Registering a later
        // version never mutates an earlier one: each carries its own compiler
        // instance and immutable tuning, and dispatch is by exact version.
        register(new Generator(1, new FrameCompiler(profile, 1, FrameCompiler.TUNING_V1),
                PatternLibrary.patterns(), PatternLibrary.fallback()));
        register(new Generator(2, new FrameCompiler(profile, 2, FrameCompiler.TUNING_V2),
                PatternLibrary.patterns(), PatternLibrary.fallback()));
    }

    private void register(Generator generator) {
        if (generators.putIfAbsent(generator.version(), generator) != null)
            throw new IllegalStateException("duplicate generator version " + generator.version());
    }

    Generator require(int version) {
        Generator generator = generators.get(version);
        if (generator == null) throw new UnsupportedAnimationVersionException(version);
        return generator;
    }
}
