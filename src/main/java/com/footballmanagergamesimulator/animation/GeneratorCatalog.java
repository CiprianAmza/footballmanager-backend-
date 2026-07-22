package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PatternLibrary;
import com.footballmanagergamesimulator.animation.v1.LegacyFrameCompiler;
import com.footballmanagergamesimulator.animation.v1.LegacyPatternLibrary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact-version registry; a released version is frozen and new behaviour ships as a new version. */
final class GeneratorCatalog {
    record Generator(int version, AnimationCompiler compiler, List<PlayPattern> patterns, PlayPattern fallback) {
        PlayPattern find(PatternId id) {
            if (fallback.id() == id) return fallback;
            return patterns.stream().filter(pattern -> pattern.id() == id).findFirst().orElse(null);
        }
    }

    /** Newest installed generator; new moments render with this version. */
    static final int CURRENT_VERSION = 2;

    private final Map<Integer, Generator> generators = new LinkedHashMap<>();

    GeneratorCatalog(AnimationPhysicsProfile profile) {
        // Two independently frozen implementations coexist and dispatch is by exact version.
        // Version 1 is the original engine, preserved byte-for-byte; version 2 is the remediated
        // engine and is current. Registering version 2 never mutates version 1.
        register(new Generator(LegacyFrameCompiler.VERSION, new LegacyFrameCompiler(profile),
                LegacyPatternLibrary.patterns(), LegacyPatternLibrary.fallback()));
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
