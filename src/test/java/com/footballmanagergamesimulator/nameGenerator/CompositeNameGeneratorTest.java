package com.footballmanagergamesimulator.nameGenerator;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeNameGeneratorTest {

    private CompetitionRepository competitionRepository;
    private CompositeNameGenerator compositeNameGenerator;

    @BeforeEach
    void setUp() {
        competitionRepository = mock(CompetitionRepository.class);
        when(competitionRepository.findById(anyLong())).thenReturn(Optional.empty());
        compositeNameGenerator = new CompositeNameGenerator(competitionRepository);
    }

    private static Competition competitionOfNation(long nationId) {
        Competition competition = new Competition();
        competition.setNationId(nationId);
        return competition;
    }

    @Test
    void selectsTheStrategyMatchingEachNation() {
        assertInstanceOf(ElevenNameGenerator.class, compositeNameGenerator.strategyFor(1L));
        assertInstanceOf(VardNameGenerator.class, compositeNameGenerator.strategyFor(2L));
        assertInstanceOf(KessNameGenerator.class, compositeNameGenerator.strategyFor(3L));
        assertInstanceOf(LiraNameGenerator.class, compositeNameGenerator.strategyFor(6L));
        assertInstanceOf(MarevalNameGenerator.class, compositeNameGenerator.strategyFor(8L));
        assertInstanceOf(TervalaNameGenerator.class, compositeNameGenerator.strategyFor(9L));
        assertInstanceOf(AltzairNameGenerator.class, compositeNameGenerator.strategyFor(10L));
        assertEquals(NameStyles.VEYRA,
                ((FragmentNameGenerator) compositeNameGenerator.strategyFor(11L)).style());
        assertEquals(NameStyles.BRANIK,
                ((FragmentNameGenerator) compositeNameGenerator.strategyFor(12L)).style());
        assertEquals(NameStyles.SILETH,
                ((FragmentNameGenerator) compositeNameGenerator.strategyFor(13L)).style());
    }

    @Test
    void unknownNationFallsBackToDefaultStrategy() {
        assertInstanceOf(ElevenNameGenerator.class, compositeNameGenerator.strategyFor(0L));
        assertInstanceOf(ElevenNameGenerator.class, compositeNameGenerator.strategyFor(99L));
    }

    @Test
    void resolvesCompetitionIdToItsNation() {
        // Khess Cup: competition 4 belongs to nation 3 in the bootstrap seed.
        when(competitionRepository.findById(4L)).thenReturn(Optional.of(competitionOfNation(3L)));
        assertEquals(3L, compositeNameGenerator.resolveNationId(4L));
        assertInstanceOf(KessNameGenerator.class,
                compositeNameGenerator.strategyFor(compositeNameGenerator.resolveNationId(4L)));
    }

    @Test
    void idWithoutCompetitionRowIsTreatedAsNationId() {
        assertEquals(6L, compositeNameGenerator.resolveNationId(6L));
    }

    @Test
    void generateNameNeverReturnsNullOrEmpty() {
        when(competitionRepository.findById(4L)).thenReturn(Optional.of(competitionOfNation(3L)));
        for (long id : new long[] {1L, 2L, 3L, 4L, 6L, 7L, 42L}) {
            String name = compositeNameGenerator.generateName(id);
            assertNotNull(name);
            assertTrue(!name.isBlank());
        }
    }
}
