package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PlayPatternLibrary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of frozen generator implementations. Recipes dispatch through
 * their exact version; a future v2 is added beside v1 rather than modifying
 * or replacing v1, which keeps historical recipes byte-identical.
 */
final class AnimationGeneratorRegistry {

    record Generator(
            int version,
            FrameCompiler compiler,
            List<PlayPattern> patterns,
            PlayPattern fallback) {

        PlayPattern patternById(String id) {
            if (fallback.id().equals(id)) return fallback;
            for (PlayPattern pattern : patterns) {
                if (pattern.id().equals(id)) return pattern;
            }
            return null;
        }
    }

    private final Map<Integer, Generator> versions = new LinkedHashMap<>();

    AnimationGeneratorRegistry(AnimationMotionLimits motionLimits) {
        register(new Generator(FrameCompiler.VERSION,
                new FrameCompiler(motionLimits),
                PlayPatternLibrary.standard(),
                PlayPatternLibrary.fallback()));
    }

    private void register(Generator generator) {
        if (versions.putIfAbsent(generator.version(), generator) != null) {
            throw new IllegalStateException("duplicate animation generator version " + generator.version());
        }
    }

    Generator require(int version) {
        Generator generator = versions.get(version);
        if (generator == null) throw new UnsupportedAnimationVersionException(version);
        return generator;
    }
}
