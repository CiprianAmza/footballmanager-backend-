package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure deterministic Animation Director; canonical facts are read-only. */
@Service
public class AnimationDirector {
    public static final int CURRENT_GENERATOR_VERSION = FrameCompiler.VERSION;

    public record DirectedAnimation(AnimationReplay replay, AnimationRecipe recipe) { }

    private final AnimationPhysicsProfile profile;
    private final GeneratorCatalog catalog;
    private final AnimationInvariantValidator validator;

    public AnimationDirector() {
        this(AnimationPhysicsProfile.defaults());
    }

    @Autowired
    public AnimationDirector(AnimationV3Settings settings) {
        this(settings.physicsProfile());
    }

    public AnimationDirector(AnimationPhysicsProfile profile) {
        this.profile = profile;
        this.catalog = new GeneratorCatalog(profile);
        this.validator = new AnimationInvariantValidator(profile);
    }

    public DirectedAnimation direct(MatchMomentSpec spec) {
        GeneratorCatalog.Generator generator = catalog.require(spec.generatorVersion());
        long seed = AnimationSeed.derive(spec.planSeed(), spec.fixtureKey(), spec.slotIndex(), spec.generatorVersion());
        PlayPattern selected = select(spec, seed, generator);
        AnimationReplay replay = render(spec, seed, selected, generator);
        List<String> errors = validator.validate(replay, spec);
        if (!errors.isEmpty() && selected.id() != PatternId.SAFE_FALLBACK) {
            replay = render(spec, seed, generator.fallback(), generator);
            errors = validator.validate(replay, spec);
        }
        if (!errors.isEmpty()) throw new IllegalStateException("cannot animate " + spec.key() + ": " + errors);

        AnimationRecipe recipe = new AnimationRecipe(spec.fixtureKey(), spec.slotIndex(), spec.planSeed(), seed,
                spec.generatorVersion(), replay.pattern(), spec.minute(), spec.firstHalfStoppage(),
                spec.scoringTeamId(), spec.defendingTeamId(), spec.homeTeamId(), spec.phase(), spec.outcome(),
                spec.scorerId(), spec.assisterId(), spec.playersOnPitch(), spec.tacticalContext(), profile);
        return new DirectedAnimation(replay, recipe);
    }

    public AnimationReplay replay(AnimationRecipe recipe) {
        GeneratorCatalog replayCatalog = recipe.physicsProfile().equals(profile)
                ? catalog : new GeneratorCatalog(recipe.physicsProfile());
        GeneratorCatalog.Generator generator = replayCatalog.require(recipe.generatorVersion());
        PlayPattern pattern = generator.find(recipe.pattern());
        if (pattern == null) throw new IllegalStateException("missing frozen pattern " + recipe.pattern());
        MatchMomentSpec spec = recipe.toSpec();
        AnimationReplay replay = render(spec, recipe.seed(), pattern, generator);
        List<String> errors = new AnimationInvariantValidator(recipe.physicsProfile()).validate(replay, spec);
        if (!errors.isEmpty()) throw new IllegalStateException("historical recipe changed " + recipe.key() + ": " + errors);
        return replay;
    }

    private static AnimationReplay render(MatchMomentSpec spec, long seed, PlayPattern pattern,
                                          GeneratorCatalog.Generator generator) {
        Random random = new Random(seed);
        PlayScript script = pattern.create(spec, random);
        return generator.compiler().compile(spec, script, random);
    }

    private static PlayPattern select(MatchMomentSpec spec, long seed, GeneratorCatalog.Generator generator) {
        List<PlayPattern> eligible = new ArrayList<>();
        for (PlayPattern pattern : generator.patterns()) if (pattern.supports(spec)) eligible.add(pattern);
        if (eligible.isEmpty()) return generator.fallback();
        double total = eligible.stream().mapToDouble(pattern -> pattern.weight(spec)).sum();
        double draw = new Random(seed ^ AnimationSeed.SELECTION_SALT).nextDouble() * total;
        double cumulative = 0;
        for (PlayPattern pattern : eligible) {
            cumulative += pattern.weight(spec);
            if (draw <= cumulative) return pattern;
        }
        return eligible.get(eligible.size() - 1);
    }
}
