package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps the denormalized all-time scorer table aligned with the player table.
 *
 * <p>The match engine used to update only leaderboard rows that already
 * existed. Players generated after the initial bootstrap (admin players,
 * academy promotions and regens) could therefore collect real {@code Scorer}
 * appearances without ever appearing in the Golden Boot tracker. This service
 * repairs those saves from the appearance table and also refreshes metadata
 * such as age, current rating and club.</p>
 */
@Service
public class ScorerLeaderboardSyncService {

    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final ScorerRepository scorerRepository;
    private final ScorerLeaderboardRepository leaderboardRepository;
    private final RoundRepository roundRepository;

    public ScorerLeaderboardSyncService(HumanRepository humanRepository,
                                        TeamRepository teamRepository,
                                        ScorerRepository scorerRepository,
                                        ScorerLeaderboardRepository leaderboardRepository,
                                        RoundRepository roundRepository) {
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.scorerRepository = scorerRepository;
        this.leaderboardRepository = leaderboardRepository;
        this.roundRepository = roundRepository;
    }

    /**
     * Repairs missing rows, backfills their historic totals and refreshes live
     * metadata. The expensive scorer aggregation runs only while a save has at
     * least one missing row; normal tracker requests remain cheap afterwards.
     */
    @Transactional
    public Map<Long, ScorerLeaderboardEntry> synchronizeAllPlayers() {
        int currentSeason = currentSeason();
        List<Human> players = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Map<Long, ScorerLeaderboardEntry> existingByPlayer = new HashMap<>();
        for (ScorerLeaderboardEntry entry : leaderboardRepository.findAll()) {
            existingByPlayer.putIfAbsent(entry.getPlayerId(), entry);
        }

        boolean hasMissingRows = players.stream()
                .anyMatch(player -> !existingByPlayer.containsKey(player.getId()));
        Map<Long, ScorerRepository.LeaderboardAggregate> aggregatesByPlayer = new HashMap<>();
        if (hasMissingRows) {
            for (ScorerRepository.LeaderboardAggregate aggregate
                    : scorerRepository.aggregateAllForLeaderboard(currentSeason)) {
                aggregatesByPlayer.put(aggregate.getPlayerId(), aggregate);
            }
        }

        Map<Long, String> teamNames = new HashMap<>();
        for (Team team : teamRepository.findAll()) {
            teamNames.put(team.getId(), team.getName());
        }

        List<ScorerLeaderboardEntry> changed = new ArrayList<>();
        Map<Long, ScorerLeaderboardEntry> result = new LinkedHashMap<>();
        for (Human player : players) {
            ScorerLeaderboardEntry entry = existingByPlayer.get(player.getId());
            boolean isNew = entry == null;
            if (isNew) {
                entry = new ScorerLeaderboardEntry();
                entry.setPlayerId(player.getId());
                applyAggregate(entry, aggregatesByPlayer.get(player.getId()));
            }

            boolean metadataChanged = synchronizeMetadata(entry, player, teamNames, currentSeason, isNew);
            if (isNew || metadataChanged) {
                changed.add(entry);
            }
            result.put(player.getId(), entry);
        }

        if (!changed.isEmpty()) {
            leaderboardRepository.saveAll(changed);
        }
        return result;
    }

    /** Creates the zeroed tracker row immediately for a newly-created player. */
    @Transactional
    public ScorerLeaderboardEntry trackNewPlayer(Human player) {
        ScorerLeaderboardEntry entry = leaderboardRepository.findByPlayerId(player.getId())
                .orElseGet(ScorerLeaderboardEntry::new);
        if (entry.getPlayerId() == 0) {
            entry.setPlayerId(player.getId());
        }
        Map<Long, String> teamNames = new HashMap<>();
        if (player.getTeamId() != null) {
            teamNames.put(player.getTeamId(), teamRepository.findNameById(player.getTeamId()));
        }
        synchronizeMetadata(entry, player, teamNames, currentSeason(), true);
        return leaderboardRepository.save(entry);
    }

    private int currentSeason() {
        return roundRepository.findById(1L)
                .map(round -> Math.toIntExact(round.getSeason()))
                .orElse(1);
    }

    private boolean synchronizeMetadata(ScorerLeaderboardEntry entry,
                                        Human player,
                                        Map<Long, String> teamNames,
                                        int currentSeason,
                                        boolean isNew) {
        boolean changed = false;
        changed |= setIfDifferent(entry.getName(), player.getName(), entry::setName);
        changed |= setIfDifferent(entry.getPosition(), player.getPosition(), entry::setPosition);
        changed |= setIfDifferent(entry.getAge(), player.getAge(), entry::setAge);
        changed |= setIfDifferent(entry.getCurrentRating(), player.getRating(), entry::setCurrentRating);
        changed |= setIfDifferent(entry.isActive(), !player.isRetired(), entry::setActive);

        if (!player.isRetired()) {
            long desiredTeamId = player.getTeamId() == null ? 0L : player.getTeamId();
            String desiredTeamName = player.getTeamId() == null
                    ? "Free Agent"
                    : teamNames.getOrDefault(player.getTeamId(), "Unknown club");
            changed |= setIfDifferent(entry.getTeamId(), desiredTeamId, entry::setTeamId);
            changed |= setIfDifferent(entry.getTeamName(), desiredTeamName, entry::setTeamName);
        } else if (isNew) {
            // Existing retired entries deliberately keep the last club shown in
            // all-time history. A repaired row has no such snapshot available.
            changed |= setIfDifferent(entry.getTeamId(), 0L, entry::setTeamId);
            changed |= setIfDifferent(entry.getTeamName(), "Retired", entry::setTeamName);
        }

        double humanPeak = Math.max(player.getBestEverRating(), player.getRating());
        if (humanPeak > entry.getBestEverRating()) {
            entry.setBestEverRating(humanPeak);
            int peakSeason = player.getBestEverRating() >= player.getRating()
                    && player.getSeasonOfBestEverRating() > 0
                    ? player.getSeasonOfBestEverRating()
                    : currentSeason;
            entry.setSeasonOfBestEverRating(peakSeason);
            changed = true;
        } else if (entry.getBestEverRating() <= 0 && isNew) {
            entry.setBestEverRating(player.getRating());
            entry.setSeasonOfBestEverRating(
                    player.getSeasonOfBestEverRating() > 0
                            ? player.getSeasonOfBestEverRating()
                            : currentSeason);
            changed = true;
        }
        return changed;
    }

    private void applyAggregate(ScorerLeaderboardEntry entry,
                                ScorerRepository.LeaderboardAggregate aggregate) {
        if (aggregate == null) {
            return;
        }
        entry.setMatches(toInt(aggregate.getMatches()));
        entry.setGoals(toInt(aggregate.getGoals()));
        entry.setLeagueMatches(toInt(aggregate.getLeagueMatches()));
        entry.setLeagueGoals(toInt(aggregate.getLeagueGoals()));
        entry.setCupMatches(toInt(aggregate.getCupMatches()));
        entry.setCupGoals(toInt(aggregate.getCupGoals()));
        entry.setSecondLeagueMatches(toInt(aggregate.getSecondLeagueMatches()));
        entry.setSecondLeagueGoals(toInt(aggregate.getSecondLeagueGoals()));
        entry.setCurrentSeasonGames(toInt(aggregate.getCurrentSeasonGames()));
        entry.setCurrentSeasonGoals(toInt(aggregate.getCurrentSeasonGoals()));
        entry.setCurrentSeasonLeagueGames(toInt(aggregate.getCurrentSeasonLeagueGames()));
        entry.setCurrentSeasonLeagueGoals(toInt(aggregate.getCurrentSeasonLeagueGoals()));
        entry.setCurrentSeasonCupGames(toInt(aggregate.getCurrentSeasonCupGames()));
        entry.setCurrentSeasonCupGoals(toInt(aggregate.getCurrentSeasonCupGoals()));
        entry.setCurrentSeasonSecondLeagueGames(toInt(aggregate.getCurrentSeasonSecondLeagueGames()));
        entry.setCurrentSeasonSecondLeagueGoals(toInt(aggregate.getCurrentSeasonSecondLeagueGoals()));
    }

    private int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

    private <T> boolean setIfDifferent(T current, T desired, Consumer<T> setter) {
        if (Objects.equals(current, desired)) {
            return false;
        }
        setter.accept(desired);
        return true;
    }
}
