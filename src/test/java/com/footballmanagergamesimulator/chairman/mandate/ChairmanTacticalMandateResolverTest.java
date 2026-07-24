package com.footballmanagergamesimulator.chairman.mandate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChairmanTacticalMandateResolverTest {
    private final ChairmanTacticalMandateResolver resolver = new ChairmanTacticalMandateResolver(
            Map.of("442", new int[]{1, 3, 5}, "433", new int[]{1, 3, 7}));

    @Test
    void overlaysZeroAndElevenSlotsWithoutChangingInputAndSorts() {
        List<ChairmanTacticalMandateResolver.ProposedSlot> input = List.of(
                new ChairmanTacticalMandateResolver.ProposedSlot(5, 20),
                new ChairmanTacticalMandateResolver.ProposedSlot(1, 10));
        var zero = resolver.resolve("442", input, new ChairmanTacticalMandateResolver.Mandate(null, List.of()));
        assertThat(zero.slots()).containsExactly(
                new ChairmanTacticalMandateResolver.ProposedSlot(1, 10),
                new ChairmanTacticalMandateResolver.ProposedSlot(5, 20));
        assertThat(input).containsExactly(new ChairmanTacticalMandateResolver.ProposedSlot(5, 20),
                new ChairmanTacticalMandateResolver.ProposedSlot(1, 10));

        List<ChairmanTacticalMandateResolver.ProposedSlot> compatibleInput = List.of(
                new ChairmanTacticalMandateResolver.ProposedSlot(1, 10),
                new ChairmanTacticalMandateResolver.ProposedSlot(3, 77));
        var one = resolver.resolve("442", compatibleInput, new ChairmanTacticalMandateResolver.Mandate("433",
                List.of(new ChairmanTacticalMandateResolver.ProposedSlot(7, 99))));
        assertThat(one.slots()).containsExactly(new ChairmanTacticalMandateResolver.ProposedSlot(1, 10),
                new ChairmanTacticalMandateResolver.ProposedSlot(3, 77),
                new ChairmanTacticalMandateResolver.ProposedSlot(7, 99));
    }

    @Test
    void rejectsUnknownFormationDuplicateSlotPlayerAndIncompatibleSlot() {
        assertThatThrownBy(() -> resolver.resolve("bad", List.of(), new ChairmanTacticalMandateResolver.Mandate(null, List.of())))
                .hasFieldOrPropertyWithValue("code", "FORMATION_NOT_FOUND");
        assertThatThrownBy(() -> resolver.resolve("442", List.of(), new ChairmanTacticalMandateResolver.Mandate(null,
                List.of(new ChairmanTacticalMandateResolver.ProposedSlot(1, 9), new ChairmanTacticalMandateResolver.ProposedSlot(1, 10)))))
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_MANDATE_SLOT");
        assertThatThrownBy(() -> resolver.resolve("442", List.of(), new ChairmanTacticalMandateResolver.Mandate(null,
                List.of(new ChairmanTacticalMandateResolver.ProposedSlot(1, 9), new ChairmanTacticalMandateResolver.ProposedSlot(3, 9)))))
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_MANDATE_PLAYER");
        assertThatThrownBy(() -> resolver.resolve("442", List.of(), new ChairmanTacticalMandateResolver.Mandate("433",
                List.of(new ChairmanTacticalMandateResolver.ProposedSlot(5, 9)))))
                .hasFieldOrPropertyWithValue("code", "MANDATE_SLOT_NOT_IN_FORMATION");
    }

    @Test
    void removesManagerSlotAndMandatedPlayerBeforeAddingMandate() {
        var result = resolver.resolve("442", List.of(
                new ChairmanTacticalMandateResolver.ProposedSlot(1, 99),
                new ChairmanTacticalMandateResolver.ProposedSlot(3, 77),
                new ChairmanTacticalMandateResolver.ProposedSlot(5, 88)),
                new ChairmanTacticalMandateResolver.Mandate(null,
                        List.of(new ChairmanTacticalMandateResolver.ProposedSlot(3, 99))));
        assertThat(result.slots()).containsExactly(
                new ChairmanTacticalMandateResolver.ProposedSlot(3, 99),
                new ChairmanTacticalMandateResolver.ProposedSlot(5, 88));
    }
}
