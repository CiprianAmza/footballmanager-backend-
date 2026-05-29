package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EuropeanCompetitionService#isKnockoutRound}. Pure
 * logic + a single repository lookup — kept as a fast unit test (no
 * Spring context) so refactors of the round-classification rules trip
 * here before the full integration suite catches them.
 *
 * <p>Rules under test (per the service Javadoc):
 * <ul>
 *   <li>Cups (typeId 2): always knockout, regardless of round.</li>
 *   <li>Stars Cup (typeId 5): groups for rounds 1-6, knockout from round 7+.</li>
 *   <li>LoC (typeId 4): knockout for rounds 0-1 and 8+; groups in between.</li>
 *   <li>Anything else: not knockout.</li>
 * </ul>
 */
class EuropeanCompetitionServiceTest {

    private EuropeanCompetitionService service;
    private CompetitionRepository competitionRepository;

    private static final long CUP_ID = 100L;
    private static final long STARS_CUP_ID = 200L;
    private static final long LOC_ID = 300L;
    private static final long LEAGUE_ID = 400L;

    @BeforeEach
    void setUp() throws Exception {
        service = new EuropeanCompetitionService();
        competitionRepository = mock(CompetitionRepository.class);
        inject("competitionRepository", competitionRepository);
        // Round classification is config-driven; wire the real registry.
        inject("competitionFormat", new CompetitionFormatConfig());

        // Seed: 4 competitions covering all the relevant typeIds.
        when(competitionRepository.findAll()).thenReturn(List.of(
                competition(LEAGUE_ID,    1), // league
                competition(CUP_ID,       2), // cup
                competition(LOC_ID,       4), // LoC
                competition(STARS_CUP_ID, 5)  // Stars Cup
        ));
        // formatOf() resolves a competition's typeId via findById.
        when(competitionRepository.findById(LEAGUE_ID)).thenReturn(Optional.of(competition(LEAGUE_ID, 1)));
        when(competitionRepository.findById(CUP_ID)).thenReturn(Optional.of(competition(CUP_ID, 2)));
        when(competitionRepository.findById(LOC_ID)).thenReturn(Optional.of(competition(LOC_ID, 4)));
        when(competitionRepository.findById(STARS_CUP_ID)).thenReturn(Optional.of(competition(STARS_CUP_ID, 5)));
    }

    // ---------------- cups ----------------

    @Test
    void cup_alwaysKnockout_regardlessOfRound() {
        assertTrue(service.isKnockoutRound(CUP_ID, 0));
        assertTrue(service.isKnockoutRound(CUP_ID, 1));
        assertTrue(service.isKnockoutRound(CUP_ID, 5));
        assertTrue(service.isKnockoutRound(CUP_ID, 99));
    }

    // ---------------- LoC ----------------

    @Test
    void loc_round0_isKnockout_preliminary() {
        assertTrue(service.isKnockoutRound(LOC_ID, 0), "LoC round 0 = preliminary (knockout)");
    }

    @Test
    void loc_round1_isKnockout_qualifying() {
        assertTrue(service.isKnockoutRound(LOC_ID, 1), "LoC round 1 = qualifying (knockout)");
    }

    @Test
    void loc_rounds2to7_areGroupStage() {
        for (long r = 2; r <= 7; r++) {
            assertFalse(service.isKnockoutRound(LOC_ID, r),
                    "LoC round " + r + " should be group stage");
        }
    }

    @Test
    void loc_rounds8to10_areKnockout() {
        assertTrue(service.isKnockoutRound(LOC_ID, 8),  "LoC round 8 = QF");
        assertTrue(service.isKnockoutRound(LOC_ID, 9),  "LoC round 9 = SF");
        assertTrue(service.isKnockoutRound(LOC_ID, 10), "LoC round 10 = Final");
    }

    // ---------------- Stars Cup ----------------

    @Test
    void starsCup_rounds1to6_areGroupStage() {
        for (long r = 1; r <= 6; r++) {
            assertFalse(service.isKnockoutRound(STARS_CUP_ID, r),
                    "Stars Cup round " + r + " should be group stage");
        }
    }

    @Test
    void starsCup_round7_isPlayoffKnockout() {
        assertTrue(service.isKnockoutRound(STARS_CUP_ID, 7), "Stars Cup round 7 = playoff (knockout)");
    }

    @Test
    void starsCup_rounds8to10_areKnockout() {
        assertTrue(service.isKnockoutRound(STARS_CUP_ID, 8),  "Stars Cup round 8 = QF");
        assertTrue(service.isKnockoutRound(STARS_CUP_ID, 9),  "Stars Cup round 9 = SF");
        assertTrue(service.isKnockoutRound(STARS_CUP_ID, 10), "Stars Cup round 10 = Final");
    }

    // ---------------- leagues (typeId 1) ----------------

    @Test
    void league_neverKnockout() {
        for (long r = 0; r <= 30; r++) {
            assertFalse(service.isKnockoutRound(LEAGUE_ID, r),
                    "league round " + r + " should never be knockout");
        }
    }

    // ---------------- unknown competition ----------------

    @Test
    void unknownCompetition_notKnockout() {
        // ID that doesn't match any of the seeded competitions falls through
        // every branch and returns false.
        assertFalse(service.isKnockoutRound(999L, 0));
        assertFalse(service.isKnockoutRound(999L, 5));
    }

    // ---------------- helpers ----------------

    private static Competition competition(long id, long typeId) {
        Competition c = new Competition();
        c.setId(id);
        c.setTypeId(typeId);
        return c;
    }

    private void inject(String fieldName, Object value) throws Exception {
        var field = EuropeanCompetitionService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
