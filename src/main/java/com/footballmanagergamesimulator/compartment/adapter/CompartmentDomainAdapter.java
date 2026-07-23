package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.ContextualPlayerRatingCalculator;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRatingInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.Rating;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, side-effect-free Phase&nbsp;2 adapter that turns an immutable {@link DomainPlayerSnapshot}
 * into the explainable Attack/Midfield/Defense {@link ContextualPlayerRating} produced by the
 * existing Phase&nbsp;0/1 {@link ContextualPlayerRatingCalculator}.
 *
 * <p>There is no Spring annotation, repository, database, random source, match score, team
 * aggregation or runtime wiring here. The class only maps already-extracted domain values onto the
 * typed pure contract and delegates the maths to the existing calculator, so the same snapshot
 * always yields the same breakdown.
 *
 * <p>Deliberate boundary decisions (documented in
 * {@code docs/compartment-engine/COMPARTMENT_ENGINE_V1_PHASE_2_ADAPTER.md}):
 * <ul>
 *   <li>attributes are clamped into the configured 1&ndash;20 domain; a missing attribute defaults
 *       to the configured minimum;</li>
 *   <li>an unknown/blank used position falls back to the natural position, then to
 *       {@link #UNKNOWN_POSITION} (which the calculator treats with the default multipliers);</li>
 *   <li>an unknown/blank role maps to the neutral role (default role multiplier);</li>
 *   <li>an unknown/blank duty maps to {@link #DEFAULT_DUTY};</li>
 *   <li>context coefficients are always empty (K=0): tactic/instruction&nbsp;&rarr;&nbsp;K mapping is
 *       roadmap item&nbsp;3 and is intentionally out of Phase&nbsp;2 scope;</li>
 *   <li>a missing familiarity defaults to {@link #DEFAULT_POSITION_FAMILIARITY}; a missing role
 *       suitability defaults to {@link #DEFAULT_ROLE_SUITABILITY} (a neutral role-fit of 1.0).</li>
 * </ul>
 */
public final class CompartmentDomainAdapter {

    /** Missing familiarity ⇒ fully familiar. */
    public static final double DEFAULT_POSITION_FAMILIARITY = 1.0;
    /** Missing suitability ⇒ neutral role fit (0.85 + 0.30·0.5 = 1.0). */
    public static final double DEFAULT_ROLE_SUITABILITY = 50.0;
    /** Missing/unknown duty ⇒ the neutral duty. */
    public static final Duty DEFAULT_DUTY = Duty.SUPPORT;
    /** Sentinel used when neither a used nor a natural position is available; yields default multipliers. */
    public static final String UNKNOWN_POSITION = "UNKNOWN";

    private final CompartmentEngineConfig config;
    private final ContextualPlayerRatingCalculator calculator;

    public CompartmentDomainAdapter(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.calculator = new ContextualPlayerRatingCalculator(config);
    }

    /** Map + evaluate in one step: immutable snapshot ⇒ explainable A/M/D breakdown. */
    public ContextualPlayerRating rate(DomainPlayerSnapshot snapshot) {
        return calculator.rate(toRatingInput(snapshot));
    }

    /**
     * Translate the domain snapshot into the typed pure {@link PlayerRatingInput}, applying the
     * documented defensive defaults. Exposed so tests and calibration can inspect the exact contract
     * inputs the maths runs on.
     */
    public PlayerRatingInput toRatingInput(DomainPlayerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Rating rating = config.getRating();

        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            Integer raw = snapshot.attributes().get(attribute);
            int value = raw == null ? rating.getAttributeMin() : raw;
            attributes.put(attribute, clampInt(value, rating.getAttributeMin(), rating.getAttributeMax()));
        }

        String position = firstNonBlank(snapshot.usedPosition(), snapshot.naturalPosition(), UNKNOWN_POSITION);
        String role = blankToEmpty(snapshot.roleDisplayName());
        Duty duty = mapDuty(snapshot.dutyLabel());
        double familiarity = snapshot.positionFamiliarity() == null
                ? DEFAULT_POSITION_FAMILIARITY : snapshot.positionFamiliarity();
        double suitability = snapshot.roleSuitability() == null
                ? DEFAULT_ROLE_SUITABILITY : snapshot.roleSuitability();

        // Context coefficients stay empty on purpose: tactic/instruction → K mapping is roadmap item 3.
        return new PlayerRatingInput(position, role, duty, attributes, Map.of(),
                familiarity, snapshot.fitness(), snapshot.morale(), suitability);
    }

    /**
     * Map the domain duty label to the typed {@link Duty}. Case-insensitive; a {@code null}, blank or
     * unrecognised label defensively resolves to {@link #DEFAULT_DUTY}.
     */
    public static Duty mapDuty(String label) {
        if (label == null) {
            return DEFAULT_DUTY;
        }
        switch (label.trim().toUpperCase(Locale.ROOT)) {
            case "ATTACK":
                return Duty.ATTACK;
            case "SUPPORT":
                return Duty.SUPPORT;
            case "DEFEND":
                return Duty.DEFEND;
            default:
                return DEFAULT_DUTY;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return UNKNOWN_POSITION;
    }
}
