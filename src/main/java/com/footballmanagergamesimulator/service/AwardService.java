package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AwardService {

    @Autowired
    AwardRepository awardRepository;
    @Autowired
    AwardOverrideRepository awardOverrideRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired
    MatchStatsRepository matchStatsRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    GameStateService gameStateService;
    @Autowired
    LeagueStrengthService leagueStrengthService;
    @Autowired
    AwardWeightingConfig awardWeightingConfig;

    public List<Award> processAwardsCeremony(int season) {
        List<Award> existingAwards = awardRepository.findAllBySeasonNumber(season);
        List<Award> awards = new ArrayList<>();
        Set<String> existingTypes = existingAwards.stream()
                .map(Award::getAwardType)
                .collect(Collectors.toSet());

        List<Scorer> allScorers =
                scorerRepository.findAllBySeasonNumberAndRoundNumberGreaterThan(season, 0);
        List<Scorer> allCompetitiveScorers = scorerRepository.findAllBySeasonNumber(season).stream()
                .filter(scorer -> scorer.getTeamScore() >= 0 && scorer.getOpponentTeamId() >= 0)
                .toList();

        // Best Player Award: highest combined goals + assists
        Award bestPlayer = existingTypes.contains("BEST_PLAYER") ? null : determineBestPlayer(allScorers, season);
        if (bestPlayer != null) {
            awards.add(bestPlayer);
        }

        // Top Scorer Award: most goals
        Award topScorer = existingTypes.contains("TOP_SCORER") ? null : determineTopScorer(allScorers, season);
        if (topScorer != null) {
            awards.add(topScorer);
        }

        // Best Young Player: best rated player under 21
        Award bestYoungPlayer = existingTypes.contains("BEST_YOUNG_PLAYER")
                ? null : determineBestYoungPlayer(allScorers, season);
        if (bestYoungPlayer != null) {
            awards.add(bestYoungPlayer);
        }

        // Manager of the Year: team that overperformed most
        Award managerOfYear = existingTypes.contains("MANAGER_OF_YEAR") ? null : determineManagerOfYear(season);
        if (managerOfYear != null) {
            awards.add(managerOfYear);
        }

        if (!existingTypes.contains("GOLDEN_BOOT")) {
            Award goldenBoot = determineGoldenBoot(allCompetitiveScorers, season);
            if (goldenBoot != null) awards.add(goldenBoot);
        }
        if (!existingTypes.contains("BALLON_DOR")) {
            Award ballonDor = determineBallonDor(allCompetitiveScorers, season);
            if (ballonDor != null) awards.add(ballonDor);
        }

        if (!awards.isEmpty()) awardRepository.saveAll(awards);

        // Competition-by-competition and global honours. This is deliberately
        // generated at the ceremony (not while opening a page), so a save has a
        // stable historical record even after players transfer or retire.
        awards.addAll(ensureComprehensiveAwardsForSeason(season));

        // Send inbox messages to human managers if their players won
        notifyManagersOfAwards(awards, season);

        return awardRepository.findAllBySeasonNumber(season);
    }

    public List<Award> getAwardsForSeason(int season) {

        return awardRepository.findAllBySeasonNumber(season);
    }

    public List<Award> getPlayerAwards(long playerId) {

        return awardRepository.findAllByWinnerId(playerId);
    }

    /**
     * Admin preview of the same deterministic ballot used by the ceremony.
     * Statistics and voting evidence remain authoritative even when an admin
     * winner override is armed for the season.
     */
    public Map<String, Object> getBallonDorAdminState(int season) {
        BallonDorBallot ballot = calculateBallonDorBallot(competitiveScorers(season), season);
        List<MajorAwardCandidate> ranking = rankBallonDorCandidates(ballot);
        Optional<Award> finalAward = awardRepository
                .findFirstBySeasonNumberAndCompetitionIdAndAwardType(season, 0L, "BALLON_DOR");
        Optional<AwardOverride> override = awardOverrideRepository
                .findBySeasonNumberAndCompetitionIdAndAwardType(season, 0L, "BALLON_DOR");

        Set<Long> playerIds = ranking.stream().map(candidate -> candidate.playerId).collect(Collectors.toSet());
        Set<Long> teamIds = ranking.stream().map(candidate -> candidate.teamId)
                .filter(id -> id > 0).collect(Collectors.toSet());
        Map<Long, Human> people = humanRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Human::getId, player -> player));
        Map<Long, Team> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < ranking.size(); index++) {
            MajorAwardCandidate candidate = ranking.get(index);
            Human player = people.get(candidate.playerId);
            Team team = teams.get(candidate.teamId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", index + 1);
            row.put("playerId", candidate.playerId);
            row.put("playerName", player != null ? player.getName() : candidate.playerName);
            row.put("teamId", candidate.teamId);
            row.put("teamName", team != null ? team.getName() : candidate.teamName);
            row.put("position", candidate.primaryPosition());
            row.put("appearances", candidate.appearances);
            row.put("goals", candidate.goals);
            row.put("assists", candidate.assists);
            row.put("weightedGoals", round(candidate.weightedGoals, 2));
            row.put("weightedAssists", round(candidate.weightedAssists, 2));
            row.put("averageRating", round(candidate.averageRating(), 2));
            row.put("votingPoints", ballot.points().getOrDefault(candidate.playerId, 0));
            row.put("firstPlaceVotes", ballot.firstPlaceVotes().getOrDefault(candidate.playerId, 0));
            row.put("selected", override.map(value -> value.getWinnerId() == candidate.playerId).orElse(false));
            row.put("baseFaceId", player != null ? player.getBaseFaceId() : 0);
            row.put("skinTone", player != null ? player.getSkinTone() : 0);
            row.put("hairStyle", player != null ? player.getHairStyle() : 0);
            row.put("hairColor", player != null ? player.getHairColor() : 0);
            row.put("eyeColor", player != null ? player.getEyeColor() : 0);
            row.put("faceShape", player != null ? player.getFaceShape() : 0);
            row.put("noseShape", player != null ? player.getNoseShape() : 0);
            row.put("eyeShape", player != null ? player.getEyeShape() : 0);
            row.put("mouthShape", player != null ? player.getMouthShape() : 0);
            row.put("browShape", player != null ? player.getBrowShape() : 0);
            row.put("species", player != null ? player.getSpecies() : "human");
            candidates.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", season);
        result.put("finalized", finalAward.isPresent());
        result.put("recommendedWinnerId", ranking.isEmpty() ? null : ranking.get(0).playerId);
        result.put("overrideWinnerId", override.map(AwardOverride::getWinnerId).orElse(null));
        result.put("winnerId", finalAward.map(Award::getWinnerId).orElse(null));
        result.put("adminSelected", finalAward.map(Award::isAdminSelected).orElse(false));
        result.put("candidates", candidates);
        return result;
    }

    @Transactional
    public Map<String, Object> setBallonDorOverride(int season, long winnerId) {
        if (awardRepository.findFirstBySeasonNumberAndCompetitionIdAndAwardType(
                season, 0L, "BALLON_DOR").isPresent()) {
            throw new IllegalArgumentException("The Ballon d'Or for season " + season + " is already finalised");
        }
        BallonDorBallot ballot = calculateBallonDorBallot(competitiveScorers(season), season);
        boolean eligible = ballot.candidates().stream().anyMatch(candidate -> candidate.playerId == winnerId);
        if (!eligible) {
            throw new IllegalArgumentException("Player " + winnerId + " is not an eligible Ballon d'Or candidate");
        }

        AwardOverride override = awardOverrideRepository
                .findBySeasonNumberAndCompetitionIdAndAwardType(season, 0L, "BALLON_DOR")
                .orElseGet(AwardOverride::new);
        override.setSeasonNumber(season);
        override.setCompetitionId(0L);
        override.setAwardType("BALLON_DOR");
        override.setWinnerId(winnerId);
        override.setCreatedAt(System.currentTimeMillis());
        awardOverrideRepository.save(override);
        return getBallonDorAdminState(season);
    }

    @Transactional
    public Map<String, Object> clearBallonDorOverride(int season) {
        if (awardRepository.findFirstBySeasonNumberAndCompetitionIdAndAwardType(
                season, 0L, "BALLON_DOR").isPresent()) {
            throw new IllegalArgumentException("The Ballon d'Or for season " + season + " is already finalised");
        }
        awardOverrideRepository.deleteBySeasonNumberAndCompetitionIdAndAwardType(
                season, 0L, "BALLON_DOR");
        return getBallonDorAdminState(season);
    }

    private List<Scorer> competitiveScorers(int season) {
        return scorerRepository.findAllBySeasonNumber(season).stream()
                .filter(scorer -> scorer.getTeamScore() >= 0 && scorer.getOpponentTeamId() >= 0)
                .toList();
    }

    /** Creates only the two global historical awards, used when an older save predates them. */
    public List<Award> ensureMajorAwardsForSeason(int season) {
        List<Scorer> scorers = scorerRepository.findAllBySeasonNumber(season).stream()
                .filter(scorer -> scorer.getTeamScore() >= 0 && scorer.getOpponentTeamId() >= 0)
                .toList();
        List<Award> created = new ArrayList<>();
        if (!awardRepository.existsBySeasonNumberAndAwardType(season, "GOLDEN_BOOT")) {
            Award award = determineGoldenBoot(scorers, season);
            if (award != null) created.add(award);
        }
        if (!awardRepository.existsBySeasonNumberAndAwardType(season, "BALLON_DOR")) {
            Award award = determineBallonDor(scorers, season);
            if (award != null) created.add(award);
        }
        if (!created.isEmpty()) awardRepository.saveAll(created);
        return created;
    }

    public Map<String, Object> getAwardHistory(String requestedAwardType) {
        String awardType = normalizeMajorAwardType(requestedAwardType);
        backfillCompletedSeasons();
        List<Award> awards = awardRepository
                .findAllByAwardTypeAndCompetitionIdOrderBySeasonNumberDesc(awardType, 0L);

        Set<Long> playerIds = awards.stream().map(Award::getWinnerId).collect(Collectors.toSet());
        Set<Long> teamIds = awards.stream().map(Award::getWinnerTeamId)
                .filter(id -> id > 0).collect(Collectors.toSet());
        Map<Long, Human> players = humanRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Human::getId, player -> player));
        Map<Long, Team> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));

        List<Map<String, Object>> history = awards.stream()
                .map(award -> awardHistoryRow(award, players.get(award.getWinnerId()),
                        teams.get(award.getWinnerTeamId())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("awardType", awardType);
        result.put("title", awardType.equals("GOLDEN_BOOT") ? "Golden Boot" : "Ballon d'Or");
        result.put("description", awardType.equals("GOLDEN_BOOT")
                ? "The season's leading weighted domestic-league goalscorer."
                : "The best overall player, ranked by a deterministic 100-voter journalist panel; an admin selection can override the winner.");
        result.put("rule", awardType.equals("GOLDEN_BOOT")
                ? "First League goals count 1.0; Second League goals count 0.5."
                : "Votes balance ratings, goals, assists, consistency and performances in major matches.");
        result.put("history", history);
        return result;
    }

    public Map<String, Object> getPlayerMajorAwardSummary(long playerId) {
        backfillCompletedSeasons();
        List<Award> awards = awardRepository.findAllByWinnerId(playerId).stream()
                .sorted(Comparator.comparingInt(Award::getSeasonNumber).reversed())
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playerId", playerId);
        result.put("goldenBoots", awards.stream()
                .filter(a -> "GOLDEN_BOOT".equals(a.getAwardType()) && a.getCompetitionId() == 0)
                .count());
        result.put("competitionGoldenBoots", awards.stream()
                .filter(a -> "GOLDEN_BOOT".equals(a.getAwardType()) && a.getCompetitionId() != 0)
                .count());
        result.put("ballonDors", awards.stream().filter(a -> "BALLON_DOR".equals(a.getAwardType())).count());
        result.put("playerOfYearAwards", awards.stream()
                .filter(a -> "PLAYER_OF_YEAR".equals(a.getAwardType())).count());
        result.put("teamOfYearSelections", awards.stream()
                .filter(a -> "TEAM_OF_YEAR".equals(a.getAwardType())).count());
        result.put("awards", awards);
        return result;
    }

    public Map<String, Object> getGlobalAwardCentre() {
        return getAwardCentre(0L, "Global Awards", "GLOBAL");
    }

    public Map<String, Object> getCompetitionAwardCentre(long competitionId) {
        Competition competition = competitionRepository.findById(competitionId)
                .orElseThrow(() -> new IllegalArgumentException("Competition not found: " + competitionId));
        return getAwardCentre(competitionId, competition.getName(), "COMPETITION");
    }

    private Map<String, Object> getAwardCentre(long competitionId, String scopeName, String scopeType) {
        backfillComprehensiveCompletedSeasons();
        List<String> supportedTypes = competitionId == 0
                ? List.of("BALLON_DOR", "PLAYER_OF_YEAR", "TEAM_OF_YEAR", "GOLDEN_BOOT",
                    "MOST_ASSISTS", "BEST_GOALKEEPER", "MOST_ENTERTAINING", "MANAGER_OF_YEAR")
                : List.of("PLAYER_OF_YEAR", "TEAM_OF_YEAR", "GOLDEN_BOOT", "MOST_ASSISTS",
                    "BEST_GOALKEEPER", "MOST_ENTERTAINING", "MANAGER_OF_YEAR");
        List<Award> awards = awardRepository
                .findAllByCompetitionIdOrderBySeasonNumberDescAwardTypeAsc(competitionId).stream()
                .filter(award -> supportedTypes.contains(award.getAwardType()))
                .toList();

        Set<Long> winnerIds = awards.stream().map(Award::getWinnerId).collect(Collectors.toSet());
        Set<Long> teamIds = awards.stream().map(Award::getWinnerTeamId)
                .filter(id -> id > 0).collect(Collectors.toSet());
        Map<Long, Human> people = humanRepository.findAllById(winnerIds).stream()
                .collect(Collectors.toMap(Human::getId, person -> person));
        Map<Long, Team> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));

        Map<Integer, List<Award>> bySeason = awards.stream().collect(Collectors.groupingBy(
                Award::getSeasonNumber, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> seasons = bySeason.entrySet().stream()
                .sorted(Map.Entry.<Integer, List<Award>>comparingByKey().reversed())
                .map(entry -> awardCentreSeason(entry.getKey(), entry.getValue(), supportedTypes, people, teams))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeType", scopeType);
        result.put("scopeId", competitionId);
        result.put("scopeName", scopeName);
        result.put("currentSeason", gameStateService.currentSeason());
        result.put("seasons", seasons);
        result.put("awardDefinitions", supportedTypes.stream().map(this::awardDefinition).toList());
        return result;
    }

    private Map<String, Object> awardCentreSeason(int season, List<Award> awards,
                                                   List<String> supportedTypes,
                                                   Map<Long, Human> people, Map<Long, Team> teams) {
        Map<String, List<Award>> byType = awards.stream()
                .collect(Collectors.groupingBy(Award::getAwardType));
        List<Map<String, Object>> sections = supportedTypes.stream()
                .filter(byType::containsKey)
                .map(type -> {
                    Map<String, Object> definition = new LinkedHashMap<>(awardDefinition(type));
                    List<Award> winners = new ArrayList<>(byType.get(type));
                    winners.sort(Comparator.comparingInt(award -> selectionSlotOrder(award.getSelectionSlot())));
                    definition.put("winners", winners.stream()
                            .map(award -> awardHistoryRow(award, people.get(award.getWinnerId()),
                                    teams.get(award.getWinnerTeamId())))
                            .toList());
                    return definition;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", season);
        result.put("awards", sections);
        return result;
    }

    private Map<String, Object> awardDefinition(String type) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("awardType", type);
        definition.put("title", formatAwardType(type));
        definition.put("description", switch (type) {
            case "BALLON_DOR" -> "The global 100-voter award for the season's best footballer, with an optional admin winner selection.";
            case "PLAYER_OF_YEAR" -> "The strongest sustained individual performance across the season.";
            case "TEAM_OF_YEAR" -> "The best performer in each position, arranged as a real eleven.";
            case "GOLDEN_BOOT" -> "The leading goalscorer; the global award keeps the L1/L2 weighting.";
            case "MOST_ASSISTS" -> "The player who supplied the most recorded assists.";
            case "BEST_GOALKEEPER" -> "Rating, clean sheets, saves and goals conceded are considered together.";
            case "MOST_ENTERTAINING" -> "Successful dribbles plus chances created, with creation weighted ×1.5.";
            case "MANAGER_OF_YEAR" -> "Results, goal difference and overachievement relative to club reputation.";
            default -> "Season award.";
        });
        return definition;
    }

    private int selectionSlotOrder(String slot) {
        if (slot == null) return 99;
        return List.of("GK", "LB", "CB1", "CB2", "RB", "CM1", "CM2", "LW", "AM", "RW", "ST")
                .indexOf(slot);
    }

    /**
     * Creates the complete honours programme once per season. Every award is
     * keyed by (season, competition, type), while TEAM_OF_YEAR intentionally
     * stores eleven rows sharing that key and differentiated by selectionSlot.
     */
    public List<Award> ensureComprehensiveAwardsForSeason(int season) {
        List<Award> existingAwards = awardRepository.findAllBySeasonNumber(season);
        Set<String> existing = existingAwards.stream()
                .map(award -> awardKey(award.getCompetitionId(), award.getAwardType()))
                .collect(Collectors.toSet());

        List<Scorer> scorers = scorerRepository.findAllBySeasonNumber(season).stream()
                .filter(scorer -> scorer.getTeamScore() >= 0 && scorer.getOpponentTeamId() >= 0)
                .toList();
        List<PlayerSeasonStat> seasonStats = playerSeasonStatRepository.findAllBySeasonNumber(season);
        List<MatchStats> matchStats = matchStatsRepository.findAllBySeasonNumber(season);
        Map<Long, Competition> competitions = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, competition -> competition));
        Set<Long> competitiveIds = competitions.values().stream()
                .filter(competition -> competition.getTypeId() >= 1 && competition.getTypeId() <= 5)
                .map(Competition::getId)
                .collect(Collectors.toSet());
        Map<Long, Team> teams = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        Map<Long, Human> managers = currentManagerByTeam();

        List<Award> created = new ArrayList<>();
        createScopedAwardSet(season, 0L, "Global", true,
                scorers.stream().filter(scorer -> competitiveIds.contains(scorer.getCompetitionId())).toList(),
                seasonStats.stream().filter(stat -> competitiveIds.contains(stat.getCompetitionId())).toList(),
                matchStats.stream().filter(stat -> competitiveIds.contains(stat.getCompetitionId())).toList(),
                teams, managers, existing, created);

        Map<Long, List<Scorer>> scorersByCompetition = scorers.stream()
                .collect(Collectors.groupingBy(Scorer::getCompetitionId));
        Map<Long, List<PlayerSeasonStat>> statsByCompetition = seasonStats.stream()
                .collect(Collectors.groupingBy(PlayerSeasonStat::getCompetitionId));
        Map<Long, List<MatchStats>> matchStatsByCompetition = matchStats.stream()
                .collect(Collectors.groupingBy(MatchStats::getCompetitionId));

        competitions.values().stream()
                .filter(competition -> competition.getTypeId() >= 1 && competition.getTypeId() <= 5)
                .sorted(Comparator.comparing(Competition::getName))
                .forEach(competition -> {
                    List<Scorer> competitionScorers = scorersByCompetition
                            .getOrDefault(competition.getId(), List.of());
                    if (competitionScorers.isEmpty()) return;
                    createScopedAwardSet(season, competition.getId(), competition.getName(), false,
                            competitionScorers,
                            statsByCompetition.getOrDefault(competition.getId(), List.of()),
                            matchStatsByCompetition.getOrDefault(competition.getId(), List.of()),
                            teams, managers, existing, created);
                });

        if (!created.isEmpty()) awardRepository.saveAll(created);
        return created;
    }

    private void createScopedAwardSet(int season, long competitionId, String competitionName,
                                      boolean global, List<Scorer> scorers,
                                      List<PlayerSeasonStat> playerStats,
                                      List<MatchStats> matchStats, Map<Long, Team> teams,
                                      Map<Long, Human> managers, Set<String> existing,
                                      List<Award> created) {
        Map<Long, MajorAwardCandidate> candidates = aggregateCandidates(scorers, global);
        if (candidates.isEmpty()) return;

        addSingleAwardIfMissing(determinePlayerOfYear(candidates, season, competitionId, competitionName),
                competitionId, "PLAYER_OF_YEAR", existing, created);
        Award goldenBoot = global
                ? determineGoldenBoot(scorers, season)
                : determineCompetitionGoldenBoot(candidates, season, competitionId, competitionName);
        if (goldenBoot != null && global) {
            goldenBoot.setCompetitionId(0L);
            goldenBoot.setCompetitionName("Global");
        }
        addSingleAwardIfMissing(goldenBoot, competitionId, "GOLDEN_BOOT", existing, created);
        addSingleAwardIfMissing(determineMostAssists(candidates, season, competitionId, competitionName),
                competitionId, "MOST_ASSISTS", existing, created);
        addSingleAwardIfMissing(determineBestGoalkeeper(candidates, scorers, matchStats,
                        season, competitionId, competitionName),
                competitionId, "BEST_GOALKEEPER", existing, created);
        addSingleAwardIfMissing(determineMostEntertaining(candidates, playerStats,
                        season, competitionId, competitionName),
                competitionId, "MOST_ENTERTAINING", existing, created);
        addSingleAwardIfMissing(determineManagerForScope(matchStats, teams, managers,
                        season, competitionId, competitionName),
                competitionId, "MANAGER_OF_YEAR", existing, created);

        String teamKey = awardKey(competitionId, "TEAM_OF_YEAR");
        if (!existing.contains(teamKey)) {
            List<Award> team = determineTeamOfYear(candidates, season, competitionId, competitionName);
            if (!team.isEmpty()) {
                created.addAll(team);
                existing.add(teamKey);
            }
        }
    }

    private void addSingleAwardIfMissing(Award award, long competitionId, String awardType,
                                         Set<String> existing, List<Award> created) {
        String key = awardKey(competitionId, awardType);
        if (award == null || existing.contains(key)) return;
        created.add(award);
        existing.add(key);
    }

    private String awardKey(long competitionId, String awardType) {
        return competitionId + ":" + awardType;
    }

    private Map<Long, MajorAwardCandidate> aggregateCandidates(List<Scorer> scorers, boolean global) {
        Map<Long, MajorAwardCandidate> candidates = new HashMap<>();
        for (Scorer scorer : scorers) {
            double importance = global ? competitionImportance(scorer.getCompetitionTypeId()) : 1.0;
            if (importance <= 0) continue;
            boolean majorMatch = scorer.getCompetitionTypeId() == 2
                    || scorer.getCompetitionTypeId() == 4 || scorer.getCompetitionTypeId() == 5;
            candidates.computeIfAbsent(scorer.getPlayerId(), MajorAwardCandidate::new)
                    .add(scorer, importance, majorMatch);
        }
        return candidates;
    }

    private Award determinePlayerOfYear(Map<Long, MajorAwardCandidate> candidates, int season,
                                        long competitionId, String competitionName) {
        int maximumAppearances = candidates.values().stream()
                .mapToInt(candidate -> candidate.appearances).max().orElse(0);
        int minimumAppearances = Math.max(3, maximumAppearances / 3);
        MajorAwardCandidate winner = candidates.values().stream()
                .filter(candidate -> candidate.appearances >= minimumAppearances)
                .max(Comparator.comparingDouble(this::playerOfYearScore)
                        .thenComparingDouble(MajorAwardCandidate::averageRating)
                        .thenComparingLong(candidate -> -candidate.playerId))
                .orElse(null);
        if (winner == null) return null;
        Award award = scopedPlayerAward(winner, season, competitionId, competitionName,
                "PLAYER_OF_YEAR");
        award.setValue(String.format(Locale.ROOT, "%.2f rating · %d goals · %d assists",
                winner.averageRating(), winner.goals, winner.assists));
        return award;
    }

    private double playerOfYearScore(MajorAwardCandidate candidate) {
        if (candidate.appearances <= 0) return 0;
        return candidate.averageRating()
                + candidate.weightedGoals / candidate.appearances * 0.12
                + candidate.weightedAssists / candidate.appearances * 0.09;
    }

    private Award determineCompetitionGoldenBoot(Map<Long, MajorAwardCandidate> candidates, int season,
                                                  long competitionId, String competitionName) {
        MajorAwardCandidate winner = candidates.values().stream()
                .filter(candidate -> candidate.goals > 0)
                .max(Comparator.comparingInt((MajorAwardCandidate candidate) -> candidate.goals)
                        .thenComparingDouble(MajorAwardCandidate::averageRating)
                        .thenComparingLong(candidate -> -candidate.playerId))
                .orElse(null);
        if (winner == null) return null;
        Award award = scopedPlayerAward(winner, season, competitionId, competitionName, "GOLDEN_BOOT");
        award.setVotingPoints(winner.goals);
        award.setValue(winner.goals + " goals");
        return award;
    }

    private Award determineMostAssists(Map<Long, MajorAwardCandidate> candidates, int season,
                                       long competitionId, String competitionName) {
        MajorAwardCandidate winner = candidates.values().stream()
                .filter(candidate -> candidate.assists > 0)
                .max(Comparator.comparingInt((MajorAwardCandidate candidate) -> candidate.assists)
                        .thenComparingDouble(MajorAwardCandidate::averageRating)
                        .thenComparingLong(candidate -> -candidate.playerId))
                .orElse(null);
        if (winner == null) return null;
        Award award = scopedPlayerAward(winner, season, competitionId, competitionName, "MOST_ASSISTS");
        award.setValue(winner.assists + " assists");
        return award;
    }

    private Award determineMostEntertaining(Map<Long, MajorAwardCandidate> candidates,
                                            List<PlayerSeasonStat> playerStats, int season,
                                            long competitionId, String competitionName) {
        Map<Long, double[]> totals = new HashMap<>();
        for (PlayerSeasonStat stat : playerStats) {
            double[] values = totals.computeIfAbsent(stat.getPlayerId(), ignored -> new double[2]);
            values[0] += stat.getDribblesCompleted();
            values[1] += stat.getChancesCreated();
        }
        Long winnerId = totals.keySet().stream()
                .filter(candidates::containsKey)
                .filter(id -> !"GK".equalsIgnoreCase(candidates.get(id).primaryPosition()))
                .max(Comparator.comparingDouble((Long id) -> entertainmentScore(totals.get(id)))
                        .thenComparingLong(id -> -id))
                .orElse(null);
        if (winnerId == null) return null;
        double[] values = totals.get(winnerId);
        if (values[0] <= 0 && values[1] <= 0) return null;
        MajorAwardCandidate winner = candidates.get(winnerId);
        Award award = scopedPlayerAward(winner, season, competitionId, competitionName,
                "MOST_ENTERTAINING");
        award.setDribblesCompleted(round(values[0], 1));
        award.setChancesCreated(round(values[1], 1));
        award.setValue(String.format(Locale.ROOT, "%.1f dribbles · %.1f chances created",
                values[0], values[1]));
        return award;
    }

    private double entertainmentScore(double[] totals) {
        return totals[0] + totals[1] * 1.5;
    }

    private Award determineBestGoalkeeper(Map<Long, MajorAwardCandidate> candidates,
                                          List<Scorer> scorers, List<MatchStats> matchStats,
                                          int season, long competitionId, String competitionName) {
        Map<RoundTeamKey, Integer> savesByMatch = new HashMap<>();
        for (MatchStats stats : matchStats) {
            savesByMatch.put(new RoundTeamKey(stats.getRoundNumber(), stats.getTeam1Id()), stats.getHomeSaves());
            savesByMatch.put(new RoundTeamKey(stats.getRoundNumber(), stats.getTeam2Id()), stats.getAwaySaves());
        }
        Map<Long, GoalkeeperRecord> records = new HashMap<>();
        for (Scorer scorer : scorers) {
            if (!"GK".equalsIgnoreCase(scorer.getPosition())) continue;
            GoalkeeperRecord record = records.computeIfAbsent(scorer.getPlayerId(), ignored -> new GoalkeeperRecord());
            record.appearances++;
            record.goalsConceded += Math.max(0, scorer.getOpponentScore());
            if (scorer.getOpponentScore() == 0) record.cleanSheets++;
            record.saves += savesByMatch.getOrDefault(
                    new RoundTeamKey(scorer.getRoundNumber(), scorer.getTeamId()), 0);
        }
        Long winnerId = records.keySet().stream()
                .filter(candidates::containsKey)
                .max(Comparator.comparingDouble((Long id) -> goalkeeperScore(candidates.get(id), records.get(id)))
                        .thenComparingLong(id -> -id))
                .orElse(null);
        if (winnerId == null) return null;
        MajorAwardCandidate winner = candidates.get(winnerId);
        GoalkeeperRecord record = records.get(winnerId);
        Award award = scopedPlayerAward(winner, season, competitionId, competitionName,
                "BEST_GOALKEEPER");
        award.setSaves(record.saves);
        award.setCleanSheets(record.cleanSheets);
        award.setGoalsConceded(record.goalsConceded);
        award.setValue(String.format(Locale.ROOT, "%d clean sheets · %d saves · %d conceded",
                record.cleanSheets, record.saves, record.goalsConceded));
        return award;
    }

    private double goalkeeperScore(MajorAwardCandidate candidate, GoalkeeperRecord record) {
        int appearances = Math.max(1, record.appearances);
        return candidate.averageRating() + record.cleanSheets * 0.14
                + record.saves / (double) appearances * 0.025
                - record.goalsConceded / (double) appearances * 0.035;
    }

    private Award determineManagerForScope(List<MatchStats> matches, Map<Long, Team> teams,
                                           Map<Long, Human> managers, int season,
                                           long competitionId, String competitionName) {
        if (matches.isEmpty()) return null;
        Map<Long, TeamPerformance> performance = new HashMap<>();
        for (MatchStats match : matches) {
            addTeamPerformance(performance, match.getTeam1Id(), match.getHomeGoals(), match.getAwayGoals());
            addTeamPerformance(performance, match.getTeam2Id(), match.getAwayGoals(), match.getHomeGoals());
        }
        Long winnerTeamId = performance.keySet().stream()
                .filter(managers::containsKey)
                .max(Comparator.<Long>comparingDouble(teamId -> managerScore(
                                performance.get(teamId), teams.get(teamId)))
                        .thenComparingLong(teamId -> -teamId))
                .orElse(null);
        if (winnerTeamId == null) return null;
        Human manager = managers.get(winnerTeamId);
        TeamPerformance record = performance.get(winnerTeamId);
        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("MANAGER_OF_YEAR");
        award.setCompetitionId(competitionId);
        award.setCompetitionName(competitionName);
        award.setWinnerId(manager.getId());
        award.setWinnerName(manager.getName());
        award.setWinnerTeamId(winnerTeamId);
        award.setWinnerTeamName(teams.containsKey(winnerTeamId) ? teams.get(winnerTeamId).getName() : "Unknown");
        award.setAppearances(record.played);
        award.setValue(String.format(Locale.ROOT, "%.2f points/game · %+d goal difference",
                record.points / (double) Math.max(1, record.played), record.goalDifference));
        return award;
    }

    private void addTeamPerformance(Map<Long, TeamPerformance> performance, long teamId,
                                    int goalsFor, int goalsAgainst) {
        TeamPerformance record = performance.computeIfAbsent(teamId, ignored -> new TeamPerformance());
        record.played++;
        record.goalDifference += goalsFor - goalsAgainst;
        record.points += goalsFor > goalsAgainst ? 3 : goalsFor == goalsAgainst ? 1 : 0;
    }

    private double managerScore(TeamPerformance performance, Team team) {
        double ppg = performance.points / (double) Math.max(1, performance.played);
        double gdPerGame = performance.goalDifference / (double) Math.max(1, performance.played);
        int reputation = team == null ? 5000 : team.getReputation();
        double underdogBonus = Math.max(0, 10_000 - reputation) / 10_000.0 * 0.38;
        return ppg + gdPerGame * 0.08 + underdogBonus;
    }

    private Map<Long, Human> currentManagerByTeam() {
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                .filter(manager -> manager.getTeamId() != null && !manager.isRetired())
                .toList();
        Map<Long, Long> linkedManagers = userRepository.findAll().stream()
                .filter(user -> user.getTeamId() != null && user.getManagerId() != null)
                .collect(Collectors.toMap(User::getTeamId, User::getManagerId, (left, right) -> left));
        Map<Long, Human> result = new HashMap<>();
        for (Human manager : managers) {
            long teamId = manager.getTeamId();
            Human current = result.get(teamId);
            boolean linked = Objects.equals(linkedManagers.get(teamId), manager.getId());
            boolean currentLinked = current != null && Objects.equals(linkedManagers.get(teamId), current.getId());
            if (current == null || linked && !currentLinked
                    || linked == currentLinked && manager.getManagerReputation() > current.getManagerReputation()) {
                result.put(teamId, manager);
            }
        }
        return result;
    }

    private List<Award> determineTeamOfYear(Map<Long, MajorAwardCandidate> candidates, int season,
                                            long competitionId, String competitionName) {
        List<TeamSlot> slots = List.of(
                new TeamSlot("GK", Set.of("GK")),
                new TeamSlot("LB", Set.of("DL", "WBL")),
                new TeamSlot("CB1", Set.of("DC")), new TeamSlot("CB2", Set.of("DC")),
                new TeamSlot("RB", Set.of("DR", "WBR")),
                new TeamSlot("CM1", Set.of("MC", "DM")), new TeamSlot("CM2", Set.of("MC", "DM")),
                new TeamSlot("LW", Set.of("AML", "ML")),
                new TeamSlot("AM", Set.of("AMC", "MC")),
                new TeamSlot("RW", Set.of("AMR", "MR")),
                new TeamSlot("ST", Set.of("ST")));
        Set<Long> selected = new HashSet<>();
        List<Award> team = new ArrayList<>();
        for (TeamSlot slot : slots) {
            MajorAwardCandidate choice = candidates.values().stream()
                    .filter(candidate -> !selected.contains(candidate.playerId))
                    .filter(candidate -> slot.positions.contains(candidate.primaryPosition()))
                    .max(Comparator.comparingDouble(this::playerOfYearScore)
                            .thenComparingLong(candidate -> -candidate.playerId))
                    .orElse(null);
            if (choice == null && !"GK".equals(slot.slot)) {
                choice = candidates.values().stream()
                        .filter(candidate -> !selected.contains(candidate.playerId))
                        .filter(candidate -> !"GK".equals(candidate.primaryPosition()))
                        .max(Comparator.comparingDouble(this::playerOfYearScore)
                                .thenComparingLong(candidate -> -candidate.playerId))
                        .orElse(null);
            }
            if (choice == null) continue;
            selected.add(choice.playerId);
            Award award = scopedPlayerAward(choice, season, competitionId, competitionName, "TEAM_OF_YEAR");
            award.setSelectionSlot(slot.slot);
            award.setValue(String.format(Locale.ROOT, "%s · %.2f rating", slot.slot, choice.averageRating()));
            team.add(award);
        }
        return team;
    }

    private Award scopedPlayerAward(MajorAwardCandidate winner, int season, long competitionId,
                                    String competitionName, String awardType) {
        Award award = baseMajorAward(winner, season, awardType, competitionName);
        award.setCompetitionId(competitionId);
        award.setCompetitionName(competitionName);
        return award;
    }

    private Award determineGoldenBoot(List<Scorer> scorers, int season) {
        LeagueStrengthService.LeagueStrengthTable leagueStrength = leagueStrengthService.calculate(season);
        Map<Long, MajorAwardCandidate> candidates = new HashMap<>();
        for (Scorer scorer : scorers) {
            if (scorer.getCompetitionTypeId() != 1 && scorer.getCompetitionTypeId() != 3) continue;
            double leagueWeight = leagueStrength.competitionMultiplier(scorer.getCompetitionId());
            candidates.computeIfAbsent(scorer.getPlayerId(), MajorAwardCandidate::new)
                    .add(scorer, leagueWeight, 1.0, false);
        }
        MajorAwardCandidate winner = candidates.values().stream()
                .filter(candidate -> candidate.goals > 0)
                .max(Comparator.comparingDouble(this::goldenBootScore)
                        .thenComparingDouble(candidate -> candidate.weightedGoals)
                        .thenComparingInt(candidate -> candidate.goals)
                        .thenComparingDouble(MajorAwardCandidate::averageRating)
                        .thenComparingLong(candidate -> -candidate.playerId))
                .orElse(null);
        if (winner == null) return null;

        Award award = baseMajorAward(winner, season, "GOLDEN_BOOT", "Domestic Leagues");
        double score = goldenBootScore(winner);
        award.setVotingPoints(round(score, 1));
        award.setValue(String.format(Locale.ROOT, "%d goals · %d assists · %.1f weighted points",
                winner.goals, winner.assists, score));
        return award;
    }

    private double goldenBootScore(MajorAwardCandidate candidate) {
        return candidate.weightedGoals * awardWeightingConfig.getGoldenBoot().getGoalWeight()
                + candidate.weightedAssists * awardWeightingConfig.getGoldenBoot().getAssistWeight();
    }

    /**
     * Simulates a transparent 100-journalist panel. Four voter profiles rotate
     * between all-round play, goals, creativity and big-match performance; every
     * ballot ranks five players (6-4-3-2-1 points). The seed is season-stable, so
     * reloading or backfilling a save can never change the winner.
     */
    private Award determineBallonDor(List<Scorer> scorers, int season) {
        BallonDorBallot ballot = calculateBallonDorBallot(scorers, season);
        List<MajorAwardCandidate> ranking = rankBallonDorCandidates(ballot);
        if (ranking.isEmpty()) return null;

        MajorAwardCandidate winner = ranking.get(0);
        boolean adminSelected = false;
        Optional<AwardOverride> override = awardOverrideRepository
                .findBySeasonNumberAndCompetitionIdAndAwardType(season, 0L, "BALLON_DOR");
        if (override.isPresent()) {
            long selectedId = override.get().getWinnerId();
            Optional<MajorAwardCandidate> selected = ballot.candidates().stream()
                    .filter(candidate -> candidate.playerId == selectedId)
                    .findFirst();
            if (selected.isPresent()) {
                winner = selected.get();
                adminSelected = true;
            }
        }

        int winnerPoints = ballot.points().getOrDefault(winner.playerId, 0);
        int winnerFirstVotes = ballot.firstPlaceVotes().getOrDefault(winner.playerId, 0);
        Award award = baseMajorAward(winner, season, "BALLON_DOR", "World Football");
        award.setVotingPoints(winnerPoints);
        award.setFirstPlaceVotes(winnerFirstVotes);
        award.setAdminSelected(adminSelected);
        award.setValue(String.format(Locale.ROOT,
                "%d voting points · %d first-place votes · %.2f rating · %d goals · %d assists%s",
                winnerPoints, winnerFirstVotes, winner.averageRating(), winner.goals, winner.assists,
                adminSelected ? " · admin selection" : ""));
        return award;
    }

    private BallonDorBallot calculateBallonDorBallot(List<Scorer> scorers, int season) {
        LeagueStrengthService.LeagueStrengthTable leagueStrength = leagueStrengthService.calculate(season);
        Map<Long, MajorAwardCandidate> byPlayer = new HashMap<>();
        for (Scorer scorer : scorers) {
            double competitionWeight = competitionImportance(scorer.getCompetitionTypeId());
            if (competitionWeight <= 0) continue;
            double contributionWeight = competitionWeight
                    * leagueStrength.teamMultiplier(scorer.getTeamId());
            boolean majorMatch = scorer.getCompetitionTypeId() == 2
                    || scorer.getCompetitionTypeId() == 4 || scorer.getCompetitionTypeId() == 5;
            byPlayer.computeIfAbsent(scorer.getPlayerId(), MajorAwardCandidate::new)
                    .add(scorer, contributionWeight, competitionWeight, majorMatch);
        }
        List<MajorAwardCandidate> candidates = byPlayer.values().stream()
                .filter(candidate -> candidate.appearances >= 10)
                .sorted(Comparator.comparingLong(candidate -> candidate.playerId))
                .toList();
        if (candidates.isEmpty()) {
            candidates = byPlayer.values().stream()
                    .filter(candidate -> candidate.appearances > 0)
                    .sorted(Comparator.comparingLong(candidate -> candidate.playerId))
                    .toList();
        }
        if (candidates.isEmpty()) return new BallonDorBallot(List.of(), Map.of(), Map.of());

        double maxGoals = candidates.stream().mapToDouble(candidate -> candidate.weightedGoals).max().orElse(1);
        double maxAssists = candidates.stream().mapToDouble(candidate -> candidate.weightedAssists).max().orElse(1);
        double maxBigMatches = candidates.stream().mapToDouble(candidate -> candidate.bigMatchContributions).max().orElse(1);
        Map<Long, Integer> points = new HashMap<>();
        Map<Long, Integer> firstPlaceVotes = new HashMap<>();
        Random random = new Random(season * 1_000_003L + 91L);
        int[] ballotPoints = {6, 4, 3, 2, 1};

        for (int voter = 0; voter < 100; voter++) {
            int profile = voter % 4;
            Map<Long, Double> ballotScores = new HashMap<>();
            for (MajorAwardCandidate candidate : candidates) {
                double score = voterScore(candidate, profile, maxGoals, maxAssists, maxBigMatches);
                ballotScores.put(candidate.playerId, score + (random.nextDouble() - 0.5) * 0.018);
            }
            List<MajorAwardCandidate> ballot = candidates.stream()
                    .sorted(Comparator.<MajorAwardCandidate>comparingDouble(
                            candidate -> ballotScores.get(candidate.playerId)).reversed()
                            .thenComparingLong(candidate -> candidate.playerId))
                    .limit(5)
                    .toList();
            for (int rank = 0; rank < ballot.size(); rank++) {
                long playerId = ballot.get(rank).playerId;
                points.merge(playerId, ballotPoints[rank], Integer::sum);
                if (rank == 0) firstPlaceVotes.merge(playerId, 1, Integer::sum);
            }
        }

        return new BallonDorBallot(candidates, points, firstPlaceVotes);
    }

    private List<MajorAwardCandidate> rankBallonDorCandidates(BallonDorBallot ballot) {
        Comparator<MajorAwardCandidate> comparator = Comparator
                .comparingInt((MajorAwardCandidate candidate) ->
                        ballot.points().getOrDefault(candidate.playerId, 0))
                .thenComparingInt(candidate ->
                        ballot.firstPlaceVotes().getOrDefault(candidate.playerId, 0))
                .thenComparingDouble(MajorAwardCandidate::averageRating)
                .thenComparingLong(candidate -> -candidate.playerId);
        return ballot.candidates().stream().sorted(comparator.reversed()).toList();
    }

    private Award baseMajorAward(MajorAwardCandidate winner, int season, String awardType,
                                  String competitionName) {
        Human player = humanRepository.findById(winner.playerId).orElse(null);
        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType(awardType);
        award.setCompetitionId(0);
        award.setCompetitionName(competitionName);
        award.setWinnerId(winner.playerId);
        award.setWinnerName(player != null ? player.getName()
                : winner.playerName == null ? "Unknown Player" : winner.playerName);
        award.setWinnerTeamId(winner.teamId);
        award.setWinnerTeamName(winner.teamName == null ? "Unknown" : winner.teamName);
        award.setAverageRating(round(winner.averageRating(), 2));
        award.setGoals(winner.goals);
        award.setAssists(winner.assists);
        award.setAppearances(winner.appearances);
        return award;
    }

    private double voterScore(MajorAwardCandidate candidate, int profile,
                              double maxGoals, double maxAssists, double maxBigMatches) {
        double rating = Math.max(0, Math.min(1, (candidate.averageRating() - 5.5) / 4.5));
        double goals = maxGoals > 0 ? candidate.weightedGoals / maxGoals : 0;
        double assists = maxAssists > 0 ? candidate.weightedAssists / maxAssists : 0;
        double bigMatches = maxBigMatches > 0 ? candidate.bigMatchContributions / maxBigMatches : 0;
        double consistency = Math.min(1, candidate.appearances / 35.0);
        return switch (profile) {
            case 1 -> rating * .22 + goals * .55 + assists * .05 + bigMatches * .13 + consistency * .05;
            case 2 -> rating * .30 + goals * .12 + assists * .43 + bigMatches * .10 + consistency * .05;
            case 3 -> rating * .30 + goals * .18 + assists * .08 + bigMatches * .39 + consistency * .05;
            default -> rating * .44 + goals * .27 + assists * .14 + bigMatches * .10 + consistency * .05;
        };
    }

    private double competitionImportance(int competitionTypeId) {
        return switch (competitionTypeId) {
            case 4 -> 1.30; // League of Champions
            case 5 -> 1.15; // Stars Cup
            case 1 -> 1.00; // First League
            case 2 -> 0.90; // Domestic Cup
            case 3 -> 0.65; // Second League
            default -> 0.0;
        };
    }

    private void backfillCompletedSeasons() {
        int currentSeason = gameStateService.currentSeason();
        for (int season : scorerRepository.findDistinctSeasonNumbersWithMatches()) {
            if (season < currentSeason) ensureMajorAwardsForSeason(season);
        }
    }

    private void backfillComprehensiveCompletedSeasons() {
        int currentSeason = gameStateService.currentSeason();
        for (int season : scorerRepository.findDistinctSeasonNumbersWithMatches()) {
            if (season < currentSeason) {
                ensureMajorAwardsForSeason(season);
                ensureComprehensiveAwardsForSeason(season);
            }
        }
    }

    private String normalizeMajorAwardType(String requestedAwardType) {
        String normalized = requestedAwardType == null ? "GOLDEN_BOOT"
                : requestedAwardType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("BALLON_DOR".equals(normalized) || "GOLDEN_BOOT".equals(normalized)) return normalized;
        throw new IllegalArgumentException("Unknown major award: " + requestedAwardType);
    }

    private Map<String, Object> awardHistoryRow(Award award, Human player, Team team) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("season", award.getSeasonNumber());
        row.put("awardType", award.getAwardType());
        row.put("playerId", award.getWinnerId());
        row.put("playerName", award.getWinnerName());
        row.put("teamId", award.getWinnerTeamId());
        row.put("teamName", award.getWinnerTeamName());
        row.put("teamColor1", team != null ? team.getColor1() : null);
        row.put("teamColor2", team != null ? team.getColor2() : null);
        row.put("value", award.getValue());
        row.put("votingPoints", award.getVotingPoints());
        row.put("firstPlaceVotes", award.getFirstPlaceVotes());
        row.put("averageRating", award.getAverageRating());
        row.put("goals", award.getGoals());
        row.put("assists", award.getAssists());
        row.put("appearances", award.getAppearances());
        row.put("chancesCreated", award.getChancesCreated());
        row.put("dribblesCompleted", award.getDribblesCompleted());
        row.put("saves", award.getSaves());
        row.put("cleanSheets", award.getCleanSheets());
        row.put("goalsConceded", award.getGoalsConceded());
        row.put("adminSelected", award.isAdminSelected());
        row.put("selectionSlot", award.getSelectionSlot());
        row.put("baseFaceId", player != null ? player.getBaseFaceId() : 0);
        row.put("skinTone", player != null ? player.getSkinTone() : 0);
        row.put("hairStyle", player != null ? player.getHairStyle() : 0);
        row.put("hairColor", player != null ? player.getHairColor() : 0);
        row.put("eyeColor", player != null ? player.getEyeColor() : 0);
        row.put("faceShape", player != null ? player.getFaceShape() : 0);
        row.put("noseShape", player != null ? player.getNoseShape() : 0);
        row.put("eyeShape", player != null ? player.getEyeShape() : 0);
        row.put("mouthShape", player != null ? player.getMouthShape() : 0);
        row.put("browShape", player != null ? player.getBrowShape() : 0);
        row.put("species", player != null ? player.getSpecies() : "human");
        return row;
    }

    private double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static final class MajorAwardCandidate {
        private final long playerId;
        private String playerName;
        private long teamId;
        private String teamName;
        private int appearances;
        private int goals;
        private int assists;
        private double weightedGoals;
        private double weightedAssists;
        private double ratingTotal;
        private double ratingWeight;
        private double bigMatchContributions;
        private final Map<String, Integer> positions = new HashMap<>();

        private MajorAwardCandidate(long playerId) {
            this.playerId = playerId;
        }

        private void add(Scorer scorer, double importance, boolean majorMatch) {
            add(scorer, importance, importance, majorMatch);
        }

        private void add(Scorer scorer, double contributionImportance,
                         double ratingImportance, boolean majorMatch) {
            appearances++;
            goals += scorer.getGoals();
            assists += scorer.getAssists();
            weightedGoals += scorer.getGoals() * contributionImportance;
            weightedAssists += scorer.getAssists() * contributionImportance;
            ratingTotal += scorer.getRating() * ratingImportance;
            ratingWeight += ratingImportance;
            if (majorMatch) {
                bigMatchContributions += (scorer.getGoals() * 1.4 + scorer.getAssists())
                        * contributionImportance;
            }
            if (scorer.getPosition() != null && !scorer.getPosition().isBlank()) {
                positions.merge(scorer.getPosition().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
            teamId = scorer.getTeamId();
            teamName = scorer.getTeamName();
        }

        private double averageRating() {
            return ratingWeight > 0 ? ratingTotal / ratingWeight : 0;
        }

        private String primaryPosition() {
            return positions.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse("");
        }
    }

    private record RoundTeamKey(int round, long teamId) {}
    private record TeamSlot(String slot, Set<String> positions) {}
    private record BallonDorBallot(List<MajorAwardCandidate> candidates,
                                   Map<Long, Integer> points,
                                   Map<Long, Integer> firstPlaceVotes) {}
    private static final class GoalkeeperRecord {
        private int appearances;
        private int saves;
        private int cleanSheets;
        private int goalsConceded;
    }
    private static final class TeamPerformance {
        private int played;
        private int points;
        private int goalDifference;
    }

    private Award determineBestPlayer(List<Scorer> allScorers, int season) {

        if (allScorers.isEmpty()) {
            return null;
        }

        // Use actual match performance, not the player's static ability rating.
        Map<Long, int[]> playerStats = new HashMap<>(); // playerId -> [goals, assists, appearances]
        Map<Long, Double> playerRatingSums = new HashMap<>();
        Map<Long, String> playerTeamNames = new HashMap<>();
        Map<Long, Long> playerTeamIds = new HashMap<>();

        for (Scorer scorer : allScorers) {
            playerStats.computeIfAbsent(scorer.getPlayerId(), k -> new int[3]);
            playerStats.get(scorer.getPlayerId())[0] += scorer.getGoals();
            playerStats.get(scorer.getPlayerId())[1] += scorer.getAssists();
            playerStats.get(scorer.getPlayerId())[2]++;
            playerRatingSums.merge(scorer.getPlayerId(), scorer.getRating(), Double::sum);
            playerTeamNames.put(scorer.getPlayerId(), scorer.getTeamName());
            playerTeamIds.put(scorer.getPlayerId(), scorer.getTeamId());
        }

        // Minimum ten appearances avoids a one-match 10.0 winning the season award.
        long bestPlayerId = -1;
        double bestScore = -1;

        for (Map.Entry<Long, int[]> entry : playerStats.entrySet()) {
            int[] stats = entry.getValue();
            if (stats[2] < 10) continue;
            double average = playerRatingSums.getOrDefault(entry.getKey(), 0.0) / stats[2];
            double score = average + Math.min(30, stats[0] + stats[1]) * 0.01;
            if (score > bestScore) {
                bestScore = score;
                bestPlayerId = entry.getKey();
            }
        }

        if (bestPlayerId == -1) {
            return null;
        }

        String playerName = humanRepository.findById(bestPlayerId)
                .map(Human::getName)
                .orElse("Unknown Player");

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("BEST_PLAYER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestPlayerId);
        award.setWinnerName(playerName);
        award.setWinnerTeamId(playerTeamIds.getOrDefault(bestPlayerId, 0L));
        award.setWinnerTeamName(playerTeamNames.getOrDefault(bestPlayerId, "Unknown"));
        int[] stats = playerStats.get(bestPlayerId);
        double average = playerRatingSums.get(bestPlayerId) / stats[2];
        award.setValue(String.format(Locale.ROOT, "%.2f rating, %d goals, %d assists",
                average, stats[0], stats[1]));

        return award;
    }

    private Award determineTopScorer(List<Scorer> allScorers, int season) {

        if (allScorers.isEmpty()) {
            return null;
        }

        // Aggregate goals per player
        Map<Long, Integer> playerGoals = new HashMap<>();
        Map<Long, String> playerTeamNames = new HashMap<>();
        Map<Long, Long> playerTeamIds = new HashMap<>();

        for (Scorer scorer : allScorers) {
            playerGoals.merge(scorer.getPlayerId(), scorer.getGoals(), Integer::sum);
            playerTeamNames.put(scorer.getPlayerId(), scorer.getTeamName());
            playerTeamIds.put(scorer.getPlayerId(), scorer.getTeamId());
        }

        // Find player with most goals
        long topScorerId = -1;
        int topGoals = -1;

        for (Map.Entry<Long, Integer> entry : playerGoals.entrySet()) {
            if (entry.getValue() > topGoals) {
                topGoals = entry.getValue();
                topScorerId = entry.getKey();
            }
        }

        if (topScorerId == -1) {
            return null;
        }

        String playerName = humanRepository.findById(topScorerId)
                .map(Human::getName)
                .orElse("Unknown Player");

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("TOP_SCORER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(topScorerId);
        award.setWinnerName(playerName);
        award.setWinnerTeamId(playerTeamIds.getOrDefault(topScorerId, 0L));
        award.setWinnerTeamName(playerTeamNames.getOrDefault(topScorerId, "Unknown"));
        award.setValue(topGoals + " goals");

        return award;
    }

    private Award determineBestYoungPlayer(List<Scorer> allScorers, int season) {
        Map<Long, Human> youngPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> player.getAge() < 21 && !player.isRetired())
                .collect(Collectors.toMap(Human::getId, player -> player));
        Map<Long, Double> ratingSums = new HashMap<>();
        Map<Long, Integer> appearances = new HashMap<>();
        Map<Long, Long> teamIds = new HashMap<>();
        for (Scorer scorer : allScorers) {
            if (!youngPlayers.containsKey(scorer.getPlayerId())) continue;
            ratingSums.merge(scorer.getPlayerId(), scorer.getRating(), Double::sum);
            appearances.merge(scorer.getPlayerId(), 1, Integer::sum);
            teamIds.put(scorer.getPlayerId(), scorer.getTeamId());
        }
        Long winnerId = ratingSums.keySet().stream()
                .filter(playerId -> appearances.getOrDefault(playerId, 0) >= 5)
                .max(Comparator.comparingDouble(playerId ->
                        ratingSums.get(playerId) / appearances.get(playerId)))
                .orElse(null);
        if (winnerId == null) return null;

        Human bestYoung = youngPlayers.get(winnerId);
        long teamId = teamIds.getOrDefault(winnerId,
                bestYoung.getTeamId() == null ? 0L : bestYoung.getTeamId());
        String teamName = teamRepository.findById(teamId)
                .map(Team::getName)
                .orElse("Free Agent");
        double average = ratingSums.get(winnerId) / appearances.get(winnerId);

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("BEST_YOUNG_PLAYER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestYoung.getId());
        award.setWinnerName(bestYoung.getName());
        award.setWinnerTeamId(teamId);
        award.setWinnerTeamName(teamName);
        award.setValue(String.format(Locale.ROOT, "%.2f average rating (%d appearances)",
                average, appearances.get(winnerId)));

        return award;
    }

    private Award determineManagerOfYear(int season) {

        // Get all managers
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)
                .stream()
                .filter(m -> m.getTeamId() != null && !m.isRetired())
                .collect(Collectors.toList());

        if (managers.isEmpty()) {
            return null;
        }

        Map<Long, Team> teams = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        Set<Long> leagueIds = competitionRepository.findAll().stream()
                .filter(competition -> competition.getTypeId() == 1 || competition.getTypeId() == 3)
                .map(Competition::getId)
                .collect(Collectors.toSet());
        Map<Long, List<TeamCompetitionDetail>> tables = teamCompetitionDetailRepository.findAll().stream()
                .filter(detail -> leagueIds.contains((long) detail.getCompetitionId()))
                .collect(Collectors.groupingBy(detail -> (long) detail.getCompetitionId()));

        // Keep only the manager actually linked to a user where available. Old
        // saves may contain more than one active manager row for the same team.
        Map<Long, Long> userManagerByTeam = userRepository.findAll().stream()
                .filter(user -> user.getTeamId() != null && user.getManagerId() != null)
                .collect(Collectors.toMap(User::getTeamId, User::getManagerId, (left, right) -> left));
        Map<Long, Human> managerByTeam = new HashMap<>();
        for (Human manager : managers) {
            long teamId = manager.getTeamId();
            Human current = managerByTeam.get(teamId);
            boolean isLinked = Objects.equals(userManagerByTeam.get(teamId), manager.getId());
            boolean currentLinked = current != null
                    && Objects.equals(userManagerByTeam.get(teamId), current.getId());
            if (current == null || isLinked && !currentLinked
                    || isLinked == currentLinked
                    && manager.getManagerReputation() > current.getManagerReputation()) {
                managerByTeam.put(teamId, manager);
            }
        }

        Human bestManager = null;
        int bestOverperformance = Integer.MIN_VALUE;
        int bestActualPosition = Integer.MAX_VALUE;

        for (List<TeamCompetitionDetail> table : tables.values()) {
            List<TeamCompetitionDetail> actualTable = table.stream()
                    .sorted(this::compareTableRows)
                    .toList();
            List<Long> expectedOrder = table.stream()
                    .map(TeamCompetitionDetail::getTeamId)
                    .sorted(Comparator.comparingInt((Long teamId) ->
                            teams.getOrDefault(teamId, new Team()).getReputation()).reversed())
                    .toList();
            for (int index = 0; index < actualTable.size(); index++) {
                long teamId = actualTable.get(index).getTeamId();
                Human manager = managerByTeam.get(teamId);
                if (manager == null) continue;
                int actualPosition = index + 1;
                int expectedPosition = expectedOrder.indexOf(teamId) + 1;
                int overperformance = expectedPosition - actualPosition;
                if (overperformance > bestOverperformance
                        || overperformance == bestOverperformance && actualPosition < bestActualPosition) {
                    bestOverperformance = overperformance;
                    bestActualPosition = actualPosition;
                    bestManager = manager;
                }
            }
        }

        if (bestManager == null && !managers.isEmpty()) {
            // Fallback: pick manager with highest reputation
            bestManager = managers.stream()
                    .max(Comparator.comparingInt(Human::getManagerReputation))
                    .orElse(null);
        }

        if (bestManager == null) {
            return null;
        }

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("MANAGER_OF_YEAR");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestManager.getId());
        award.setWinnerName(bestManager.getName());
        award.setWinnerTeamId(bestManager.getTeamId() != null ? bestManager.getTeamId() : 0L);
        Team winningTeam = teams.get(bestManager.getTeamId());
        award.setWinnerTeamName(winningTeam != null ? winningTeam.getName() : "Unknown");
        award.setValue(bestOverperformance == Integer.MIN_VALUE
                ? "Highest manager reputation"
                : (bestOverperformance >= 0 ? "+" : "") + bestOverperformance
                    + " positions versus expectation");

        return award;
    }

    private int compareTableRows(TeamCompetitionDetail left, TeamCompetitionDetail right) {
        if (left.getPoints() != right.getPoints())
            return Integer.compare(right.getPoints(), left.getPoints());
        if (left.getGoalDifference() != right.getGoalDifference())
            return Integer.compare(right.getGoalDifference(), left.getGoalDifference());
        return Integer.compare(right.getGoalsFor(), left.getGoalsFor());
    }

    private void notifyManagersOfAwards(List<Award> awards, int season) {

        // Get all human managers (those with teams)
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)
                .stream()
                .filter(m -> m.getTeamId() != null)
                .collect(Collectors.toList());

        for (Human manager : managers) {
            long managedTeamId = manager.getTeamId();

            // Check if any awards were won by players on this manager's team
            List<Award> teamAwards = awards.stream()
                    .filter(a -> a.getWinnerTeamId() == managedTeamId)
                    .collect(Collectors.toList());

            if (!teamAwards.isEmpty()) {
                StringBuilder content = new StringBuilder("Congratulations! The following awards were won:\n\n");
                for (Award award : teamAwards) {
                    content.append("- ").append(formatAwardType(award.getAwardType()))
                            .append(": ").append(award.getWinnerName())
                            .append(" (").append(award.getValue()).append(")\n");
                }

                sendInboxMessage(managedTeamId, season, 0,
                        "Awards Ceremony Results",
                        content.toString(),
                        "AWARDS");
            }
        }
    }

    private String formatAwardType(String awardType) {

        switch (awardType) {
            case "BEST_PLAYER": return "Best Player";
            case "TOP_SCORER": return "Top Scorer";
            case "BEST_YOUNG_PLAYER": return "Best Young Player";
            case "MANAGER_OF_YEAR": return "Manager of the Year";
            case "GOLDEN_BOOT": return "Golden Boot";
            case "BALLON_DOR": return "Ballon d'Or";
            case "PLAYER_OF_YEAR": return "Player of the Year";
            case "TEAM_OF_YEAR": return "Team of the Year";
            case "MOST_ASSISTS": return "Most Assists";
            case "BEST_GOALKEEPER": return "Best Goalkeeper";
            case "MOST_ENTERTAINING": return "Most Entertaining Player";
            default: return awardType;
        }
    }

    private void sendInboxMessage(long teamId, int season, int day, String title, String content, String category) {

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }
}
