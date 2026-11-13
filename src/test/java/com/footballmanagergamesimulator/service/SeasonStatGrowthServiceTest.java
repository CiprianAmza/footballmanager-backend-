package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeasonStatGrowthServiceTest {

    private static final long LEAGUE = 1L;
    private static final int SEASON = 4;

    private final SeasonStatGrowthService service = new SeasonStatGrowthService();
    private final Map<Long, Human> players = new HashMap<>();
    private final Map<Long, PlayerSkills> skills = new HashMap<>();
    private final List<Scorer> matches = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service.gameStateService = mock(GameStateService.class);
        when(service.gameStateService.getLeagueCompetitionIdsCached()).thenReturn(Set.of(LEAGUE));
        when(service.gameStateService.getSecondLeagueCompetitionIdsCached()).thenReturn(Set.of());
        service.scorerRepository = mock(ScorerRepository.class);
        when(service.scorerRepository.findAllByCompetitionIdAndSeasonNumber(LEAGUE, SEASON)).thenReturn(matches);
        service.playerSeasonStatRepository = mock(PlayerSeasonStatRepository.class);
        when(service.playerSeasonStatRepository.findAllByCompetitionIdAndSeasonNumber(LEAGUE, SEASON))
                .thenReturn(List.<PlayerSeasonStat>of());
        service.matchStatsRepository = mock(MatchStatsRepository.class);
        when(service.matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(LEAGUE, SEASON))
                .thenReturn(List.<MatchStats>of());
        service.humanRepository = mock(HumanRepository.class);
        when(service.humanRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Human> found = new ArrayList<>();
            ((Iterable<Long>) invocation.getArgument(0)).forEach(id -> {
                if (players.containsKey(id)) found.add(players.get(id));
            });
            return found;
        });
        service.playerSkillsRepository = mock(PlayerSkillsRepository.class);
        when(service.playerSkillsRepository.findAllByPlayerIdIn(any())).thenAnswer(invocation -> {
            List<PlayerSkills> found = new ArrayList<>();
            ((Iterable<Long>) invocation.getArgument(0)).forEach(id -> {
                if (skills.containsKey(id)) found.add(skills.get(id));
            });
            return found;
        });
    }

    /** A striker with every attribute at {@code value}, who scored {@code goals} over two matches. */
    private void striker(long id, int value, int goals) {
        PlayerSkills attributes = new PlayerSkills();
        attributes.setPlayerId(id);
        attributes.setPosition("ST");
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(attributes, value));
        skills.put(id, attributes);
        Human player = new Human();
        player.setId(id);
        player.setPosition("ST");
        player.setRating(PlayerSkillsService.computeOverallRating(attributes));
        players.put(id, player);
        for (int part : new int[]{goals / 2, goals - goals / 2}) {
            Scorer row = new Scorer();
            row.setPlayerId(id);
            row.setCompetitionId(LEAGUE);
            row.setSeasonNumber(SEASON);
            row.setGoals(part);
            matches.add(row);
        }
    }

    @Test
    @DisplayName("""
            GIVEN a league's 10 scorers, the top scorer being a modest player (attributes 8, the rest 12)
            WHEN the season's growth is applied
            THEN the top 3 grow, the 1st by about 15% of the others' average rating and through goal attributes only;
                 the 4th does not grow
            """)
    void topScorersGrowThroughGoalAttributes() {
        striker(1L, 8, 30);
        for (long id = 2; id <= 10; id++) striker(id, 12, 30 - (int) id * 2);
        double leaderBefore = players.get(1L).getRating();
        double othersRating = players.get(2L).getRating();

        List<SeasonStatGrowthService.Grant> grants = service.apply(SEASON, new Random(1));

        assertEquals(3, grants.size());
        assertEquals(leaderBefore + 0.15 * othersRating, players.get(1L).getRating(), 2.0);
        PlayerSkillsService.GETTER_MAP.forEach((name, getter) -> {
            if (!StatCategory.GOALS.attributes().contains(name)) assertEquals(8, getter.apply(skills.get(1L)), name);
        });
        assertEquals(othersRating, players.get(4L).getRating(), 0.0);
    }

    private static Scorer appearance(long player, long team, int round, String position, boolean substitute) {
        Scorer row = new Scorer();
        row.setPlayerId(player);
        row.setTeamId(team);
        row.setRoundNumber(round);
        row.setPosition(position);
        row.setSubstitute(substitute);
        return row;
    }

    private static MatchStats match(int round, long home, long away, int homeOnTarget, int homeGoals,
                                    int awayOnTarget, int awayGoals) {
        MatchStats match = new MatchStats();
        match.setRoundNumber(round);
        match.setTeam1Id(home);
        match.setTeam2Id(away);
        match.setHomeShotsOnTarget(homeOnTarget);
        match.setHomeGoals(homeGoals);
        match.setAwayShotsOnTarget(awayOnTarget);
        match.setAwayGoals(awayGoals);
        return match;
    }

    @Test
    @DisplayName("""
            GIVEN keeper 1 (team 10) starting rounds 1 and 2, keeper 2 (team 20) starting round 1,
             keeper 3 only on the bench, an outfield player, and a round where the opponent scored
             more goals than it had shots on target
            WHEN the saves are counted
            THEN each starting keeper gets the opponent's shots on target minus its goals, never below 0,
             and the bench keeper and the outfield player get nothing
            """)
    void savesComeFromTheOpponentsShotsOnTargetMinusGoals() {
        List<Scorer> rows = List.of(
                appearance(1L, 10L, 1, "GK", false),
                appearance(2L, 20L, 1, "GK", false),
                appearance(3L, 20L, 1, "GK", true),
                appearance(4L, 10L, 1, "DC", false),
                appearance(1L, 10L, 2, "GK", false));
        List<MatchStats> stats = List.of(
                match(1, 10L, 20L, 7, 1, 5, 2),
                match(2, 30L, 10L, 1, 2, 4, 0));

        Map<Long, Double> saves = SeasonStatGrowthService.saves(rows, stats);

        assertEquals(Map.of(1L, 3.0, 2L, 6.0), saves);
    }
}
