package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The v2 Animation Director. Receives a fully-decided canonical moment
 * ({@link MatchMomentSpec}) and invents only its visual representation:
 * deterministically selects a {@link PlayPattern}, compiles it into physically
 * coherent frames, validates every invariant, and returns the replay together
 * with the compact {@link AnimationRecipe} that regenerates it byte-identically.
 *
 * <p>Pure and stateless: no repositories, no clocks, no global randomness.
 * Never alters score, side, minute, scorer, assister or statistics.
 */
@Service
public class AnimationDirector {

    public static final int CURRENT_GENERATOR_VERSION = FrameCompiler.VERSION;

    private final AnimationGeneratorRegistry generators;
    private final AnimationPhysicsValidator validator;
    private final AnimationMotionLimits motionLimits;

    /** Convenience constructor for pure unit use with production defaults. */
    public AnimationDirector() {
        this(AnimationMotionLimits.defaults());
    }

    /** Spring constructor: speed/acceleration limits come from v2 configuration. */
    @Autowired
    public AnimationDirector(AnimationV2Settings settings) {
        this(settings.motionLimits());
    }

    public AnimationDirector(AnimationMotionLimits motionLimits) {
        this.motionLimits = motionLimits;
        this.generators = new AnimationGeneratorRegistry(motionLimits);
        this.validator = new AnimationPhysicsValidator(motionLimits);
    }

    /** A directed animation: the frames plus the recipe that regenerates them. */
    public record DirectedAnimation(AnimationReplay replay, AnimationRecipe recipe) {
    }

    /** Direct a canonical moment: pick a pattern deterministically and render. */
    public DirectedAnimation direct(MatchMomentSpec spec) {
        AnimationGeneratorRegistry.Generator generator = generators.require(spec.generatorVersion());
        int version = generator.version();
        long seed = AnimationSeeds.derive(spec.planSeed(), spec.fixtureKey(),
                spec.slotIndex(), version);
        PlayPattern pattern = selectPattern(spec, seed, generator);
        AnimationReplay replay = renderValidated(spec, pattern, seed, generator);
        AnimationRecipe recipe = new AnimationRecipe(spec.fixtureKey(), spec.slotIndex(),
                spec.planSeed(), seed, version, replay.patternId(), spec.phase(), spec.outcome(),
                spec.minute(), spec.firstHalfStoppage(), spec.scoringTeamId(),
                spec.defendingTeamId(), spec.homeTeamId(), spec.scorerId(), spec.assisterId(),
                motionLimits,
                spec.attackingPlayers(), spec.defendingPlayers(), spec.tacticalContext());
        return new DirectedAnimation(replay, recipe);
    }

    /**
     * Regenerate the frames of a persisted recipe — byte-identical to the
     * original render. The exact frozen version and pattern must still be
     * shipped; otherwise regeneration fails explicitly so historical output
     * is never silently changed.
     */
    public AnimationReplay replay(AnimationRecipe recipe) {
        AnimationGeneratorRegistry replayGenerators = recipe.motionLimits().equals(motionLimits)
                ? generators : new AnimationGeneratorRegistry(recipe.motionLimits());
        AnimationPhysicsValidator replayValidator = recipe.motionLimits().equals(motionLimits)
                ? validator : new AnimationPhysicsValidator(recipe.motionLimits());
        AnimationGeneratorRegistry.Generator generator =
                replayGenerators.require(recipe.generatorVersion());
        MatchMomentSpec spec = recipe.toSpec();
        PlayPattern pattern = generator.patternById(recipe.patternId());
        if (pattern == null) {
            throw new IllegalStateException("Pattern " + recipe.patternId()
                    + " is missing from frozen generator version " + recipe.generatorVersion());
        }
        AnimationReplay replay = render(spec, pattern, recipe.seed(), generator);
        List<String> violations = replayValidator.validate(replay, spec);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Persisted animation recipe " + recipe.key()
                    + " no longer renders identically: " + violations);
        }
        return replay;
    }

    // ==================== INTERNALS ====================

    private AnimationReplay renderValidated(MatchMomentSpec spec, PlayPattern pattern, long seed,
                                            AnimationGeneratorRegistry.Generator generator) {
        AnimationReplay replay = render(spec, pattern, seed, generator);
        List<String> violations = validator.validate(replay, spec);
        if (!violations.isEmpty() && !pattern.id().equals(generator.fallback().id())) {
            replay = render(spec, generator.fallback(), seed, generator);
            violations = validator.validate(replay, spec);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Cannot animate canonical moment "
                    + AnimationKey.of(spec) + ": " + violations);
        }
        return replay;
    }

    private AnimationReplay render(MatchMomentSpec spec, PlayPattern pattern, long seed,
                                   AnimationGeneratorRegistry.Generator generator) {
        Random patternRng = new Random(seed);
        Choreography choreography = pattern.choreograph(spec, patternRng);
        return generator.compiler().compile(spec, choreography, patternRng);
    }

    /**
     * Deterministic weighted pick over the eligible patterns. Uses an RNG
     * stream independent from the pattern stream so recipe replays (which pin
     * the pattern) still consume identical pattern-stream draws.
     */
    private PlayPattern selectPattern(MatchMomentSpec spec, long seed,
                                      AnimationGeneratorRegistry.Generator generator) {
        List<PlayPattern> eligible = new ArrayList<>();
        for (PlayPattern p : generator.patterns()) {
            if (p.supports(spec)) eligible.add(p);
        }
        if (eligible.isEmpty()) return generator.fallback();

        Random selectionRng = new Random(seed ^ AnimationSeeds.SELECTION_SALT);
        double total = 0;
        for (PlayPattern p : eligible) total += p.weight(spec);
        double r = selectionRng.nextDouble() * total;
        double cum = 0;
        for (PlayPattern p : eligible) {
            cum += p.weight(spec);
            if (r <= cum) return p;
        }
        return eligible.get(eligible.size() - 1);
    }

}
