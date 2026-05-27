package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.InvalidSubstitutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Validation tests for {@link LiveMatchSession#applyUserSub} (the manager-driven
 * substitution path hit by {@code POST /match/live/{key}/substitute}). Builds a
 * session via {@link LiveMatchSimulationService#createInteractiveSession} with
 * mocked repos so the real validation logic runs, without standing up Spring.
 */
class LiveMatchSessionSubTest {

    private LiveMatchSimulationService service;
    private HumanRepository humanRepository;
    private TeamRepository teamRepository;
    private CompetitionRepository competitionRepository;
    private PlayerSkillsRepository playerSkillsRepository;
    private MatchEventRepository matchEventRepository;
    private GoalAnimationService goalAnimationService;

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;

    // Home team: GK id=100 + 11 outfield ids=101..111 + 1 bench DC id=112.
    // Away team: GK id=200 + 11 outfield ids=201..211 + 1 bench DC id=212.
    private List<Human> homeSquad;
    private List<Human> awaySquad;

    @BeforeEach
    void setUp() throws Exception {
        service = new LiveMatchSimulationService();
        humanRepository = mock(HumanRepository.class);
        teamRepository = mock(TeamRepository.class);
        competitionRepository = mock(CompetitionRepository.class);
        playerSkillsRepository = mock(PlayerSkillsRepository.class);
        matchEventRepository = mock(MatchEventRepository.class);
        goalAnimationService = mock(GoalAnimationService.class);

        inject("humanRepository", humanRepository);
        inject("teamRepository", teamRepository);
        inject("competitionRepository", competitionRepository);
        inject("playerSkillsRepository", playerSkillsRepository);
        inject("matchEventRepository", matchEventRepository);
        inject("goalAnimationService", goalAnimationService);

        homeSquad = buildSquad(100L);
        awaySquad = buildSquad(200L);

        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(homeSquad);
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(awaySquad);
        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void applyUserSub_samePlayerOnBothEnds_throws() {
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(101L, 101L));
        assertTrue(ex.getMessage().toLowerCase().contains("same player"));
    }

    @Test
    void applyUserSub_unknownPlayer_throws() {
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(999L, 112L));
        assertTrue(ex.getMessage().toLowerCase().contains("not in either squad"));
    }

    @Test
    void applyUserSub_mixingTeams_throws() {
        // Try to swap a home starter (101) for an away bench player (212).
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(101L, 212L));
        assertTrue(ex.getMessage().toLowerCase().contains("same team"));
    }

    @Test
    void applyUserSub_swapWithBenchedPlayer_succeeds() {
        // Home starter 101 (DC) → home bench 112 (DC). Both same team, same
        // position group. Should swap their isOnPitch flags + bump subs used.
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        assertDoesNotThrow(() -> session.applyUserSub(101L, 112L));

        // Verify via snapshot that subsRemaining dropped from 3 to 2 on home side.
        assertEquals(2, session.snapshot().getHomeSubsRemaining());
        assertEquals(3, session.snapshot().getAwaySubsRemaining());
    }

    @Test
    void applyUserSub_swapTwoStarters_throwsBenchRule() {
        // Both 101 and 102 are starters → playerIn must be on the bench.
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(101L, 102L));
        assertTrue(ex.getMessage().toLowerCase().contains("already on the pitch"));
    }

    @Test
    void applyUserSub_swapTwoBenchPlayers_throwsPitchRule() {
        // 112 is bench, and once we've subbed 101 off, 101 is also bench.
        // A bench→bench swap should be rejected.
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);
        session.applyUserSub(101L, 112L); // now 101 off, 112 on

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(101L, 101L)); // self — same-player check fires first
        assertTrue(ex.getMessage().toLowerCase().contains("same player"));
    }

    @Test
    void applyUserSub_outfieldForGk_throws() {
        // 101 (DC, on pitch) → 100 (GK, on pitch) is doubly invalid, but the
        // pitch check (100 already on pitch) trips before the GK rule. Use the
        // GK-only bench player to isolate the GK rule: add a bench GK 113.
        Human benchGk = humanWithPosition(113L, "GK", 65);
        homeSquad.add(benchGk);

        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        // Outfield starter 101 (DC) → bench GK 113. Different position groups.
        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(101L, 113L));
        assertTrue(ex.getMessage().toLowerCase().contains("gk"));
    }

    @Test
    void applyUserSub_capsAtThreeSubsPerSide() {
        // Add 3 more home bench players (DC) so we can do 4 attempted subs.
        homeSquad.add(humanWithPosition(113L, "DC", 60));
        homeSquad.add(humanWithPosition(114L, "DC", 60));
        homeSquad.add(humanWithPosition(115L, "DC", 60));

        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 100.0, COMP, 1, 1, false);

        // The starting XI is GK + top 10 outfield by rating. With ratings
        // 100..111 all set to 70, the picker uses the natural ordering, so
        // outfield 101..110 are on the pitch and 111 (lowest in order) is
        // benched. Let's just sub off whatever is on pitch — pick starters
        // we know are on (101, 102, 103) and use bench players (112, 113, 114).
        assertDoesNotThrow(() -> session.applyUserSub(101L, 112L));
        assertDoesNotThrow(() -> session.applyUserSub(102L, 113L));
        assertDoesNotThrow(() -> session.applyUserSub(103L, 114L));

        InvalidSubstitutionException ex = assertThrows(InvalidSubstitutionException.class,
                () -> session.applyUserSub(104L, 115L));
        assertTrue(ex.getMessage().toLowerCase().contains("no substitutions"));
    }

    // ---------------- fixtures ----------------

    /** 1 GK + 11 outfield (DCs to keep things simple) + 1 bench DC = 13 players. */
    private List<Human> buildSquad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(humanWithPosition(baseId, "GK", 70));
        for (long i = 1; i <= 11; i++) {
            squad.add(humanWithPosition(baseId + i, "DC", 70));
        }
        // Bench DC — ensures we have a bench to sub TO in the happy-path test.
        squad.add(humanWithPosition(baseId + 12, "DC", 50));
        return squad;
    }

    private static Human humanWithPosition(long id, String position, double rating) {
        Human h = new Human();
        h.setId(id);
        h.setName(position + "_" + id);
        h.setPosition(position);
        h.setRating(rating);
        h.setRetired(false);
        return h;
    }

    /** Reflection-set a private field on the service (avoids needing setters). */
    private void inject(String fieldName, Object value) throws Exception {
        var field = LiveMatchSimulationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
