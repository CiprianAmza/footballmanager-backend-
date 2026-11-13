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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * End of season: in every league, the top 3 of each statistics category grow
 * ({@link SeasonStatGrowth}) through that category's attributes ({@link PlayerRatingSync#shift}).
 *
 * <p>Sources: goals and assists from the match rows ({@link Scorer}); dribbles, tackles
 * and aerial duels from the season tallies ({@link PlayerSeasonStat}, synthetic); saves from
 * the matches: for the goalkeeper who started, the opponent's shots on target minus the
 * goals he conceded ({@link MatchStats}).
 */
@Service
public class SeasonStatGrowthService {

    @Autowired ScorerRepository scorerRepository;
    @Autowired PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired MatchStatsRepository matchStatsRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired @Lazy GameStateService gameStateService;

    /** The leagues (first and second tier) that reward their best players. */
    Set<Long> leagues() {
        Set<Long> leagues = new HashSet<>(gameStateService.getLeagueCompetitionIdsCached());
        leagues.addAll(gameStateService.getSecondLeagueCompetitionIdsCached());
        return leagues;
    }

    /** Every award of the season, per league and category, computed on the ratings before any growth. */
    public List<Grant> grants(int season) {
        List<Grant> grants = new ArrayList<>();
        Map<Long, Double> ratings = new HashMap<>();
        for (Long league : leagues()) {
            Map<StatCategory, Map<Long, Double>> stats = statsOf(league, season);
            Set<Long> players = new HashSet<>();
            stats.values().forEach(byPlayer -> players.addAll(byPlayer.keySet()));
            players.removeAll(ratings.keySet());
            if (!players.isEmpty()) {
                for (Human player : humanRepository.findAllById(players)) {
                    if (!player.isRetired()) ratings.put(player.getId(), player.getRating());
                }
            }
            stats.forEach((category, byPlayer) -> {
                for (SeasonStatGrowth.Award award : SeasonStatGrowth.awards(byPlayer, ratings)) {
                    grants.add(new Grant(league, category, award));
                }
            });
        }
        return grants;
    }

    /** One award: a league, a category and the player's place and growth. */
    public record Grant(long leagueId, StatCategory category, SeasonStatGrowth.Award award) {}

    /** Applies the season's awards. Returns them, for logging. */
    @Transactional
    public List<Grant> apply(int season, Random rng) {
        List<Grant> grants = grants(season);
        if (grants.isEmpty()) return grants;
        Set<Long> ids = new HashSet<>();
        grants.forEach(grant -> ids.add(grant.award().playerId()));
        Map<Long, Human> players = new HashMap<>();
        humanRepository.findAllById(ids).forEach(player -> players.put(player.getId(), player));
        Map<Long, PlayerSkills> skills = new HashMap<>();
        playerSkillsRepository.findAllByPlayerIdIn(ids).forEach(row -> skills.put(row.getPlayerId(), row));

        Set<Human> changed = new HashSet<>();
        for (Grant grant : grants) {
            Human player = players.get(grant.award().playerId());
            PlayerSkills attributes = skills.get(grant.award().playerId());
            if (player == null || attributes == null) continue;
            PlayerRatingSync.shift(player, attributes, grant.award().ratingGrowth(), grant.category(), rng, season);
            changed.add(player);
        }
        humanRepository.saveAll(changed);
        playerSkillsRepository.saveAll(skills.values());
        return grants;
    }

    /** Category → player → statistic, for one league and season. */
    Map<StatCategory, Map<Long, Double>> statsOf(long league, int season) {
        Map<StatCategory, Map<Long, Double>> stats = new LinkedHashMap<>();
        List<Scorer> matches = scorerRepository.findAllByCompetitionIdAndSeasonNumber(league, season);
        stats.put(StatCategory.GOALS, sum(matches, Scorer::getPlayerId, Scorer::getGoals));
        stats.put(StatCategory.ASSISTS, sum(matches, Scorer::getPlayerId, Scorer::getAssists));
        List<PlayerSeasonStat> tallies = playerSeasonStatRepository.findAllByCompetitionIdAndSeasonNumber(league, season);
        stats.put(StatCategory.DRIBBLES, sum(tallies, PlayerSeasonStat::getPlayerId, PlayerSeasonStat::getDribblesCompleted));
        stats.put(StatCategory.TACKLES_AND_INTERCEPTIONS, sum(tallies, PlayerSeasonStat::getPlayerId, PlayerSeasonStat::getTackles));
        stats.put(StatCategory.AERIAL_DUELS, sum(tallies, PlayerSeasonStat::getPlayerId, PlayerSeasonStat::getAerialDuelsWon));
        stats.put(StatCategory.SAVES, saves(matches,
                matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(league, season)));
        return stats;
    }

    /**
     * Goalkeeper → saves: in every match he started in goal, the opponent's shots on target
     * minus the goals the opponent scored (never below 0).
     */
    static Map<Long, Double> saves(Collection<Scorer> rows, Collection<MatchStats> matchStats) {
        Map<String, Integer> savesByRoundAndTeam = new HashMap<>();
        for (MatchStats match : matchStats) {
            savesByRoundAndTeam.put(match.getRoundNumber() + ":" + match.getTeam1Id(),
                    Math.max(0, match.getAwayShotsOnTarget() - match.getAwayGoals()));
            savesByRoundAndTeam.put(match.getRoundNumber() + ":" + match.getTeam2Id(),
                    Math.max(0, match.getHomeShotsOnTarget() - match.getHomeGoals()));
        }
        Map<Long, Double> saves = new HashMap<>();
        for (Scorer row : rows) {
            if (row.isSubstitute() || !"GK".equals(row.getPosition())) continue;
            Integer made = savesByRoundAndTeam.get(row.getRoundNumber() + ":" + row.getTeamId());
            if (made != null) saves.merge(row.getPlayerId(), (double) made, Double::sum);
        }
        return saves;
    }

    private static <T> Map<Long, Double> sum(Collection<T> rows, java.util.function.ToLongFunction<T> player,
                                             ToDoubleFunction<T> value) {
        Map<Long, Double> totals = new HashMap<>();
        for (T row : rows) totals.merge(player.applyAsLong(row), value.applyAsDouble(row), Double::sum);
        return totals;
    }
}
