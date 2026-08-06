package com.footballmanagergamesimulator.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.footballmanagergamesimulator.model.PredeterminedScore;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link TeamPostMatchService}. Covers
 * {@code calculateMoraleChangeForTeamDifference} without standing up Spring.
 * (Score generation moved entirely to {@link MatchSimulationService}; its
 * contract is covered by {@code MatchSimulationServiceTest}.) The DB-touching
 * methods (updateTeam, updatePlayersMorale, etc.) are exercised through
 * MatchdayInvariantsIT's golden-path coverage.
 */
class TeamPostMatchServiceTest {

    private TeamPostMatchService service;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        service = new TeamPostMatchService();
        entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    // ---------------- calculateMoraleChangeForTeamDifference ----------------

    @Test
    void moraleChange_winningAgainstStrongerTeam_isLargePositive() {
        // teamPowerDifference -700 (we were big underdog) + Win → big morale boost.
        // Range from method: random.nextDouble(5, 10).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("W", -700);
            assertTrue(delta >= 5 && delta < 10,
                    "underdog win morale should be 5..10, got " + delta);
        }
    }

    @Test
    void moraleChange_winningAsHugeFavourite_isSmallPositive() {
        // teamPowerDifference +700 + Win → small morale (expected was a win anyway).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("W", 700);
            assertTrue(delta >= 0 && delta < 1,
                    "favourite win morale should be 0..1, got " + delta);
        }
    }

    @Test
    void moraleChange_losingAsFavourite_isLargeNegative() {
        // teamPowerDifference +700 + Loss → big morale hit.
        // Range: random.nextDouble(-15, -5).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("L", 700);
            assertTrue(delta >= -15 && delta < -5,
                    "favourite loss morale should be -15..-5, got " + delta);
        }
    }

    @Test
    void moraleChange_drawAgainstStrongerTeam_isPositive() {
        // Underdog drawing the favourite should feel like a small win.
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("D", -700);
            assertTrue(delta >= 3 && delta < 7,
                    "underdog draw morale should be 3..7, got " + delta);
        }
    }

    @Test
    void atomicOverrideClaim_consumesMatchingRowExactlyOnce() {
        PredeterminedScoreRepository repository = mock(PredeterminedScoreRepository.class);
        ReflectionTestUtils.setField(service, "predeterminedScoreRepository", repository);
        PredeterminedScore row = override(2, 1);
        when(repository.findForUpdateByFixture(
                7L, 3, 4, 11L, 12L)).thenReturn(Optional.of(row));

        TeamPostMatchService.PredeterminedScoreAttempt first = service.consumePredeterminedScoreIfMatches(
                7L, 3, 4, 11L, 12L, 2, 1);
        TeamPostMatchService.PredeterminedScoreAttempt replay = service.consumePredeterminedScoreIfMatches(
                7L, 3, 4, 11L, 12L, 2, 1);

        assertEquals(TeamPostMatchService.PredeterminedScoreResolution.CONSUMED, first.resolution());
        assertEquals(TeamPostMatchService.PredeterminedScoreResolution.ALREADY_CONSUMED, replay.resolution());
        assertTrue(row.isConsumed());
        verify(repository, times(1)).save(row);
    }

    @Test
    void atomicOverrideClaim_divergentScoreLeavesOverrideUnconsumed() {
        PredeterminedScoreRepository repository = mock(PredeterminedScoreRepository.class);
        ReflectionTestUtils.setField(service, "predeterminedScoreRepository", repository);
        PredeterminedScore row = override(2, 1);
        when(repository.findForUpdateByFixture(
                7L, 3, 4, 11L, 12L)).thenReturn(Optional.of(row));

        TeamPostMatchService.PredeterminedScoreAttempt result = service.consumePredeterminedScoreIfMatches(
                7L, 3, 4, 11L, 12L, 1, 1);

        assertEquals(TeamPostMatchService.PredeterminedScoreResolution.DIVERGENT, result.resolution());
        assertArrayEquals(new int[]{2, 1}, result.score());
        assertFalse(row.isConsumed());
        verify(repository, never()).save(any());
    }

    @Test
    void atomicOverrideClaim_refreshesLockedRowBeforeClassifyingConsumedState() {
        PredeterminedScoreRepository repository = mock(PredeterminedScoreRepository.class);
        ReflectionTestUtils.setField(service, "predeterminedScoreRepository", repository);
        PredeterminedScore row = override(2, 1);
        when(repository.findForUpdateByFixture(
                7L, 3, 4, 11L, 12L)).thenReturn(Optional.of(row));
        doAnswer(invocation -> {
            row.setConsumed(true);
            return null;
        }).when(entityManager).refresh(row);

        TeamPostMatchService.PredeterminedScoreAttempt result = service.consumePredeterminedScoreIfMatches(
                7L, 3, 4, 11L, 12L, 2, 1);

        assertEquals(TeamPostMatchService.PredeterminedScoreResolution.ALREADY_CONSUMED, result.resolution());
        verify(entityManager).refresh(row);
        verify(repository, never()).save(any());
    }

    @Test
    void atomicOverrideClaim_absentIsReplayPermitted() {
        PredeterminedScoreRepository repository = mock(PredeterminedScoreRepository.class);
        ReflectionTestUtils.setField(service, "predeterminedScoreRepository", repository);
        when(repository.findForUpdateByFixture(
                7L, 3, 4, 11L, 12L)).thenReturn(Optional.empty());

        TeamPostMatchService.PredeterminedScoreAttempt result = service.consumePredeterminedScoreIfMatches(
                7L, 3, 4, 11L, 12L, 2, 1);

        assertEquals(TeamPostMatchService.PredeterminedScoreResolution.ABSENT, result.resolution());
        verify(repository, never()).save(any());
    }

    @Test
    void newAdminOverridePolicyAllowsOnlyTheWinnerClaim() {
        assertTrue(MatchRoundSimulator.mayProceedWithNewAdminOverride(
                TeamPostMatchService.PredeterminedScoreResolution.CONSUMED));
        assertFalse(MatchRoundSimulator.mayProceedWithNewAdminOverride(
                TeamPostMatchService.PredeterminedScoreResolution.ABSENT));
        assertFalse(MatchRoundSimulator.mayProceedWithNewAdminOverride(
                TeamPostMatchService.PredeterminedScoreResolution.ALREADY_CONSUMED));
        assertFalse(MatchRoundSimulator.mayProceedWithNewAdminOverride(
                TeamPostMatchService.PredeterminedScoreResolution.DIVERGENT));
    }

    @Test
    void matchReportInboxCarriesCanonicalFixtureReferenceAndEditorialCopy() {
        TeamRepository teams = mock(TeamRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        RoundRepository rounds = mock(RoundRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ManagerInboxRepository inboxes = mock(ManagerInboxRepository.class);
        UserContext userContext = mock(UserContext.class);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "competitionRepository", competitions);
        ReflectionTestUtils.setField(service, "roundRepository", rounds);
        ReflectionTestUtils.setField(service, "scorerRepository", scorers);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "managerInboxRepository", inboxes);
        ReflectionTestUtils.setField(service, "userContext", userContext);
        ReflectionTestUtils.setField(service, "mediaNarrativeService", mock(MediaNarrativeService.class));
        ReflectionTestUtils.setField(service, "formerPlayerStatementService", mock(FormerPlayerStatementService.class));
        ReflectionTestUtils.setField(service, "formerManagerStatementService", mock(FormerManagerStatementService.class));
        ReflectionTestUtils.setField(service, "fanSocialFeedService", mock(FanSocialFeedService.class));

        Team home = new Team(); home.setName("Sherlock FC");
        Team away = new Team(); away.setName("Yu Gi Oh");
        Competition competition = new Competition(); competition.setName("Premier Division");
        Round round = new Round(); round.setSeason(4L);
        Scorer scorer = new Scorer();
        scorer.setPlayerId(3386L); scorer.setTeamId(86L); scorer.setOpponentTeamId(91L);
        scorer.setCompetitionId(8L); scorer.setSeasonNumber(4); scorer.setRoundNumber(12);
        scorer.setTeamScore(2); scorer.setOpponentScore(1); scorer.setGoals(2);
        Human player = new Human(); player.setName("Ilerande");

        when(userContext.isHumanTeam(86L)).thenReturn(true);
        when(userContext.isHumanTeam(91L)).thenReturn(false);
        when(teams.findById(86L)).thenReturn(Optional.of(home));
        when(teams.findById(91L)).thenReturn(Optional.of(away));
        when(competitions.findById(8L)).thenReturn(Optional.of(competition));
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(scorers.findAllByTeamIdAndSeasonNumber(86L, 4)).thenReturn(List.of(scorer));
        when(humans.findById(3386L)).thenReturn(Optional.of(player));

        service.generateMatchReport(8L, 12L, 86L, 91L, 2, 1);

        ArgumentCaptor<ManagerInbox> saved = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inboxes).save(saved.capture());
        assertEquals("match_result", saved.getValue().getCategory());
        assertEquals("MATCH_REPORT_V1|8|4|12|86|91|86", saved.getValue().getDeduplicationKey());
        assertTrue(saved.getValue().getContent().contains("claimed a 2-1 victory"));
        assertTrue(saved.getValue().getContent().contains("Ilerande (2)"));
        assertTrue(saved.getValue().getContent().contains("xG"));
    }

    private static PredeterminedScore override(int home, int away) {
        PredeterminedScore row = new PredeterminedScore();
        row.setTeam1Score(home);
        row.setTeam2Score(away);
        return row;
    }
}
