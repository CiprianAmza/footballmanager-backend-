package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContextCoefficientMapperTest {

    private final CompartmentEngineConfig config = config(-1.0, 1.0);
    private final ContextCoefficientMapper mapper = new ContextCoefficientMapper(config);

    @Test
    void everySupportedTeamAxisMapsOnlyRelevantAttributes() {
        assertOnly(context("Attacking", "Standard", "Normal", "Standard", "Standard", "Balanced"),
                PlayerAttribute.OFF_THE_BALL, PlayerAttribute.COMPOSURE);
        assertOnly(context("Balanced", "Higher", "Normal", "Standard", "Standard", "Balanced"),
                PlayerAttribute.DECISIONS, PlayerAttribute.FIRST_TOUCH);
        assertOnly(context("Balanced", "Standard", "Long", "Standard", "Standard", "Balanced"),
                PlayerAttribute.PASSING, PlayerAttribute.VISION, PlayerAttribute.KICKING);
        assertOnly(context("Balanced", "Standard", "Normal", "High", "Standard", "Balanced"),
                PlayerAttribute.PACE, PlayerAttribute.ANTICIPATION, PlayerAttribute.POSITIONING);
        assertOnly(context("Balanced", "Standard", "Normal", "Standard", "High", "Balanced"),
                PlayerAttribute.WORK_RATE, PlayerAttribute.STAMINA, PlayerAttribute.ANTICIPATION);
        assertOnly(context("Balanced", "Standard", "Normal", "Standard", "Standard", "Wide"),
                PlayerAttribute.PACE, PlayerAttribute.DRIBBLING, PlayerAttribute.PASSING);
    }

    @Test
    void neutralUnknownAndNullContextProduceZeroK() {
        assertThat(mapper.map(TacticalContextInput.neutral()).coefficients()).isEmpty();
        assertThat(mapper.map(new TacticalContextInput("mystery", null, "?", "", "unknown", "standard", List.of("???")))
                .coefficients()).isEmpty();
        assertThat(mapper.map(null).coefficients()).isEmpty();
    }

    @Test
    void everyCanonicalPlayerInstructionIsSupportedAndRelevantOnly() {
        assertOnly(instructions("Mark Tighter"), PlayerAttribute.MARKING, PlayerAttribute.CONCENTRATION);
        assertOnly(instructions("Close Down More"), PlayerAttribute.WORK_RATE, PlayerAttribute.STAMINA, PlayerAttribute.ANTICIPATION);
        assertOnly(instructions("Close Down Less"), PlayerAttribute.POSITIONING, PlayerAttribute.CONCENTRATION);
        assertOnly(instructions("Tackle Harder"), PlayerAttribute.TACKLING, PlayerAttribute.BRAVERY);
        assertOnly(instructions("Stay On Feet"), PlayerAttribute.DECISIONS, PlayerAttribute.POSITIONING);
        assertOnly(instructions("Ease Off Tackles"), PlayerAttribute.DECISIONS, PlayerAttribute.TACKLING);
        assertOnly(instructions("Get Further Forward"), PlayerAttribute.OFF_THE_BALL, PlayerAttribute.STAMINA);
        assertOnly(instructions("Hold Position"), PlayerAttribute.POSITIONING, PlayerAttribute.CONCENTRATION);
        assertOnly(instructions("Shoot More Often"), PlayerAttribute.FINISHING, PlayerAttribute.COMPOSURE);
        assertOnly(instructions("Shoot Less Often"), PlayerAttribute.DECISIONS, PlayerAttribute.PASSING);
        assertOnly(instructions("Dribble More"), PlayerAttribute.DRIBBLING, PlayerAttribute.ACCELERATION);
        assertOnly(instructions("Dribble Less"), PlayerAttribute.FIRST_TOUCH, PlayerAttribute.PASSING);
        assertOnly(instructions("Roam From Position"), PlayerAttribute.OFF_THE_BALL, PlayerAttribute.DECISIONS);
        assertOnly(instructions("Sit Narrower"), PlayerAttribute.TECHNIQUE, PlayerAttribute.FIRST_TOUCH);
        assertOnly(instructions("Stay Wider"), PlayerAttribute.PACE, PlayerAttribute.DRIBBLING);
        assertOnly(instructions("Move Into Channels"), PlayerAttribute.OFF_THE_BALL, PlayerAttribute.ANTICIPATION);
        assertOnly(instructions("Drop Deeper"), PlayerAttribute.FIRST_TOUCH, PlayerAttribute.VISION, PlayerAttribute.PASSING);
        assertOnly(instructions("Pass It Shorter"), PlayerAttribute.PASSING, PlayerAttribute.FIRST_TOUCH);
        assertOnly(instructions("Try More Direct Passes"), PlayerAttribute.PASSING, PlayerAttribute.VISION);
        assertOnly(instructions("Cross From Byline"), PlayerAttribute.PACE, PlayerAttribute.DRIBBLING, PlayerAttribute.PASSING);
        assertOnly(instructions("Cross From Deep"), PlayerAttribute.PASSING, PlayerAttribute.VISION);
        assertOnly(instructions("Play Through Balls"), PlayerAttribute.VISION, PlayerAttribute.PASSING, PlayerAttribute.DECISIONS);
    }

    @Test
    void composedAndConflictingSignalsAddWithoutErasingEachOther() {
        var result = mapper.map(new TacticalContextInput("Attacking", "Higher", "Short", "High", "High", "Wide",
                List.of("Hold Position", "Get Further Forward")));
        assertThat(result.coefficients().get(PlayerAttribute.OFF_THE_BALL)).isCloseTo(.35, within(1e-12));
        assertThat(result.coefficients().get(PlayerAttribute.POSITIONING)).isCloseTo(.30, within(1e-12));
        assertThat(result.coefficients().get(PlayerAttribute.PASSING)).isCloseTo(.20, within(1e-12));
        assertThat(result.contributions()).hasSizeGreaterThan(result.coefficients().size());
    }

    @Test
    void configuredClampIsReported() {
        ContextCoefficientMapper tight = new ContextCoefficientMapper(config(-.25, .25));
        var result = tight.map(new TacticalContextInput("Very Attacking", "Standard", "Normal", "Standard",
                "Standard", "Balanced", List.of("Get Further Forward", "Move Into Channels")));
        assertThat(result.coefficients()).containsEntry(PlayerAttribute.OFF_THE_BALL, .25);
        assertThat(result.clamps()).anySatisfy(clamp -> {
            assertThat(clamp.attribute()).isEqualTo(PlayerAttribute.OFF_THE_BALL);
            assertThat(clamp.requested()).isCloseTo(.70, within(1e-12));
            assertThat(clamp.applied()).isEqualTo(.25);
        });
    }

    @Test
    void instructionPermutationProducesIdenticalMappingAndBreakdown() {
        var a = instructions("Play Through Balls", "Hold Position", "Close Down More");
        var b = instructions("Close Down More", "Play Through Balls", "Hold Position");
        assertThat(mapper.map(a)).isEqualTo(mapper.map(b));
    }

    private void assertOnly(TacticalContextInput input, PlayerAttribute... attributes) {
        assertThat(mapper.map(input).coefficients()).containsOnlyKeys(attributes);
    }

    private static TacticalContextInput context(String mentality, String tempo, String passing, String line,
                                                 String pressing, String width) {
        return new TacticalContextInput(mentality, tempo, passing, line, pressing, width, List.of());
    }

    private static TacticalContextInput instructions(String... values) {
        return new TacticalContextInput("Balanced", "Standard", "Normal", "Standard", "Standard", "Balanced", List.of(values));
    }

    private static CompartmentEngineConfig config(double min, double max) {
        CompartmentEngineConfig c = new CompartmentEngineConfig();
        c.getRating().setContextCoefficientMin(min);
        c.getRating().setContextCoefficientMax(max);
        return c;
    }
}
