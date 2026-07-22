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

    private final Map<Integer, Generator> generators = new LinkedHashMap<>();

    GeneratorCatalog(AnimationPhysicsProfile profile) {
        register(new Generator(FrameCompiler.VERSION, new FrameCompiler(profile),
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
