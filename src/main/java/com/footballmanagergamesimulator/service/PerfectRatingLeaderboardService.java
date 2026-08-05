package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the all-time leaderboard of displayed 10.0 match performances. */
@Service
public class PerfectRatingLeaderboardService {

    private final ScorerRepository scorerRepository;
    private final HumanRepository humanRepository;

    public PerfectRatingLeaderboardService(ScorerRepository scorerRepository,
                                           HumanRepository humanRepository) {
        this.scorerRepository = scorerRepository;
        this.humanRepository = humanRepository;
    }

    @Transactional(readOnly = true)
    public PerfectRatingLeaderboard leaderboard(Long competitionId, String level, int requestedLimit) {
        Long selectedCompetitionId = competitionId != null && competitionId > 0 ? competitionId : null;
        PerfectRatingLevel selectedLevel = PerfectRatingLevel.from(level);
        int limit = Math.max(1, Math.min(200, requestedLimit));

        List<ScorerRepository.PerfectRatingAggregate> all = scorerRepository.aggregatePerfectRatings();
        List<CompetitionOption> competitions = competitionOptions(all);
        Map<Long, String> competitionNames = competitions.stream().collect(Collectors.toMap(
                CompetitionOption::competitionId, CompetitionOption::competitionName));

        List<ScorerRepository.PerfectRatingAggregate> filtered = all.stream()
                .filter(row -> selectedCompetitionId == null
                        ? selectedLevel.includes(safeInt(row.getCompetitionTypeId()))
                        : selectedCompetitionId.equals(row.getCompetitionId()))
                .toList();

        Map<Long, PlayerAccumulator> byPlayer = new HashMap<>();
        for (ScorerRepository.PerfectRatingAggregate row : filtered) {
            byPlayer.computeIfAbsent(row.getPlayerId(), PlayerAccumulator::new).add(row);
        }

        Map<Long, Human> humans = humanRepository.findAllById(byPlayer.keySet()).stream()
                .collect(Collectors.toMap(Human::getId, Function.identity()));

        List<PlayerAccumulator> ordered = byPlayer.values().stream()
                .sorted(Comparator.comparingLong(PlayerAccumulator::perfectRatings).reversed()
                        .thenComparing(Comparator.comparingInt(PlayerAccumulator::lastSeason).reversed())
                        .thenComparing(player -> playerName(player.playerId, humans), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(player -> player.playerId))
                .limit(limit)
                .toList();

        List<PerfectRatingLeader> leaders = new ArrayList<>();
        long previousCount = Long.MIN_VALUE;
        int rank = 0;
        for (int index = 0; index < ordered.size(); index++) {
            PlayerAccumulator player = ordered.get(index);
            if (player.perfectRatings != previousCount) rank = index + 1;
            previousCount = player.perfectRatings;
            leaders.add(player.toLeader(rank, playerName(player.playerId, humans)));
        }

        long totalPerfectRatings = byPlayer.values().stream().mapToLong(PlayerAccumulator::perfectRatings).sum();
        String competitionName = selectedCompetitionId == null
                ? null : competitionNames.getOrDefault(selectedCompetitionId, "Competition #" + selectedCompetitionId);
        String scope = selectedCompetitionId != null ? "COMPETITION" : selectedLevel.name();

        return new PerfectRatingLeaderboard(
                scope,
                selectedLevel.name(),
                selectedCompetitionId,
                competitionName,
                totalPerfectRatings,
                byPlayer.size(),
                leaders,
                competitions,
                "Counts all saved player appearances whose one-decimal displayed match rating is 10.0 "
                        + "(stored rating 9.95–10.0). CHAMPIONSHIP includes league types 1 and legacy 3; "
                        + "CUP includes all other saved cup formats."
        );
    }

    private List<CompetitionOption> competitionOptions(List<ScorerRepository.PerfectRatingAggregate> rows) {
        Map<Long, CompetitionAccumulator> options = new LinkedHashMap<>();
        for (ScorerRepository.PerfectRatingAggregate row : rows) {
            options.computeIfAbsent(row.getCompetitionId(), ignored -> new CompetitionAccumulator(
                    row.getCompetitionId(), nonBlank(row.getCompetitionName(), "Competition #" + row.getCompetitionId()),
                    PerfectRatingLevel.forType(safeInt(row.getCompetitionTypeId()))
            )).add(row);
        }
        return options.values().stream()
                .map(CompetitionAccumulator::toOption)
                .sorted(Comparator.comparing(CompetitionOption::competitionName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(CompetitionOption::competitionId))
                .toList();
    }

    private static String playerName(long playerId, Map<Long, Human> humans) {
        Human human = humans.get(playerId);
        return human == null ? "Player #" + playerId : nonBlank(human.getName(), "Player #" + playerId);
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    enum PerfectRatingLevel {
        ALL,
        CHAMPIONSHIP,
        CUP;

        static PerfectRatingLevel from(String value) {
            if (value == null || value.isBlank()) return ALL;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ALL;
            }
        }

        static PerfectRatingLevel forType(int competitionTypeId) {
            return competitionTypeId == 1 || competitionTypeId == 3 ? CHAMPIONSHIP : CUP;
        }

        boolean includes(int competitionTypeId) {
            return this == ALL || this == forType(competitionTypeId);
        }
    }

    private static final class PlayerAccumulator {
        private final long playerId;
        private long perfectRatings;
        private long championshipPerfectRatings;
        private long cupPerfectRatings;
        private final Set<Long> competitionIds = new HashSet<>();
        private int firstSeason = Integer.MAX_VALUE;
        private int lastSeason;
        private long latestRowId;
        private long latestTeamId;
        private String latestTeamName = "";

        private PlayerAccumulator(long playerId) {
            this.playerId = playerId;
        }

        private void add(ScorerRepository.PerfectRatingAggregate row) {
            long count = safeLong(row.getPerfectRatings());
            perfectRatings += count;
            if (PerfectRatingLevel.forType(safeInt(row.getCompetitionTypeId())) == PerfectRatingLevel.CHAMPIONSHIP) {
                championshipPerfectRatings += count;
            } else {
                cupPerfectRatings += count;
            }
            competitionIds.add(row.getCompetitionId());
            int season = safeInt(row.getSeasonNumber());
            firstSeason = Math.min(firstSeason, season);
            lastSeason = Math.max(lastSeason, season);
            long rowId = safeLong(row.getLatestRowId());
            if (rowId >= latestRowId) {
                latestRowId = rowId;
                latestTeamId = safeLong(row.getTeamId());
                latestTeamName = nonBlank(row.getTeamName(), "Team #" + latestTeamId);
            }
        }

        private long perfectRatings() {
            return perfectRatings;
        }

        private int lastSeason() {
            return lastSeason;
        }

        private PerfectRatingLeader toLeader(int rank, String playerName) {
            return new PerfectRatingLeader(rank, playerId, playerName, latestTeamId, latestTeamName,
                    perfectRatings, championshipPerfectRatings, cupPerfectRatings,
                    competitionIds.size(), firstSeason == Integer.MAX_VALUE ? 0 : firstSeason, lastSeason);
        }
    }

    private static final class CompetitionAccumulator {
        private final long competitionId;
        private final String competitionName;
        private final PerfectRatingLevel level;
        private long perfectRatings;
        private final Set<Long> playerIds = new HashSet<>();

        private CompetitionAccumulator(long competitionId, String competitionName, PerfectRatingLevel level) {
            this.competitionId = competitionId;
            this.competitionName = competitionName;
            this.level = level;
        }

        private void add(ScorerRepository.PerfectRatingAggregate row) {
            perfectRatings += safeLong(row.getPerfectRatings());
            playerIds.add(row.getPlayerId());
        }

        private CompetitionOption toOption() {
            return new CompetitionOption(competitionId, competitionName, level.name(), perfectRatings, playerIds.size());
        }
    }

    public record PerfectRatingLeaderboard(
            String scope,
            String level,
            Long competitionId,
            String competitionName,
            long totalPerfectRatings,
            int totalPlayers,
            List<PerfectRatingLeader> leaders,
            List<CompetitionOption> competitions,
            String methodology
    ) {}

    public record PerfectRatingLeader(
            int rank,
            long playerId,
            String playerName,
            long teamId,
            String teamName,
            long perfectRatings,
            long championshipPerfectRatings,
            long cupPerfectRatings,
            int competitionCount,
            int firstSeason,
            int lastSeason
    ) {}

    public record CompetitionOption(
            long competitionId,
            String competitionName,
            String level,
            long perfectRatings,
            int players
    ) {}
}
