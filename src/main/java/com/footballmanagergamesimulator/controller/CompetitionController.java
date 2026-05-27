package com.footballmanagergamesimulator.controller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import org.springframework.transaction.annotation.Transactional;
import com.footballmanagergamesimulator.service.FixtureSchedulingService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.LeagueConfigService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.PlayerInstructionService;
import com.footballmanagergamesimulator.service.SquadGenerationService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TeamPostMatchService;
import com.footballmanagergamesimulator.service.TeamTalkService;
import com.footballmanagergamesimulator.service.TransferValueCalculator;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.*;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CompetitionController {

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private PredeterminedScoreRepository predeterminedScoreRepository;
    @Autowired
    private com.footballmanagergamesimulator.service.CupBracketService cupBracketService;
    @Autowired
    private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    RoundRobin roundRobin;
    @Autowired
    LeagueConfigService leagueConfigService;
    @Autowired
    CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    HumanService _humanService;
    @Autowired
    TeamFacilitiesRepository _teamFacilitiesRepository;
    @Autowired
    CompositeTransferStrategy _compositeTransferStrategy;
    @Autowired
    TransferRepository transferRepository;
    @Autowired
    TacticService tacticService;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    TacticController tacticController;
    @Autowired
    PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    CompetitionService competitionService;
    @Autowired
    SquadGenerationService squadGenerationService;
    @Autowired
    EuropeanCompetitionService europeanCompetitionService;
    @Autowired
    TeamPostMatchService teamPostMatchService;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired
    ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    TrainingScheduleRepository trainingScheduleRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    TransferOfferRepository transferOfferRepository;
    @Autowired
    SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired
    LoanRepository loanRepository;
    @Autowired
    MatchEventRepository matchEventRepository;
    @Autowired
    ManagerHistoryRepository managerHistoryRepository;
    @Autowired
    FixtureSchedulingService fixtureSchedulingService;
    @Autowired
    GameCalendarRepository gameCalendarRepository;
    @Autowired
    @org.springframework.context.annotation.Lazy
    ScoutManagementController scoutManagementController;
    @Autowired
    UserContext userContext;
    @Autowired
    UserRepository userRepository;
    @Autowired
    com.footballmanagergamesimulator.service.JobOfferService jobOfferService;
    @Autowired
    LiveMatchSimulationService liveMatchSimulationService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    FinanceService financeService;
    @Autowired
    StadiumRepository stadiumRepository;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.StaffService staffService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.SeasonTransitionService seasonTransitionService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationService matchSimulationService;
    @Autowired
    com.footballmanagergamesimulator.service.PlayerRoleService playerRoleService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper(); // <--- Ai nevoie de asta


    Round round;
    private boolean transferWindowOpen = false;
    // managerFired is now per-user on User.fired, not a global boolean
    private boolean seasonTransitionInProgress = false;
    private boolean teamTalkUsedThisRound = false;

    // Cached competition type ID sets to avoid repeated DB queries
    private Set<Long> cachedLeagueCompIds = null;
    private Set<Long> cachedCupCompIds = null;
    private Set<Long> cachedSecondLeagueCompIds = null;

    /** Lazily populated competition-type ID caches — exposed so
     *  {@link com.footballmanagergamesimulator.service.MatchSimulationOrchestrator}
     *  can share the same warm cache instead of re-querying. */
    public Set<Long> getLeagueCompetitionIdsCached() {
        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = getCompetitionIdsByCompetitionType(1);
        }
        return cachedLeagueCompIds;
    }

    public Set<Long> getCupCompetitionIdsCached() {
        if (cachedCupCompIds == null) {
            cachedCupCompIds = getCompetitionIdsByCompetitionType(2);
        }
        return cachedCupCompIds;
    }

    public Set<Long> getSecondLeagueCompetitionIdsCached() {
        if (cachedSecondLeagueCompIds == null) {
            cachedSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
        }
        return cachedSecondLeagueCompIds;
    }

    @PostConstruct
    public void initializeRound() {

        // ===== Resume support =====
        // If a Round already exists (file-based DB carrying over a prior run),
        // just load it and skip the entire one-time setup below. The previous
        // implementation always re-ran the full season-1 setup on every boot —
        // fine with in-memory H2 (table is empty on each restart), but with
        // persistent storage it created duplicate TeamFacilities/players/etc.
        Optional<Round> existing = roundRepository.findById(1L);
        if (existing.isPresent()) {
            round = existing.get();
            System.out.println("=== Resuming from season " + round.getSeason()
                    + ", round " + round.getRound() + " ===");
            return;
        }

        // ===== First-time setup =====
        round = new Round();
        round.setId(1L);
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);

        // Initialize the game (create teams, competitions, players, etc.)
        competitionTeamInfoRepository.deleteAll();
        initialization();

        // Generate fixtures for all leagues (was previously in play() round==1 block)
        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3);
        leagueCompetitionIds.addAll(secondLeagueCompetitionIds);
        for (Long competitionId : leagueCompetitionIds)
            this.getFixturesForRound(String.valueOf(competitionId), "1");

        // Generate calendar AFTER league fixtures exist so updateMatchDays can set the day field
        fixtureSchedulingService.generateSeasonCalendar(1);

        generateSeasonObjectives((int) round.getSeason());

        // Create players and managers for all teams (season 1 only)
        List<Team> teams = teamRepository.findAll();
        Random random = new Random();
        for (Team team : teams) {
            TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(team.getId());
            squadGenerationService.generateInitialSquad(team, teamFacilities, 1, 70, random);

            // create manager for each team
            Human manager = new Human();
            manager.setAge(random.nextInt(35, 70));
            manager.setName(compositeNameGenerator.generateName(team.getCompetitionId()));
            manager.setTeamId(team.getId());
            int reputation = 100;
            if (teamFacilities != null)
                reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
            manager.setRating(random.nextInt(reputation - 20, reputation + 20));
            manager.setPosition("Manager");
            manager.setSeasonCreated(1L);
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            // Assign a real tactical kit (multiple known tactics + a preferred one)
            // scaled by manager rating instead of the old hardcoded 5-option pick.
            String[] kit = tacticService.buildManagerTacticKit((int) manager.getRating(), random);
            manager.setTacticStyle(kit[0]);
            manager.setKnownTactics(kit[1]);
            humanRepository.save(manager);

            // Generate coaching staff for each team
            staffService.generateInitialStaff(team.getId(), 1);
        }

        // Generate free agent coaches on the market
        staffService.generateFreeAgentCoaches(30, 1);

        // Initialize scorers for all players
        List<Team> allTeams = teamRepository.findAll();
        for (Team team : allTeams) {
            List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
            List<CompetitionTeamInfo> competitions = competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(team.getId(), round.getSeason());

            for (Human human : allPlayers) {
                for (CompetitionTeamInfo competitionTeamInfo : competitions) {
                    Scorer scorer = new Scorer();
                    scorer.setPlayerId(human.getId());
                    scorer.setSeasonNumber((int) round.getSeason());
                    scorer.setTeamId(team.getId());
                    scorer.setOpponentTeamId(-1);
                    scorer.setPosition(human.getPosition());
                    scorer.setTeamScore(-1);
                    scorer.setOpponentScore(-1);
                    scorer.setCompetitionId(competitionTeamInfo.getCompetitionId());
                    scorer.setCompetitionTypeId(competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()) != null ? competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()).intValue() : 0);
                    scorer.setTeamName(team.getName());
                    scorer.setOpponentTeamName(null);
                    scorer.setCompetitionName(competitionRepository.findNameById(competitionTeamInfo.getCompetitionId()));
                    scorer.setRating(human.getRating());
                    scorer.setGoals(0);
                    scorerRepository.save(scorer);
                }

                if (scorerLeaderboardRepository.findByPlayerId(human.getId()).isEmpty()) {
                    ScorerLeaderboardEntry scorerLeaderboardEntry = new ScorerLeaderboardEntry();
                    scorerLeaderboardEntry.setPlayerId(human.getId());
                    scorerLeaderboardEntry.setAge(human.getAge());
                    scorerLeaderboardEntry.setName(human.getName());
                    scorerLeaderboardEntry.setGoals(0);
                    scorerLeaderboardEntry.setMatches(0);
                    scorerLeaderboardEntry.setCurrentRating(human.getRating());
                    scorerLeaderboardEntry.setBestEverRating(human.getRating());
                    scorerLeaderboardEntry.setSeasonOfBestEverRating((int) round.getSeason());
                    scorerLeaderboardEntry.setPosition(human.getPosition());
                    scorerLeaderboardEntry.setTeamId(human.getTeamId());
                    scorerLeaderboardEntry.setTeamName(team.getName());
                    scorerLeaderboardRepository.save(scorerLeaderboardEntry);
                }

                Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(human.getId());
                ScorerLeaderboardEntry scorerLeaderboardEntry = resetCurrentSeasonStats(optionalScorerLeaderboardEntry);
                scorerLeaderboardRepository.save(scorerLeaderboardEntry);
            }
        }

        System.out.println("=== Initialization complete: teams, players, fixtures, and scorers created ===");

        // Build proper full cup brackets for season 1 (wipes the legacy per-league
        // cup CompetitionTeamInfo records and replaces with bracket-aware fixtures).
        regenerateAllCupBrackets((int) round.getSeason());
    }

    /**
     * (Re)generates the bracket for every national cup for the given season.
     * Safe to call multiple times — the service wipes the cup's existing
     * (cup, season) data before rebuilding.
     */
    private void regenerateAllCupBrackets(int season) {
        Set<Long> cupIds = getCompetitionIdsByCompetitionType(2);
        for (Long cupId : cupIds) {
            cupBracketService.generateBracket(cupId, season);
            // generateSeasonCalendar() runs before the bracket exists, so the match rows
            // we just created still have day=0. Sync them with the already-existing
            // MATCH_CUP CalendarEvent rows so they appear on the right day in schedules.
            fixtureSchedulingService.syncCalendarDaysOntoExistingMatches(cupId, season);
        }
    }

    /**
     * Leagues overview — for the leagues-overview frontend page.
     * Returns all first-division leagues sorted by their nation's UEFA-style
     * coefficient rank (rank 1 = strongest), each with:
     *  - top N standings (positional + W/D/L/GF/GA/GD/Pts)
     *  - the qualification zones for that rank (which 1-based positions go to
     *    LoC group / LoC qualifying / LoC preliminary / Stars Cup / relegation)
     * so the frontend can color each row based on what that position earns.
     */
    @GetMapping("/leaguesOverview")
    public Map<String, Object> getLeaguesOverview(@RequestParam(defaultValue = "5") int topN) {
        int currentSeason = Integer.parseInt(getCurrentSeason());
        List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();

        List<Map<String, Object>> leagues = new ArrayList<>();
        for (int i = 0; i < sortedLeagueIds.size(); i++) {
            long leagueId = sortedLeagueIds.get(i);
            int rank = i + 1;
            Competition comp = competitionRepository.findById(leagueId).orElse(null);
            if (comp == null) continue;

            Map<String, Object> league = new LinkedHashMap<>();
            league.put("competitionId", leagueId);
            league.put("name", comp.getName());
            league.put("nationId", comp.getNationId());
            league.put("rank", rank);
            league.put("qualificationZones", computeLeagueQualificationZones(rank));

            List<TeamCompetitionDetail> standings = teamCompetitionDetailRepository.findAll().stream()
                    .filter(d -> d.getCompetitionId() == leagueId)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
                        if (a.getGoalDifference() != b.getGoalDifference())
                            return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
                        return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
                    })
                    .toList();

            List<Map<String, Object>> topTeams = new ArrayList<>();
            int limit = Math.min(topN, standings.size());
            for (int p = 0; p < limit; p++) {
                TeamCompetitionDetail s = standings.get(p);
                Team t = teamRepository.findById(s.getTeamId()).orElse(null);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("position", p + 1);
                row.put("teamId", s.getTeamId());
                row.put("teamName", t != null ? t.getName() : "?");
                row.put("played", s.getGames());
                row.put("wins", s.getWins());
                row.put("draws", s.getDraws());
                row.put("losses", s.getLoses());
                row.put("goalsFor", s.getGoalsFor());
                row.put("goalsAgainst", s.getGoalsAgainst());
                row.put("goalDifference", s.getGoalDifference());
                row.put("points", s.getPoints());
                row.put("form", s.getForm() != null ? s.getForm() : "");
                topTeams.add(row);
            }
            league.put("topTeams", topTeams);
            league.put("totalTeams", standings.size());

            leagues.add(league);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", currentSeason);
        result.put("topN", topN);
        result.put("leagues", leagues);
        return result;
    }

    /**
     * Same as the European qualification logic in qualifyTeamsForEuropeanCompetitions().
     * Returned positions are 1-based so the frontend can compare to a row's display
     * position directly.
     */
    private Map<String, Object> computeLeagueQualificationZones(int rank) {
        Map<String, Object> zones = new LinkedHashMap<>();
        List<Integer> locGroup = new ArrayList<>();
        List<Integer> locQualifying = new ArrayList<>();
        List<Integer> locPreliminary = new ArrayList<>();
        List<Integer> starsCup = new ArrayList<>();

        if (rank >= 1 && rank <= 7) {
            int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
            int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
            int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
            int[][] starsCupPositions = {{4}, {4}, {3}, {3}, {}, {}, {}};

            int idx = rank - 1;
            int pos = 0;
            for (int i = 0; i < directSpots[idx]; i++) locGroup.add(++pos);
            for (int i = 0; i < qualifyingSpots[idx]; i++) locQualifying.add(++pos);
            for (int i = 0; i < preliminarySpots[idx]; i++) locPreliminary.add(++pos);
            for (int p : starsCupPositions[idx]) starsCup.add(p + 1);
        }

        zones.put("locGroup", locGroup);
        zones.put("locQualifying", locQualifying);
        zones.put("locPreliminary", locPreliminary);
        zones.put("starsCup", starsCup);
        return zones;
    }

    /**
     * Cups overview — sister endpoint to /leaguesOverview, one row per national cup
     * with its current state: which round just played, how many teams remain, and
     * either the last completed round's results or the upcoming round's pairings.
     */
    @GetMapping("/cupsOverview")
    public Map<String, Object> getCupsOverview() {
        int currentSeason = Integer.parseInt(getCurrentSeason());
        String seasonStr = String.valueOf(currentSeason);

        // Order cups by their nation's league rank (rank 1 league's cup comes first)
        List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();
        List<Competition> orderedCups = new ArrayList<>();
        for (Long leagueId : sortedLeagueIds) {
            Competition league = competitionRepository.findById(leagueId).orElse(null);
            if (league == null) continue;
            competitionRepository.findAll().stream()
                    .filter(c -> c.getTypeId() == 2L && c.getNationId() == league.getNationId())
                    .findFirst()
                    .ifPresent(orderedCups::add);
        }

        List<Map<String, Object>> cups = new ArrayList<>();
        int rank = 0;
        for (Competition cup : orderedCups) {
            rank++;
            Map<String, Object> cupInfo = new LinkedHashMap<>();
            cupInfo.put("competitionId", cup.getId());
            cupInfo.put("name", cup.getName());
            cupInfo.put("nationId", cup.getNationId());
            cupInfo.put("rank", rank);

            List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository.findAll().stream()
                    .filter(m -> m.getCompetitionId() == cup.getId() && seasonStr.equals(m.getSeasonNumber()))
                    .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound)
                            .thenComparingInt(CompetitionTeamInfoMatch::getMatchIndex))
                    .toList();

            int totalRounds = matches.stream().mapToInt(m -> (int) m.getRound()).max().orElse(0);
            cupInfo.put("totalRounds", totalRounds);

            // Find the most recently played round (any with a saved CompetitionTeamInfoDetail)
            List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository.findAll().stream()
                    .filter(d -> d.getCompetitionId() == cup.getId() && d.getSeasonNumber() == currentSeason)
                    .toList();
            int lastPlayedRound = details.stream().mapToInt(d -> (int) d.getRoundId()).max().orElse(0);
            cupInfo.put("lastPlayedRound", lastPlayedRound);
            cupInfo.put("currentRoundName", roundDisplayName(lastPlayedRound > 0 ? lastPlayedRound : 1, totalRounds, matches));

            // Show next-to-play (or last played) round's pairings as a small bracket card
            int focusRound = lastPlayedRound > 0 && lastPlayedRound < totalRounds ? lastPlayedRound + 1 : Math.max(1, lastPlayedRound);
            List<Map<String, Object>> roundMatches = new ArrayList<>();
            Set<Long> teamIdsInRound = new HashSet<>();
            for (CompetitionTeamInfoMatch m : matches) {
                if (m.getRound() != focusRound) continue;
                if (m.getTeam1Id() > 0) teamIdsInRound.add(m.getTeam1Id());
                if (m.getTeam2Id() > 0) teamIdsInRound.add(m.getTeam2Id());
            }
            Map<Long, String> nameLookup = teamRepository.findAllById(teamIdsInRound).stream()
                    .collect(Collectors.toMap(Team::getId, Team::getName));
            Map<String, String> scoreByKey = new HashMap<>();
            for (CompetitionTeamInfoDetail d : details) {
                if (d.getRoundId() != focusRound) continue;
                scoreByKey.put(d.getTeam1Id() + "-" + d.getTeam2Id(), d.getScore());
            }
            for (CompetitionTeamInfoMatch m : matches) {
                if (m.getRound() != focusRound) continue;
                Map<String, Object> mr = new LinkedHashMap<>();
                mr.put("matchIndex", m.getMatchIndex());
                mr.put("team1Name", m.getTeam1Id() > 0 ? nameLookup.getOrDefault(m.getTeam1Id(), "?") : null);
                mr.put("team2Name", m.getTeam2Id() > 0 ? nameLookup.getOrDefault(m.getTeam2Id(), "?") : null);
                mr.put("score", scoreByKey.get(m.getTeam1Id() + "-" + m.getTeam2Id()));
                roundMatches.add(mr);
            }
            cupInfo.put("focusRound", focusRound);
            cupInfo.put("focusRoundMatches", roundMatches);

            cups.add(cupInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", currentSeason);
        result.put("cups", cups);
        return result;
    }

    private String roundDisplayName(int round, int totalRounds, List<CompetitionTeamInfoMatch> allMatches) {
        if (totalRounds <= 0) return "Round " + round;
        int fromEnd = totalRounds - round + 1;
        // Prelim detection: round 1 with fewer matches than round 2
        if (round == 1 && totalRounds >= 2) {
            long r1Count = allMatches.stream().filter(m -> m.getRound() == 1).count();
            long r2Count = allMatches.stream().filter(m -> m.getRound() == 2).count();
            if (r1Count < r2Count) return "Preliminary";
        }
        if (fromEnd == 1) return "Final";
        if (fromEnd == 2) return "Semi-Final";
        if (fromEnd == 3) return "Quarter-Final";
        if (fromEnd == 4) return "Round of 16";
        if (fromEnd == 5) return "Round of 32";
        return "Round " + round;
    }

    /**
     * Returns the full pre-generated cup bracket for the given (cup, season),
     * grouped by round. team1Id/team2Id == 0 means "winner of the matching
     * earlier-round slot" (placeholder, not yet decided). Frontend renders this
     * as a tree so users can see the whole path from day 1.
     */
    @GetMapping("/cupBracket/{cupId}/{season}")
    public Map<String, Object> getCupBracket(@PathVariable long cupId,
                                              @PathVariable int season) {
        String seasonStr = String.valueOf(season);

        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == cupId && seasonStr.equals(m.getSeasonNumber()))
                .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound)
                        .thenComparingInt(CompetitionTeamInfoMatch::getMatchIndex))
                .toList();

        // Look up team names + played-match results once, in batches
        Set<Long> teamIds = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matches) {
            if (m.getTeam1Id() > 0) teamIds.add(m.getTeam1Id());
            if (m.getTeam2Id() > 0) teamIds.add(m.getTeam2Id());
        }
        Map<Long, String> teamNames = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == cupId && d.getSeasonNumber() == season)
                .toList();
        Map<String, String> scoreByKey = new HashMap<>();
        for (CompetitionTeamInfoDetail d : details) {
            scoreByKey.put(d.getCompetitionId() + "-" + d.getRoundId() + "-"
                    + d.getTeam1Id() + "-" + d.getTeam2Id(), d.getScore());
        }

        // Group matches by round
        Map<Long, List<Map<String, Object>>> matchesByRound = new LinkedHashMap<>();
        for (CompetitionTeamInfoMatch m : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matchIndex", m.getMatchIndex());
            entry.put("day", m.getDay());
            entry.put("team1Id", m.getTeam1Id());
            entry.put("team2Id", m.getTeam2Id());
            entry.put("team1Name", m.getTeam1Id() > 0 ? teamNames.getOrDefault(m.getTeam1Id(), "?") : null);
            entry.put("team2Name", m.getTeam2Id() > 0 ? teamNames.getOrDefault(m.getTeam2Id(), "?") : null);
            String key = m.getCompetitionId() + "-" + m.getRound() + "-" + m.getTeam1Id() + "-" + m.getTeam2Id();
            entry.put("score", scoreByKey.get(key)); // null if not yet played
            matchesByRound.computeIfAbsent(m.getRound(), k -> new ArrayList<>()).add(entry);
        }

        List<Map<String, Object>> rounds = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : matchesByRound.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("round", e.getKey());
            r.put("matches", e.getValue());
            rounds.add(r);
        }

        Competition cup = competitionRepository.findById(cupId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cupId", cupId);
        result.put("cupName", cup != null ? cup.getName() : "");
        result.put("season", season);
        result.put("rounds", rounds);
        return result;
    }

    @GetMapping("/getCurrentSeason")
    public String getCurrentSeason() {

        return String.valueOf(round.getSeason());
    }

    @GetMapping("/getCurrentRound")
    public String getCurrentRound() {

        return String.valueOf(round.getRound());
    }

    @GetMapping("/isTransferWindowOpen")
    public boolean isTransferWindowOpen() {
        return transferWindowOpen;
    }

    public void setTransferWindowOpen(boolean open) {
        this.transferWindowOpen = open;
    }

    @GetMapping("/isManagerFired")
    public boolean isManagerFired(HttpServletRequest request) {
        return userContext.isCurrentUserFired(request);
    }

    @GetMapping("/availableJobs")
    public List<Map<String, Object>> getAvailableJobs() {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);

        // Find the human manager to check their reputation
        Human humanManager = allManagers.stream()
                .filter(m -> m.getTeamId() != null && m.getTeamId() == 0L && !m.isRetired())
                .findFirst().orElse(null);
        int humanRep = humanManager != null ? humanManager.getManagerReputation() : 500;

        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Team team : allTeams) {
            // Find current manager of this team
            Human currentMgr = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst().orElse(null);

            // Can apply if: no manager, or your reputation is at least 50% of the team's reputation
            boolean vacant = (currentMgr == null);
            boolean canApply = vacant || (humanRep >= team.getReputation() / 2);

            if (!canApply) continue;

            Map<String, Object> job = new LinkedHashMap<>();
            job.put("teamId", team.getId());
            job.put("teamName", team.getName());
            job.put("reputation", team.getReputation());
            job.put("league", getLeagueNameForTeam(team.getId()));

            if (vacant) {
                job.put("status", "Vacant");
            } else {
                job.put("status", "Available");
                job.put("currentManager", currentMgr.getName());
            }
            jobs.add(job);
        }

        // Fallback: if no jobs available (reputation too low), offer the weakest teams
        if (jobs.isEmpty()) {
            List<Team> sortedByRep = allTeams.stream()
                    .sorted(Comparator.comparingInt(Team::getReputation))
                    .limit(5)
                    .toList();

            for (Team team : sortedByRep) {
                Human currentMgr = allManagers.stream()
                        .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                        .findFirst().orElse(null);

                Map<String, Object> job = new LinkedHashMap<>();
                job.put("teamId", team.getId());
                job.put("teamName", team.getName());
                job.put("reputation", team.getReputation());
                job.put("league", getLeagueNameForTeam(team.getId()));
                job.put("status", currentMgr == null ? "Vacant" : "Available");
                if (currentMgr != null) {
                    job.put("currentManager", currentMgr.getName());
                }
                jobs.add(job);
            }
        }

        jobs.sort((a, b) -> Integer.compare((int) b.get("reputation"), (int) a.get("reputation")));
        return jobs;
    }

    private String getLeagueNameForTeam(long teamId) {
        try {
            List<Competition> comps = competitionRepository.findAll();
            for (Competition comp : comps) {
                if (comp.getTypeId() == 1 || comp.getTypeId() == 3) {
                    List<CompetitionTeamInfo> teams = competitionTeamInfoRepository.findAll().stream()
                            .filter(c -> c.getCompetitionId() == comp.getId() && c.getTeamId() == teamId)
                            .toList();
                    if (!teams.isEmpty()) return comp.getName();
                }
            }
        } catch (Exception e) { /* ignore */ }
        return "Unknown";
    }

    @PostMapping("/acceptJob")
    public String acceptJob(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        User currentUser = userContext.getUserOrNull(request);
        if (currentUser == null || !currentUser.isFired()) {
            return "You are not currently looking for a job.";
        }

        Long newTeamId = body.get("teamId");
        if (newTeamId == null) {
            return "No teamId provided.";
        }

        Team newTeam = teamRepository.findById(newTeamId).orElse(null);
        if (newTeam == null) {
            return "Team not found.";
        }

        // Find the human manager by managerId on User, or fallback to unemployed manager
        Human humanManager = null;
        if (currentUser.getManagerId() != null) {
            humanManager = humanRepository.findById(currentUser.getManagerId()).orElse(null);
        }
        if (humanManager == null) {
            humanManager = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == 0L)
                    .findFirst()
                    .orElse(null);
        }

        if (humanManager == null) {
            return "Human manager not found.";
        }

        // Remove existing manager from the target team if there is one
        Human existingManager = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                .filter(m -> m.getTeamId() != null && m.getTeamId().equals(newTeamId) && !m.isRetired())
                .findFirst()
                .orElse(null);

        if (existingManager != null && existingManager.getId() != humanManager.getId()) {
            existingManager.setTeamId(0L);
            existingManager.setRetired(true);
            humanRepository.save(existingManager);
        }

        humanManager.setTeamId(newTeamId);
        humanRepository.save(humanManager);

        // Update User: set new teamId and clear fired flag
        currentUser.setTeamId(newTeamId);
        currentUser.setLastTeamId(newTeamId);
        currentUser.setFired(false);
        userRepository.save(currentUser);

        // Update Round.humanTeamId to the new team
        round.setHumanTeamId(newTeamId);
        roundRepository.save(round);

        // Clear managerFired on GameCalendar if no more fired users remain
        if (!userContext.isAnyUserFired()) {
            List<GameCalendar> calendars = gameCalendarRepository.findAll();
            for (GameCalendar cal : calendars) {
                if (cal.isManagerFired()) {
                    cal.setManagerFired(false);
                    gameCalendarRepository.save(cal);
                }
            }
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(newTeamId);
        inbox.setSeasonNumber((int) round.getSeason());
        inbox.setRoundNumber(0);
        inbox.setTitle("Welcome to " + newTeam.getName() + "!");
        inbox.setContent("You have been appointed as the new manager of " + newTeam.getName() + ". Good luck!");
        inbox.setCategory("board");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);

        return "You have been appointed as manager of " + newTeam.getName() + "!";
    }

    @PostMapping("/closeTransferWindow")
    public String closeTransferWindow() {
        if (!transferWindowOpen) {
            return "Transfer window is not open";
        }

        System.out.println("=== CLOSING TRANSFER WINDOW - Starting new season ===");

        List<Long> teamIds = getAllTeams();

        // Refresh team budgets based on league position + European coefficient
        refreshTeamBudgets((int) round.getSeason());

        // Apply training effect (once per season, with full season multiplier)
        List<Long> allTeamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());
        applyTrainingEffect(allTeamIds);

        // save historical values
        Set<Long> competitions = competitionRepository.findAll()
                .stream()
                .mapToLong(Competition::getId)
                .boxed()
                .collect(Collectors.toSet());

        for (Long competitionId : competitions)
            this.saveHistoricalValues(competitionId, round.getSeason());
        this.saveAllPlayerTeamHistoricalRelations(round.getSeason());

        // Return all loaned players to their parent teams
        List<com.footballmanagergamesimulator.model.Loan> activeLoans = loanRepository.findAllByStatus("active");
        for (com.footballmanagergamesimulator.model.Loan loan : activeLoans) {
            Human loanedPlayer = humanRepository.findById(loan.getPlayerId()).orElse(null);
            if (loanedPlayer != null) {
                loanedPlayer.setTeamId(loan.getParentTeamId());
                humanRepository.save(loanedPlayer);
            }
            loan.setStatus("completed");
            loanRepository.save(loan);
        }
        if (!activeLoans.isEmpty()) {
            System.out.println("=== Returned " + activeLoans.size() + " loaned players to their parent teams ===");
        }

        // reset values
        this.resetCompetitionData();
        this.removeCompetitionData(round.getSeason() + 1);
        this.addImprovementToOverachievers();

        round.setRound(1);
        round.setSeason(round.getSeason() + 1);
        roundRepository.save(round);

        // add 1 year for each player
        _humanService.addOneYearToAge();
        _humanService.retirePlayers();

        personalizedTacticRepository.deleteAll(); // remove old tactics, as some player may retire

        for (Long teamId : teamIds) {
            TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
            if (teamFacilities != null)
                _humanService.addRegens(teamFacilities, teamId);
        }

        for (long teamId : teamIds) {
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human human : players) {
                long seasonCreated = human.getSeasonCreated();
                if (round.getSeason() - seasonCreated <= 2 && seasonCreated != 1L)
                    human.setCurrentStatus("Junior");
                else if (round.getSeason() - seasonCreated <= 6 && seasonCreated != 1L)
                    human.setCurrentStatus("Intermediate");
                else
                    human.setCurrentStatus("Senior");
                human.setTransferValue(calculateTransferValue(human.getAge(), human.getPosition(), human.getRating()));
                // Reset seasonal tracking for player relationships
                human.setSeasonMatchesPlayed(0);
                human.setConsecutiveBenched(0);
                // Fresh start: 50% chance to reset wantsTransfer
                if (human.isWantsTransfer() && new Random().nextDouble() < 0.5) {
                    human.setWantsTransfer(false);
                    human.setMorale(Math.min(100, human.getMorale() + 10));
                }
            }
            humanRepository.saveAll(players);
        }

        // Clear derby cache for new season
        teamPostMatchService.clearDerbyCache();

        // Handle contract expiries
        handleContractExpiries((int) round.getSeason());

        transferWindowOpen = false;

        // Handle expired scout contracts
        scoutManagementController.processExpiredContracts((int) round.getSeason());

        // Generate season objectives for all teams
        generateSeasonObjectives((int) round.getSeason());

        // Generate league fixtures for new season
        Set<Long> newLeagueCompIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> newSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
        newLeagueCompIds.addAll(newSecondLeagueCompIds);
        for (Long competitionId : newLeagueCompIds)
            this.getFixturesForRound(String.valueOf(competitionId), "1");

        // Create new GameCalendar for the new season
        int newSeason = (int) round.getSeason();
        List<GameCalendar> existingNewCal = gameCalendarRepository.findBySeason(newSeason);
        if (existingNewCal.isEmpty()) {
            GameCalendar newCalendar = new GameCalendar();
            newCalendar.setSeason(newSeason);
            newCalendar.setCurrentDay(1);
            newCalendar.setCurrentPhase("MORNING");
            newCalendar.setSeasonPhase("PRE_SEASON");
            newCalendar.setTransferWindowOpen(false);
            newCalendar.setManagerFired(false);
            newCalendar.setPaused(false);
            gameCalendarRepository.save(newCalendar);

            // Generate calendar events for the new season
            fixtureSchedulingService.generateSeasonCalendar(newSeason);
        }

        // Close old season's transfer window on calendar
        int oldSeason = newSeason - 1;
        List<GameCalendar> oldCals = gameCalendarRepository.findBySeason(oldSeason);
        for (GameCalendar oldCal : oldCals) {
            oldCal.setTransferWindowOpen(false);
            gameCalendarRepository.save(oldCal);
        }

        System.out.println("=== NEW SEASON " + newSeason + " STARTED ===");

        return "Transfer window closed. Season " + newSeason + " started.";
    }

    /**
     * Phase 1: Called at SEASON_END (day 340).
     * Processes final standings, relegation/promotion, European qualification,
     * AI transfers and loans, and evaluates season objectives.
     * Does NOT start the new season — that happens in processNewSeasonSetup().
     */
    private boolean endOfSeasonProcessed = false;
    private int endOfSeasonProcessedForSeason = -1;

    @Transactional
    public synchronized void processEndOfSeason(int season) {
        if (seasonTransitionInProgress) {
            System.out.println("=== processEndOfSeason: season " + season + " ALREADY IN PROGRESS, skipping ===");
            return;
        }
        if (endOfSeasonProcessed && endOfSeasonProcessedForSeason == season) {
            System.out.println("=== processEndOfSeason: season " + season + " ALREADY PROCESSED, skipping ===");
            return;
        }
        seasonTransitionInProgress = true;
        try {
        System.out.println("=== processEndOfSeason: season " + season + " ===");

        List<Long> teamIds = getAllTeams();

        // Final standings, relegation/promotion
        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3);
        leagueCompetitionIds.addAll(secondLeagueCompetitionIds);

        Map<Long, Long> leagueToCupMap = new HashMap<>();
        List<Competition> allComps = competitionRepository.findAll();
        for (Competition league : allComps) {
            if (league.getTypeId() == 1 || league.getTypeId() == 3) {
                allComps.stream()
                        .filter(c -> c.getTypeId() == 2 && c.getNationId() == league.getNationId())
                        .findFirst()
                        .ifPresent(cup -> leagueToCupMap.put(league.getId(), cup.getId()));
            }
        }

        // Ensure every team in every league has a TeamCompetitionDetail entry
        // (needed when encounters=0 and no matches were played)
        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> currentSeasonEntries = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason);
        for (Long id : leagueCompetitionIds) {
            List<Long> leagueTeamIds = currentSeasonEntries.stream()
                    .filter(cti -> cti.getCompetitionId() == id)
                    .map(CompetitionTeamInfo::getTeamId)
                    .distinct().toList();
            for (long tid : leagueTeamIds) {
                TeamCompetitionDetail existing = teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(tid, id);
                if (existing == null) {
                    TeamCompetitionDetail tcd = new TeamCompetitionDetail();
                    tcd.setTeamId(tid);
                    tcd.setCompetitionId(id);
                    tcd.setForm("");
                    teamCompetitionDetailRepository.save(tcd);
                }
            }
        }

        List<TeamCompetitionDetail> teamCompetitionDetails = teamCompetitionDetailRepository.findAll();
        for (Long id : leagueCompetitionIds) {
            int finalId = Math.toIntExact(id);
            List<TeamCompetitionDetail> teamCompetitionDetailList = teamCompetitionDetails.stream()
                    .filter(detail -> detail.getCompetitionId() == finalId)
                    .sorted((o1, o2) -> {
                        if (o1.getPoints() != o2.getPoints())
                            return o1.getPoints() < o2.getPoints() ? 1 : -1;
                        if (o1.getGoalDifference() != o2.getGoalDifference())
                            return o1.getGoalDifference() < o2.getGoalDifference() ? 1 : -1;
                        if (o1.getGoalsFor() != o2.getGoalsFor())
                            return o1.getGoalsFor() < o2.getGoalsFor() ? 1 : -1;
                        // Fallback: sort by team reputation (for when no matches were played)
                        Team teamA = teamRepository.findById(o1.getTeamId()).orElse(null);
                        Team teamB = teamRepository.findById(o2.getTeamId()).orElse(null);
                        int repA = teamA != null ? teamA.getReputation() : 0;
                        int repB = teamB != null ? teamB.getReputation() : 0;
                        return Integer.compare(repB, repA);
                    }).toList();

            int index = 1;
            int numTeams = teamCompetitionDetailList.size();
            int numCupRounds = numTeams > 0 ? (int) Math.ceil(Math.log(numTeams) / Math.log(2)) : 3;
            int numByes = (int) Math.pow(2, numCupRounds) - numTeams;

            for (TeamCompetitionDetail teamCompetitionDetail : teamCompetitionDetailList) {
                CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                if (id == 3L && index >= 11)
                    competitionTeamInfo.setCompetitionId(5L);
                else if (id == 5L && index <= 2)
                    competitionTeamInfo.setCompetitionId(3L);
                else
                    competitionTeamInfo.setCompetitionId(id);

                competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                competitionTeamInfo.setRound(1L);
                competitionTeamInfo.setTeamId(teamCompetitionDetail.getTeamId());
                competitionTeamInfoRepository.save(competitionTeamInfo);

                Long cupId = leagueToCupMap.get(id);
                if (cupId != null) {
                    CompetitionTeamInfo competitionTeamInfoCup = new CompetitionTeamInfo();
                    competitionTeamInfoCup.setCompetitionId(cupId);
                    competitionTeamInfoCup.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                    competitionTeamInfoCup.setRound(index <= numByes ? 2L : 1L);
                    competitionTeamInfoCup.setTeamId(teamCompetitionDetail.getTeamId());
                    competitionTeamInfoRepository.save(competitionTeamInfoCup);
                }
                index++;
            }
        }

        qualifyTeamsForEuropeanCompetitions();

        // Refresh team budgets BEFORE AI transfers so teams have money to spend
        refreshTeamBudgets((int) round.getSeason());

        // AI transfer market
        List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
        for (Long teamId : teamIds) {
            if (userContext.isHumanTeam(teamId)) continue;
            Team team = teamRepository.findById(teamId).orElse(new Team());
            playersForTransferMarket.addAll(_compositeTransferStrategy.playersToSell(team, humanRepository, getMinimumPositionNeeded()));
        }

        HashMap<String, List<PlayerTransferView>> transferMarket = new HashMap<>();
        for (PlayerTransferView playerTransferView : playersForTransferMarket) {
            if (transferMarket.containsKey(playerTransferView.getPosition()))
                transferMarket.get(playerTransferView.getPosition()).add(playerTransferView);
            else
                transferMarket.put(playerTransferView.getPosition(), new ArrayList<>(List.of(playerTransferView)));
        }

        Map<PlayerTransferView, List<BuyPlanTransferView>> buyPlan = new HashMap<>();
        for (Long teamId : teamIds) {
            if (userContext.isHumanTeam(teamId)) continue;
            Team team = teamRepository.findById(teamId).orElse(new Team());
            BuyPlanTransferView buyPlanTransferView = _compositeTransferStrategy.playersToBuy(team, humanRepository, getMaximumPositionAllowed());
            if (buyPlanTransferView == null) continue;

            for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {
                List<PlayerTransferView> playersInMarket = transferMarket.get(clubPlan.getPosition());
                if (playersInMarket == null) continue;
                for (PlayerTransferView player : playersInMarket) {
                    if (canBeTransfered(player, buyPlanTransferView, clubPlan)) {
                        if (buyPlan.containsKey(player))
                            buyPlan.get(player).add(buyPlanTransferView);
                        else {
                            List<BuyPlanTransferView> buyPlanTransferViews = new ArrayList<>();
                            buyPlanTransferViews.add(buyPlanTransferView);
                            buyPlan.put(player, buyPlanTransferViews);
                        }
                    }
                }
            }
            generateAiOffersForHumanPlayers(team, buyPlanTransferView);
        }

        HashMap<PlayerTransferView, BuyPlanTransferView> playerTransfered = new HashMap<>();
        List<Transfer> transfers = new ArrayList<>();
        for (Map.Entry<PlayerTransferView, List<BuyPlanTransferView>> pair : buyPlan.entrySet()) {
            if (pair.getValue().size() == 1) {
                playerTransfered.put(pair.getKey(), pair.getValue().get(0));
            } else {
                Collections.shuffle(pair.getValue());
                playerTransfered.put(pair.getKey(), pair.getValue().get(0));
            }
            PlayerTransferView playerTransferView = pair.getKey();
            BuyPlanTransferView buyPlanTransferView = playerTransfered.get(playerTransferView);

            Team sellTeam = teamRepository.findById(playerTransferView.getTeamId()).get();
            Team buyTeam = teamRepository.findById(buyPlanTransferView.getTeamId()).get();

            long transferFee = calculateTransferValue(playerTransferView.getAge(), playerTransferView.getPosition(), playerTransferView.getRating());
            if (transferFee > buyTeam.getTransferBudget()) continue;

            Human human = humanRepository.findById(playerTransferView.getPlayerId()).get();
            human.setTeamId(buyTeam.getId());
            human.setSeasonMatchesPlayed(0);
            human.setConsecutiveBenched(0);
            humanRepository.save(human);

            // Record transfer as financial transaction
            financeService.recordExpense(buyTeam.getId(), (int) round.getSeason(), 0,
                    "TRANSFER_BUY", "Bought " + human.getName(), transferFee);
            buyTeam = teamRepository.findById(buyTeam.getId()).orElse(buyTeam);
            buyTeam.setTransferBudget(buyTeam.getTransferBudget() - transferFee);
            teamRepository.save(buyTeam);

            financeService.recordTransaction(sellTeam.getId(), (int) round.getSeason(), 0,
                    "TRANSFER_SALE", "Sold " + human.getName(), transferFee);
            sellTeam = teamRepository.findById(sellTeam.getId()).orElse(sellTeam);
            teamRepository.save(sellTeam);

            Transfer transfer = new Transfer();
            transfer.setPlayerId(human.getId());
            transfer.setPlayerName(human.getName());
            transfer.setPlayerTransferValue(transferFee);
            transfer.setSellTeamId(sellTeam.getId());
            transfer.setSellTeamName(sellTeam.getName());
            transfer.setBuyTeamId(buyTeam.getId());
            transfer.setBuyTeamName(buyTeam.getName());
            transfer.setRating(human.getRating());
            transfer.setSeasonNumber(Long.parseLong(getCurrentSeason()));
            transfer.setPlayerAge(human.getAge());
            transferRepository.save(transfer);
            transfers.add(transfer);
        }

        // AI Loan Logic
        Random loanRandom = new Random();
        List<Team> allTeams = teamRepository.findAll();
        for (Long teamId : teamIds) {
            if (userContext.isHumanTeam(teamId)) continue;
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
            if (players.size() <= 18) continue;
            double avgRating = players.stream().mapToDouble(Human::getRating).average().orElse(0);
            List<Human> loanCandidates = players.stream()
                    .filter(p -> p.getAge() <= 22 && p.getRating() < avgRating && !p.isRetired())
                    .collect(Collectors.toList());
            Collections.shuffle(loanCandidates);
            int loansToMake = Math.min(loanCandidates.size(), 2);
            for (int i = 0; i < loansToMake; i++) {
                Human loanPlayer = loanCandidates.get(i);
                List<Team> potentialTeams = allTeams.stream()
                        .filter(t -> t.getId() != teamId && !userContext.isHumanTeam(t.getId()))
                        .collect(Collectors.toList());
                if (potentialTeams.isEmpty()) continue;
                Team loanTeam = potentialTeams.get(loanRandom.nextInt(potentialTeams.size()));
                Team parentTeam = teamRepository.findById(teamId).orElse(null);
                if (parentTeam == null) continue;
                long loanFee = (long) (loanPlayer.getTransferValue() * 0.05);
                loanPlayer.setTeamId(loanTeam.getId());
                humanRepository.save(loanPlayer);
                com.footballmanagergamesimulator.model.Loan loan = new com.footballmanagergamesimulator.model.Loan();
                loan.setPlayerId(loanPlayer.getId());
                loan.setPlayerName(loanPlayer.getName());
                loan.setParentTeamId(parentTeam.getId());
                loan.setParentTeamName(parentTeam.getName());
                loan.setLoanTeamId(loanTeam.getId());
                loan.setLoanTeamName(loanTeam.getName());
                loan.setSeasonNumber((int) round.getSeason());
                loan.setStatus("active");
                loan.setLoanFee(loanFee);
                loanRepository.save(loan);
            }
        }

        evaluateSeasonObjectives((int) round.getSeason());

        // Notify all human users about AI transfers
        if (!transfers.isEmpty()) {
            StringBuilder transferNews = new StringBuilder();
            for (Transfer transfer : transfers) {
                transferNews.append(transfer.getPlayerName())
                        .append(" (").append(String.format("%.0f", transfer.getRating())).append(")")
                        .append(": ").append(transfer.getSellTeamName())
                        .append(" → ").append(transfer.getBuyTeamName())
                        .append(" (").append(String.format("%,d", transfer.getPlayerTransferValue())).append(")\n");
            }
            for (long htId : userContext.getAllHumanTeamIds()) {
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(htId);
                inbox.setSeasonNumber((int) round.getSeason());
                inbox.setRoundNumber(0);
                inbox.setTitle("Transfer Market Summary - " + transfers.size() + " transfers completed");
                inbox.setContent(transferNews.toString().trim());
                inbox.setCategory("transfer");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }

        // Open the transfer window for user transfers
        transferWindowOpen = true;

        endOfSeasonProcessed = true;
        endOfSeasonProcessedForSeason = season;
        System.out.println("=== END OF SEASON " + season + " PROCESSED. " + transfers.size() + " AI transfers. Transfer window open. ===");
        } finally {
            seasonTransitionInProgress = false;
        }
    }

    /**
     * Phase 2: Called at SEASON_TRANSITION (day 360).
     * Sets up the new season: budget refresh, aging, regens, fixtures, scorers, etc.
     * Called AFTER the transfer window closes.
     */
    @Transactional
    public synchronized void processNewSeasonSetup(int season) {
        // Guard: if round.season already moved past this season, skip (prevents double transition)
        if (round.getSeason() > season) {
            System.out.println("=== processNewSeasonSetup: season " + season + " already transitioned (current=" + round.getSeason() + "), skipping ===");
            return;
        }
        System.out.println("=== processNewSeasonSetup: transitioning from season " + season + " ===");

        List<Long> teamIds = getAllTeams();

        // Note: refreshTeamBudgets is called in processEndOfSeason before AI transfers

        List<Long> allTeamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());
        applyTrainingEffect(allTeamIds);

        Set<Long> competitions = competitionRepository.findAll()
                .stream()
                .mapToLong(Competition::getId)
                .boxed()
                .collect(Collectors.toSet());
        for (Long competitionId : competitions)
            this.saveHistoricalValues(competitionId, round.getSeason());
        this.saveAllPlayerTeamHistoricalRelations(round.getSeason());

        // Return loaned players (handles buy obligations and salary adjustments)
        seasonTransitionService.processLoanReturns((int) round.getSeason());

        this.resetCompetitionData();
        this.removeCompetitionData(round.getSeason() + 1);
        this.addImprovementToOverachievers();

        round.setRound(1);
        round.setSeason(round.getSeason() + 1);
        roundRepository.save(round);

        _humanService.addOneYearToAge();
        _humanService.retirePlayers();
        personalizedTacticRepository.deleteAll();

        for (Long teamId : teamIds) {
            TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
            if (teamFacilities != null)
                _humanService.addRegens(teamFacilities, teamId);
        }

        for (long teamId : teamIds) {
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human human : players) {
                long seasonCreated = human.getSeasonCreated();
                if (round.getSeason() - seasonCreated <= 2 && seasonCreated != 1L)
                    human.setCurrentStatus("Junior");
                else if (round.getSeason() - seasonCreated <= 6 && seasonCreated != 1L)
                    human.setCurrentStatus("Intermediate");
                else
                    human.setCurrentStatus("Senior");
                human.setTransferValue(calculateTransferValue(human.getAge(), human.getPosition(), human.getRating()));
                human.setSeasonMatchesPlayed(0);
                human.setConsecutiveBenched(0);
                // Fresh start: 50% chance to reset wantsTransfer
                if (human.isWantsTransfer() && new Random().nextDouble() < 0.5) {
                    human.setWantsTransfer(false);
                    human.setMorale(Math.min(100, human.getMorale() + 10));
                }
            }
            humanRepository.saveAll(players);
        }

        // Clear derby cache for new season
        teamPostMatchService.clearDerbyCache();

        handleContractExpiries((int) round.getSeason());
        scoutManagementController.processExpiredContracts((int) round.getSeason());
        generateSeasonObjectives((int) round.getSeason());

        // Generate league fixtures for new season
        Set<Long> newLeagueCompIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> newSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
        newLeagueCompIds.addAll(newSecondLeagueCompIds);
        for (Long competitionId : newLeagueCompIds)
            this.getFixturesForRound(String.valueOf(competitionId), "1");

        // Initialize scorers for all players in the new season (batch optimized)
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Map<Long, List<Human>> playersByTeam = allPlayers.stream()
                .collect(Collectors.groupingBy(h -> h.getTeamId() != null ? h.getTeamId() : 0L));

        List<CompetitionTeamInfo> allNewCompTeamInfos = competitionTeamInfoRepository
                .findAllBySeasonNumber(round.getSeason());
        Map<Long, List<CompetitionTeamInfo>> compsByTeam = allNewCompTeamInfos.stream()
                .collect(Collectors.groupingBy(CompetitionTeamInfo::getTeamId));

        Map<Long, String> compNameCache = new HashMap<>();
        Map<Long, Integer> compTypeIdCache = new HashMap<>();
        for (CompetitionTeamInfo cti : allNewCompTeamInfos) {
            long cId = cti.getCompetitionId();
            compNameCache.computeIfAbsent(cId, id -> competitionRepository.findNameById(id));
            compTypeIdCache.computeIfAbsent(cId, id -> {
                Long typeId = competitionRepository.findTypeIdById(id);
                return typeId != null ? typeId.intValue() : 0;
            });
        }

        Map<Long, String> teamNameCache = new HashMap<>();
        List<Scorer> allNewScorers = new ArrayList<>();
        List<ScorerLeaderboardEntry> leaderboardUpdates = new ArrayList<>();

        for (Map.Entry<Long, List<Human>> entry : playersByTeam.entrySet()) {
            long teamId = entry.getKey();
            if (teamId == 0L) continue;
            List<Human> players = entry.getValue();
            List<CompetitionTeamInfo> teamComps = compsByTeam.getOrDefault(teamId, List.of());
            String teamName = teamNameCache.computeIfAbsent(teamId, id -> {
                Team t = teamRepository.findById(id).orElse(null);
                return t != null ? t.getName() : "Unknown";
            });

            for (Human human : players) {
                for (CompetitionTeamInfo cti : teamComps) {
                    Scorer scorer = new Scorer();
                    scorer.setPlayerId(human.getId());
                    scorer.setSeasonNumber((int) round.getSeason());
                    scorer.setTeamId(teamId);
                    scorer.setOpponentTeamId(-1);
                    scorer.setPosition(human.getPosition());
                    scorer.setTeamScore(-1);
                    scorer.setOpponentScore(-1);
                    scorer.setCompetitionId(cti.getCompetitionId());
                    scorer.setCompetitionTypeId(compTypeIdCache.getOrDefault(cti.getCompetitionId(), 0));
                    scorer.setTeamName(teamName);
                    scorer.setOpponentTeamName(null);
                    scorer.setCompetitionName(compNameCache.get(cti.getCompetitionId()));
                    scorer.setRating(human.getRating());
                    scorer.setGoals(0);
                    allNewScorers.add(scorer);
                }

                Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(human.getId());
                if (optionalScorerLeaderboardEntry.isPresent()) {
                    leaderboardUpdates.add(resetCurrentSeasonStats(optionalScorerLeaderboardEntry));
                }
            }
        }
        scorerRepository.saveAll(allNewScorers);
        scorerLeaderboardRepository.saveAll(leaderboardUpdates);

        // Reset end-of-season flag for the next season
        endOfSeasonProcessed = false;

        // Rebuild cup brackets for the new season — based on last season's standings.
        // This wipes whatever cup CompetitionTeamInfo/Match rows the legacy per-league
        // loop wrote earlier and replaces them with a proper full bracket.
        regenerateAllCupBrackets((int) round.getSeason());

        System.out.println("=== NEW SEASON " + round.getSeason() + " STARTED ===");
    }

    @GetMapping("/play")
    // @Scheduled(fixedDelay = 3000L) -- Disabled: game advance is now driven by GameAdvanceService
    public void play() {
      try {

        // Game paused during transfer window or any manager firing - wait for resolution
        if (transferWindowOpen || userContext.isAnyUserFired()) {
            return;
        }

        // Game paused while any user has a pending job offer (must accept/decline first)
        for (User u : userRepository.findAll()) {
            if (jobOfferService.userHasPendingOffer(u.getId())) {
                return;
            }
        }

        // initialization() is now called from @PostConstruct initializeRound()

        List<Long> teamIds = getAllTeams();

        if (round.getRound() > 50) {
            System.out.println("=== END OF SEASON " + round.getSeason() + " ===");

            // GF
            Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
            Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second league
            leagueCompetitionIds.addAll(secondLeagueCompetitionIds);

            // Build league-to-cup mapping by nationId
            Map<Long, Long> leagueToCupMap = new HashMap<>();
            List<Competition> allComps = competitionRepository.findAll();
            for (Competition league : allComps) {
                if (league.getTypeId() == 1 || league.getTypeId() == 3) {
                    // Find the cup with same nationId
                    allComps.stream()
                            .filter(c -> c.getTypeId() == 2 && c.getNationId() == league.getNationId())
                            .findFirst()
                            .ifPresent(cup -> leagueToCupMap.put(league.getId(), cup.getId()));
                }
            }

            List<TeamCompetitionDetail> teamCompetitionDetails = teamCompetitionDetailRepository.findAll();
            for (Long id: leagueCompetitionIds) {
                int finalId = Math.toIntExact(id);
                List<TeamCompetitionDetail> teamCompetitionDetailList = teamCompetitionDetails.stream()
                        .filter(detail -> detail.getCompetitionId() == finalId)
                        .sorted((o1, o2) -> {
                            if (o1.getPoints() != o2.getPoints())
                                return o1.getPoints() < o2.getPoints() ? 1 : -1;
                            if (o1.getGoalDifference() != o2.getGoalDifference())
                                return o1.getGoalDifference() < o2.getGoalDifference() ? 1 : -1;
                            if (o1.getGoalsFor() != o2.getGoalsFor())
                                return o1.getGoalsFor() < o2.getGoalsFor() ? 1 : -1;
                            return 0;
                        }).toList();

                int index = 1;
                int numTeams = teamCompetitionDetailList.size();
                int numCupRounds = numTeams > 0 ? (int) Math.ceil(Math.log(numTeams) / Math.log(2)) : 3;
                int numByes = (int) Math.pow(2, numCupRounds) - numTeams;

                for (TeamCompetitionDetail teamCompetitionDetail : teamCompetitionDetailList) {

                    CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                    if (id == 3L && index >= 11) // relegation from First League to Second League for Kess
                        competitionTeamInfo.setCompetitionId(5L);
                    else if (id == 5L && index <= 2)
                        competitionTeamInfo.setCompetitionId(3L);
                    else
                        competitionTeamInfo.setCompetitionId(id);

                    competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                    competitionTeamInfo.setRound(1L);
                    competitionTeamInfo.setTeamId(teamCompetitionDetail.getTeamId());

                    competitionTeamInfoRepository.save(competitionTeamInfo);

                    // Find cup for this league
                    Long cupId = leagueToCupMap.get(id);
                    if (cupId != null) {
                        CompetitionTeamInfo competitionTeamInfoCup = new CompetitionTeamInfo();
                        competitionTeamInfoCup.setCompetitionId(cupId);
                        competitionTeamInfoCup.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                        competitionTeamInfoCup.setRound(index <= numByes ? 2L : 1L);
                        competitionTeamInfoCup.setTeamId(teamCompetitionDetail.getTeamId());

                        competitionTeamInfoRepository.save(competitionTeamInfoCup);
                    }

                    index++;
                }
            }

            qualifyTeamsForEuropeanCompetitions();

            // transfer market

            List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
            for (Long teamId : teamIds) {
                // Skip human teams from automatic sell list - they negotiate manually
                if (userContext.isHumanTeam(teamId)) continue;

                Team team = teamRepository.findById(teamId).orElse(new Team());
                playersForTransferMarket.addAll(_compositeTransferStrategy.playersToSell(team, humanRepository, getMinimumPositionNeeded()));
            }

            HashMap<String, List<PlayerTransferView>> transferMarket = new HashMap<>();
            for (PlayerTransferView playerTransferView : playersForTransferMarket) {
                if (transferMarket.containsKey(playerTransferView.getPosition()))
                    transferMarket.get(playerTransferView.getPosition()).add(playerTransferView);
                else
                    transferMarket.put(playerTransferView.getPosition(), new ArrayList<>(List.of(playerTransferView)));
            }

            Map<PlayerTransferView, List<BuyPlanTransferView>> buyPlan = new HashMap<>();
            for (Long teamId : teamIds) {
                // Skip human teams from automatic buying - they negotiate manually
                if (userContext.isHumanTeam(teamId)) continue;

                Team team = teamRepository.findById(teamId).orElse(new Team());
                BuyPlanTransferView buyPlanTransferView = _compositeTransferStrategy.playersToBuy(team, humanRepository, getMaximumPositionAllowed());

                if (buyPlanTransferView == null)
                    continue;

                for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {

                    List<PlayerTransferView> playersInMarket = transferMarket.get(clubPlan.getPosition());
                    if (playersInMarket == null) continue;
                    for (PlayerTransferView player : playersInMarket) {

                        if (canBeTransfered(player, buyPlanTransferView, clubPlan)) {
                            if (buyPlan.containsKey(player))
                                buyPlan.get(player).add(buyPlanTransferView);
                            else {
                                List<BuyPlanTransferView> buyPlanTransferViews = new ArrayList<>();
                                buyPlanTransferViews.add(buyPlanTransferView);
                                buyPlan.put(player, buyPlanTransferViews);
                            }
                        }
                    }
                }

                // AI team tries to buy from human team
                generateAiOffersForHumanPlayers(team, buyPlanTransferView);
            }

            HashMap<PlayerTransferView, BuyPlanTransferView> playerTransfered = new HashMap<>();
            List<Transfer> transfers = new ArrayList<>();
            for (Map.Entry<PlayerTransferView, List<BuyPlanTransferView>> pair : buyPlan.entrySet()) {

                if (pair.getValue().size() == 1) {
                    // we have only 1 option, so we have a winner
                    playerTransfered.put(pair.getKey(), pair.getValue().get(0));
                } else {
                    // TODO different task
                    // we need to apply the logic based on the player personality
                    // personalities:
                    /*
                    1. choosing a club by the bigger wage
                    2. choosing the club with the bigger reputation
                    3. choosing the club playing at a higher continental level
                    4. choosing the club where he could have a higher chance to play / be in first eleven
                     */

                    Collections.shuffle(pair.getValue());
                    playerTransfered.put(pair.getKey(), pair.getValue().get(0));
                }
                PlayerTransferView playerTransferView = pair.getKey();
                BuyPlanTransferView buyPlanTransferView = playerTransfered.get(playerTransferView);

                Team sellTeam = teamRepository.findById(playerTransferView.getTeamId()).get();
                Team buyTeam = teamRepository.findById(buyPlanTransferView.getTeamId()).get();

                // Validate transfer budget before completing transfer
                long transferFee = calculateTransferValue(playerTransferView.getAge(), playerTransferView.getPosition(), playerTransferView.getRating());
                if (transferFee > buyTeam.getTransferBudget()) {
                    continue; // Skip transfer - team can't afford this player
                }

                Human human = humanRepository.findById(playerTransferView.getPlayerId()).get();
                human.setTeamId(buyTeam.getId());
                human.setSeasonMatchesPlayed(0);
                human.setConsecutiveBenched(0);
                humanRepository.save(human);

                // Record transfer as financial transaction
                financeService.recordExpense(buyTeam.getId(), (int) round.getSeason(), 0,
                        "TRANSFER_BUY", "Bought " + human.getName(), transferFee);
                buyTeam = teamRepository.findById(buyTeam.getId()).orElse(buyTeam);
                buyTeam.setTransferBudget(buyTeam.getTransferBudget() - transferFee);
                teamRepository.save(buyTeam);

                financeService.recordTransaction(sellTeam.getId(), (int) round.getSeason(), 0,
                        "TRANSFER_SALE", "Sold " + human.getName(), transferFee);
                sellTeam = teamRepository.findById(sellTeam.getId()).orElse(sellTeam);
                teamRepository.save(sellTeam);

                Transfer transfer = new Transfer();
                transfer.setPlayerId(human.getId());
                transfer.setPlayerName(human.getName());
                transfer.setPlayerTransferValue(transferFee);
                transfer.setSellTeamId(sellTeam.getId());
                transfer.setSellTeamName(sellTeam.getName());
                transfer.setBuyTeamId(buyTeam.getId());
                transfer.setBuyTeamName(buyTeam.getName());
                transfer.setRating(human.getRating());
                transfer.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                transfer.setPlayerAge(human.getAge());

                transferRepository.save(transfer);
                transfers.add(transfer);
            }

            transfers.sort((o1, o2) -> {
                if (o1.getRating() == o2.getRating())
                    return 0;
                return o1.getRating() > o2.getRating() ? 1 : -1;
            });

            // AI Loan Logic: surplus young players loaned out
            Random loanRandom = new Random();
            List<Team> allTeams = teamRepository.findAll();
            for (Long teamId : teamIds) {
                if (userContext.isHumanTeam(teamId)) continue;

                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                if (players.size() <= 18) continue;

                double avgRating = players.stream().mapToDouble(Human::getRating).average().orElse(0);

                List<Human> loanCandidates = players.stream()
                        .filter(p -> p.getAge() <= 22 && p.getRating() < avgRating && !p.isRetired())
                        .collect(Collectors.toList());

                Collections.shuffle(loanCandidates);
                int loansToMake = Math.min(loanCandidates.size(), 2); // max 2 loans per team

                for (int i = 0; i < loansToMake; i++) {
                    Human loanPlayer = loanCandidates.get(i);

                    // Find a random team that could use this position (not the same team, not human)
                    List<Team> potentialTeams = allTeams.stream()
                            .filter(t -> t.getId() != teamId && !userContext.isHumanTeam(t.getId()))
                            .collect(Collectors.toList());

                    if (potentialTeams.isEmpty()) continue;

                    Team loanTeam = potentialTeams.get(loanRandom.nextInt(potentialTeams.size()));
                    Team parentTeam = teamRepository.findById(teamId).orElse(null);
                    if (parentTeam == null) continue;

                    long loanFee = (long) (loanPlayer.getTransferValue() * 0.05);

                    loanPlayer.setTeamId(loanTeam.getId());
                    humanRepository.save(loanPlayer);

                    com.footballmanagergamesimulator.model.Loan loan = new com.footballmanagergamesimulator.model.Loan();
                    loan.setPlayerId(loanPlayer.getId());
                    loan.setPlayerName(loanPlayer.getName());
                    loan.setParentTeamId(parentTeam.getId());
                    loan.setParentTeamName(parentTeam.getName());
                    loan.setLoanTeamId(loanTeam.getId());
                    loan.setLoanTeamName(loanTeam.getName());
                    loan.setSeasonNumber((int) round.getSeason());
                    loan.setStatus("active");
                    loan.setLoanFee(loanFee);
                    loanRepository.save(loan);
                }
            }

            // Evaluate season objectives before opening transfer window
            evaluateSeasonObjectives((int) round.getSeason());

            // Transfer window is now open - game pauses until admin closes it
            transferWindowOpen = true;
            System.out.println("=== TRANSFER WINDOW OPEN - Game paused. Call /competition/closeTransferWindow to start new season ===");
            return;
        }

        if (round.getRound() == 1) {

            Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
            Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second leagues
            leagueCompetitionIds.addAll(secondLeagueCompetitionIds);

            for (Long competitionId : leagueCompetitionIds)
                this.getFixturesForRound(String.valueOf(competitionId), "1");

            // Generate season objectives for all teams (including season 1)
            generateSeasonObjectives((int) round.getSeason());

            if (round.getSeason() == 1) {
                List<Team> teams = teamRepository.findAll();
                Random random = new Random();
                for (Team team : teams) {
                    TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(team.getId());
                    squadGenerationService.generateInitialSquad(team, teamFacilities, 1, 100, random);

                    // create manager for each team
                    Human manager = new Human();
                    manager.setAge(random.nextInt(35, 70));
                    manager.setName(compositeNameGenerator.generateName(team.getCompetitionId()));
                    manager.setTeamId(team.getId());

                    int reputation = 100;
                    if (teamFacilities != null)
                        reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                    manager.setRating(random.nextInt(reputation - 20, reputation + 20));

                    manager.setPosition("Manager");
                    manager.setSeasonCreated(1L);
                    manager.setTypeId(TypeNames.MANAGER_TYPE);
                    String[] kit = tacticService.buildManagerTacticKit((int) manager.getRating(), random);
                    manager.setTacticStyle(kit[0]);
                    manager.setKnownTactics(kit[1]);
                    humanRepository.save(manager);
                }
            }

            // initiate all Scorers
            List<Team> allTeams = teamRepository.findAll();
            for (Team team: allTeams) {
                List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
                List<CompetitionTeamInfo> competitions = competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(team.getId(), round.getSeason());

                for (Human human: allPlayers) {

                    for (CompetitionTeamInfo competitionTeamInfo: competitions) {
                        Scorer scorer = new Scorer();
                        scorer.setPlayerId(human.getId());
                        scorer.setSeasonNumber((int) round.getSeason());
                        scorer.setTeamId(team.getId());
                        scorer.setOpponentTeamId(-1);
                        scorer.setPosition(human.getPosition());
                        scorer.setTeamScore(-1);
                        scorer.setOpponentScore(-1);
                        scorer.setCompetitionId(competitionTeamInfo.getCompetitionId());
                        scorer.setCompetitionTypeId(competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()) != null ? competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()).intValue() : 0);
                        scorer.setTeamName(team.getName());
                        scorer.setOpponentTeamName(null);
                        scorer.setCompetitionName(competitionRepository.findNameById(competitionTeamInfo.getCompetitionId()));
                        scorer.setRating(human.getRating());
                        scorer.setGoals(0);

                        scorerRepository.save(scorer);
                    }

                    if (scorerLeaderboardRepository.findByPlayerId(human.getId()).isEmpty()) {

                        ScorerLeaderboardEntry scorerLeaderboardEntry = new ScorerLeaderboardEntry();
                        scorerLeaderboardEntry.setPlayerId(human.getId());
                        scorerLeaderboardEntry.setAge(human.getAge());
                        scorerLeaderboardEntry.setName(human.getName());
                        scorerLeaderboardEntry.setGoals(0);
                        scorerLeaderboardEntry.setMatches(0);
                        scorerLeaderboardEntry.setCurrentRating(human.getRating());
                        scorerLeaderboardEntry.setBestEverRating(human.getRating());
                        scorerLeaderboardEntry.setSeasonOfBestEverRating((int) round.getSeason());
                        scorerLeaderboardEntry.setPosition(human.getPosition());
                        scorerLeaderboardEntry.setTeamId(human.getTeamId());
                        scorerLeaderboardEntry.setTeamName(team.getName());

                        scorerLeaderboardRepository.save(scorerLeaderboardEntry);
                    }

                    // reset stats for current season, as it just started
                    Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(human.getId());
                    ScorerLeaderboardEntry scorerLeaderboardEntry = resetCurrentSeasonStats(optionalScorerLeaderboardEntry);
                    scorerLeaderboardRepository.save(scorerLeaderboardEntry);
                }
            }
        }

        if (round.getRound() % 3 == 0) {
            List<Human> allTrainedPlayers = new ArrayList<>();
            for (long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                for (Human player : players) {
                    player = _humanService.trainPlayer(player, teamFacilities, Integer.parseInt(getCurrentSeason()));
                    allTrainedPlayers.add(player);
                }
            }
            if (!allTrainedPlayers.isEmpty()) humanRepository.saveAll(allTrainedPlayers);
        }

        // Decrement injury days for all active injuries at the start of each round
        decrementInjuryDays();

        // Cache all injured player IDs for this round (avoids N+1 queries)
        injuredPlayerIdsCache = injuryRepository.findAll().stream()
                .filter(i -> i.getDaysRemaining() > 0)
                .map(Injury::getPlayerId)
                .collect(Collectors.toSet());

        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second leagues
        Set<Long> cupCompetitionIds = getCompetitionIdsByCompetitionType(2); // cup



        for (Long competitionId : leagueCompetitionIds)
            this.simulateRound(String.valueOf(competitionId), round.getRound() - 1 + "");
        for (Long competitionId : secondLeagueCompetitionIds)
            this.simulateRound(String.valueOf(competitionId), round.getRound() - 1 + "");

        // Dynamic cup rounds based on team count
        for (Long cupId : cupCompetitionIds) {
            String competitionId = String.valueOf(cupId);
            int numTeams = getTeamCountForCompetition(cupId);
            int numRounds = Math.max(1, (int) Math.ceil(Math.log(numTeams) / Math.log(2)));
            int[] schedule = getCupSchedule(numRounds);

            for (int r = 0; r < numRounds; r++) {
                if (round.getRound() == schedule[r]) {
                    this.getFixturesForRound(competitionId, String.valueOf(r + 1));
                    this.simulateRound(competitionId, String.valueOf(r + 1));
                }
            }
        }

        // European competitions - League of Champions + Stars Cup (both with group stages)
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4); // League of Champions
        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5); // Stars Cup

        // LoC schedule:
        // Game round 1: Preliminary qualifying (round 0) - 4 teams from rank 7
        // Game round 3: Qualifying round (round 1) - 6 existing + 2 preliminary winners = 8 teams
        //   After qualifying: losers go to Stars Cup
        // Game rounds {5, 9, 14, 19, 24, 29}: Group stage matchdays 1-6 (competition rounds 2-7)
        // Game round 34: Quarter-Final (competition round 8)
        // Game round 40: Semi-Final (competition round 9)
        // Game round 46: Final (competition round 10)
        for (Long locId : locIds) {
            String competitionId = String.valueOf(locId);

            // Preliminary qualifying round at game round 1 (competition round 0)
            if (round.getRound() == 1) {
                this.getFixturesForRound(competitionId, "0");
                this.simulateRound(competitionId, "0");
                // Preliminary winners advance to round 1 (handled by getFixturesForRound knockout logic)
                // Preliminary losers go to Stars Cup
                assignLocLosersToStarsCup(locId, 0);
            }

            // Qualifying round at game round 3 (competition round 1)
            if (round.getRound() == 3) {
                this.getFixturesForRound(competitionId, "1");
                this.simulateRound(competitionId, "1");
                // Qualifying losers go to Stars Cup
                assignLocLosersToStarsCup(locId, 1);
            }

            // Group stage matchdays at game rounds 5, 9, 14, 19, 24, 29
            int[] groupMatchdays = {5, 9, 14, 19, 24, 29};
            for (int md = 0; md < groupMatchdays.length; md++) {
                if (round.getRound() == groupMatchdays[md]) {
                    if (md == 0) {
                        drawEuropeanGroups(locId, 2);
                        resetEuropeanStats(locId);
                        generateGroupStageFixtures(locId);
                    }
                    this.simulateRound(competitionId, String.valueOf(md + 2));
                    if (md == groupMatchdays.length - 1) {
                        qualifyFromGroupStage(locId);
                    }
                }
            }

            // Quarter-Final at game round 34
            if (round.getRound() == 34) {
                this.getFixturesForRound(competitionId, "8");
                this.simulateRound(competitionId, "8");
            }
            // Semi-Final at game round 40
            if (round.getRound() == 40) {
                this.getFixturesForRound(competitionId, "9");
                this.simulateRound(competitionId, "9");
            }
            // Final at game round 46
            if (round.getRound() == 46) {
                this.getFixturesForRound(competitionId, "10");
                this.simulateRound(competitionId, "10");
            }
        }

        // Stars Cup: group stage + playoff + knockout
        // Game round 4: Group MD1 (draw groups from all Stars Cup teams)
        // Game rounds {4, 7, 11, 16, 22, 27}: Group stage matchdays 1-6 (competition rounds 1-6)
        // Game round 31: Playoff (round 7) - group runners-up vs LoC 3rd place
        // Game round 36: QF (round 8) - group winners vs playoff winners
        // Game round 42: SF (round 9)
        // Game round 48: Final (round 10)
        for (Long starsCupId : starsCupIds) {
            String competitionId = String.valueOf(starsCupId);

            int[] scGroupMatchdays = {4, 7, 11, 16, 22, 27};
            for (int md = 0; md < scGroupMatchdays.length; md++) {
                if (round.getRound() == scGroupMatchdays[md]) {
                    if (md == 0) {
                        drawEuropeanGroups(starsCupId, 1);
                        resetEuropeanStats(starsCupId);
                        generateGroupStageFixtures(starsCupId);
                    }
                    this.simulateRound(competitionId, String.valueOf(md + 1));
                    if (md == scGroupMatchdays.length - 1) {
                        qualifyFromStarsCupGroupStage(starsCupId);
                    }
                }
            }

            // Playoff at game round 31 (round 7)
            if (round.getRound() == 31) {
                this.getFixturesForRound(competitionId, "7");
                this.simulateRound(competitionId, "7");
            }
            // QF at game round 36 (round 8)
            if (round.getRound() == 36) {
                this.getFixturesForRound(competitionId, "8");
                this.simulateRound(competitionId, "8");
            }
            // SF at game round 42 (round 9)
            if (round.getRound() == 42) {
                this.getFixturesForRound(competitionId, "9");
                this.simulateRound(competitionId, "9");
            }
            // Final at game round 48 (round 10)
            if (round.getRound() == 48) {
                this.getFixturesForRound(competitionId, "10");
                this.simulateRound(competitionId, "10");
            }
        }

        // Morale recovery: slowly pull extreme morale values back toward normal
        applyMoraleRecovery();

        round.setRound(round.getRound() + 1);
        teamTalkUsedThisRound = false;
        roundRepository.save(round);

      } catch (Exception e) {
        System.err.println("ERROR in play() at round " + round.getRound() + " season " + round.getSeason());
        e.printStackTrace();
      }
    }

    private static ScorerLeaderboardEntry resetCurrentSeasonStats(Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry) {

        ScorerLeaderboardEntry scorerLeaderboardEntry = optionalScorerLeaderboardEntry.get();
        scorerLeaderboardEntry.setCurrentSeasonGames(0);
        scorerLeaderboardEntry.setCurrentSeasonGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonLeagueGames(0);
        scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonCupGames(0);
        scorerLeaderboardEntry.setCurrentSeasonCupGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(0);
        scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(0);
        return scorerLeaderboardEntry;
    }

    private void addImprovementToOverachievers() { // todo aici

        System.out.println("Starting rating adjusting for season " + getCurrentSeason());
        Random random = new Random();
        List<ScorerLeaderboardEntry> allEntries = scorerLeaderboardRepository.findAll();

        for (ScorerLeaderboardEntry entry: allEntries) {

            if (entry.getCurrentSeasonGames() < 20) continue; // play at least half of the games

            double ratio = 0D;
            if (entry.getCurrentSeasonLeagueGames() > 0 && entry.getCurrentSeasonLeagueGoals() > 0) {
                ratio = (double) entry.getCurrentSeasonLeagueGoals() / entry.getCurrentSeasonLeagueGames();
            } else if (entry.getCurrentSeasonSecondLeagueGames() > 0 && entry.getCurrentSeasonSecondLeagueGoals() > 0){
                ratio = (double) entry.getCurrentSeasonSecondLeagueGoals() / entry.getCurrentSeasonSecondLeagueGames();
            }

            int ratingIncrease = 0;
            if (ratio >= 1.0D) { // amazing ratio, should increase rating by far
                ratingIncrease = random.nextInt(15, 20);
            } else if (ratio >= 0.75) {
                ratingIncrease = random.nextInt(10, 15);
            } else if (ratio >= 0.5) {
                ratingIncrease = random.nextInt(7, 10);
            } else if (ratio >= 0.4) {
                ratingIncrease = random.nextInt(3, 7);
            } else {
                continue;
            }
            if (entry.getLeagueGoals() < entry.getSecondLeagueGoals()) { // this means that the goals were mainly scored in the second league, currently players can not be transferred during a season
                ratingIncrease /= 2; // decrease rating, as overachieving in the second league is not that impressive
            }

            Optional<Human> human = humanRepository.findById(entry.getPlayerId());
            if (human.isPresent()){
                Human player = human.get();
                System.out.println("Player " + player.getName() + " from team " + entry.getTeamName() + " had rating increased from " + player.getRating() + " to " + (player.getRating() + ratingIncrease) + " because of ratio of " + ratio);
                player.setRating(player.getRating() + ratingIncrease);
                humanRepository.save(player);

                entry.setCurrentRating(player.getRating());
                if (entry.getCurrentRating() > entry.getBestEverRating()) {
                    entry.setBestEverRating(entry.getCurrentRating());
                    entry.setSeasonOfBestEverRating((int) round.getSeason());
                }
                scorerLeaderboardRepository.save(entry);
            }
        }
    }

    private void saveAllPlayerTeamHistoricalRelations(long seasonNumber) {

        Set<Long> teamIds = new HashSet<>();
        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll();

        for (TeamCompetitionDetail teamCompetitionDetail: teams) {

            teamIds.add(teamCompetitionDetail.getTeamId());
        }

        for (Long teamId: teamIds) {

            List<Human> allTeamPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player: allTeamPlayers) {
                TeamPlayerHistoricalRelation teamPlayerHistoricalRelation = new TeamPlayerHistoricalRelation();
                teamPlayerHistoricalRelation.setPlayerId(player.getId());
                teamPlayerHistoricalRelation.setTeamId(teamId);
                teamPlayerHistoricalRelation.setSeasonNumber(seasonNumber + 1);
                teamPlayerHistoricalRelation.setRating(player.getRating());

                teamPlayerHistoricalRelationRepository.save(teamPlayerHistoricalRelation);
            }
        }
    }

    public void removeCompetitionData(Long seasonNumber) {

        teamCompetitionDetailRepository.deleteAll();

        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository.findAllBySeasonNumber(seasonNumber);

        for (CompetitionTeamInfo team : competitionTeamInfos) {

            TeamCompetitionDetail newTeam = new TeamCompetitionDetail();
            newTeam.setTeamId(team.getTeamId());
            newTeam.setCompetitionId(team.getCompetitionId());
            newTeam.setForm("");
            teamCompetitionDetailRepository.save(newTeam);
        }
    }

    public void resetCompetitionData() {
        // Keep CompetitionTeamInfoDetail (match results) for historical viewing
        // Only delete CompetitionTeamInfoMatch (fixtures) as they're regenerated each season
        competitionTeamInfoMatchRepository.deleteAll();
    }

    public void saveHistoricalValues(Long competitionId, Long seasonNumber) {

        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll()
                .stream()
                .filter(teamCompetitionDetail -> teamCompetitionDetail.getCompetitionId() == competitionId)
                .collect(Collectors.toList());

        Collections.sort(teams, (a, b) -> {
            int pointsA = a.getPoints();
            int pointsB = b.getPoints();
            if (pointsA != pointsB)
                return pointsA > pointsB ? -1 : 1;

            int gdA = a.getGoalDifference();
            int gdB = b.getGoalDifference();
            if (gdA != gdB)
                return gdA > gdB ? -1 : 1;

            return a.getGoalsFor() > b.getGoalsFor() ? -1 : 1;
        });

        for (int i = 0; i < teams.size(); i++) {
            TeamCompetitionDetail team = teams.get(i);
            if (team.getCompetitionId() != competitionId)
                continue;
            competitionHistoryRepository.save(this.adaptCompetitionHistory(team, seasonNumber, 1 + i));
        }
    }

    private CompetitionHistory adaptCompetitionHistory(TeamCompetitionDetail team, Long seasonNumber, long position) {

        CompetitionHistory competitionHistory = new CompetitionHistory();

        Competition competition = competitionRepository.findById(team.getCompetitionId()).get();

        competitionHistory.setSeasonNumber(seasonNumber);
        competitionHistory.setLastPosition(position);
        competitionHistory.setCompetitionTypeId(competition.getTypeId());
        competitionHistory.setCompetitionName(competition.getName());
        competitionHistory.setCompetitionId(team.getCompetitionId());
        competitionHistory.setTeamId(team.getTeamId());
        competitionHistory.setGames(team.getGames());
        competitionHistory.setWins(team.getWins());
        competitionHistory.setDraws(team.getDraws());
        competitionHistory.setLoses(team.getLoses());
        competitionHistory.setGoalsFor(team.getGoalsFor());
        competitionHistory.setGoalsAgainst(team.getGoalsAgainst());
        competitionHistory.setGoalDifference(team.getGoalDifference());
        competitionHistory.setPoints(team.getPoints());
        competitionHistory.setForm(team.getForm());

        return competitionHistory;
    }

    @GetMapping("/historical/getTeams/{seasonNumber}/{competitionId}")
    public List<TeamCompetitionView> getHistoricalTeamDetails(@PathVariable(name = "competitionId") long competitionId, @PathVariable(name = "seasonNumber") long seasonNumber) {

        List<CompetitionHistory> teamParticipants = competitionHistoryRepository
                .findAll()
                .stream()
                .filter(competitionHistory -> competitionHistory.getCompetitionId() == competitionId && competitionHistory.getSeasonNumber() == seasonNumber)
                .collect(Collectors.toList());

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();

        for (CompetitionHistory competitionHistory : teamParticipants)
            teamCompetitionViews.add(adaptTeam(teamRepository.findById(competitionHistory.getTeamId()).orElse(new Team()), competitionHistory));

        return teamCompetitionViews;
    }

    @GetMapping("/getTeams/{competitionId}")
    public List<TeamCompetitionView> getTeamDetails(@PathVariable(name = "competitionId") long competitionId, HttpServletRequest request) {

        List<Long> teamParticipantIds = competitionTeamInfoRepository
                .findAll()
                .stream()
                .filter(competitionTeamInfo -> competitionTeamInfo.getCompetitionId() == competitionId && competitionTeamInfo.getSeasonNumber() == Long.valueOf(getCurrentSeason()))
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .toList();

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();

        for (Long teamId : teamParticipantIds) {
            TeamCompetitionDetail teamCompetitionDetail = teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(teamId, competitionId);
            Team team = teamRepository.findById(teamId).orElseGet(null);

            if (team == null || teamCompetitionDetail == null) {
                teamCompetitionDetail = new TeamCompetitionDetail();
                teamCompetitionDetail.setTeamId(teamId);
                teamCompetitionDetail.setCompetitionId(competitionId);
            }

            if (team != null) {
                TeamCompetitionView teamCompetitionView = adaptTeam(team, teamCompetitionDetail);
                teamCompetitionViews.add(teamCompetitionView);
            }
        }

        // Sort by points desc, then goal difference, then goals for
        teamCompetitionViews.sort((a, b) -> {
            int ptsA = a.getPoints() != null ? Integer.parseInt(a.getPoints()) : 0;
            int ptsB = b.getPoints() != null ? Integer.parseInt(b.getPoints()) : 0;
            if (ptsA != ptsB) return ptsB - ptsA;
            int gdA = a.getGoalDifference() != null ? Integer.parseInt(a.getGoalDifference()) : 0;
            int gdB = b.getGoalDifference() != null ? Integer.parseInt(b.getGoalDifference()) : 0;
            if (gdA != gdB) return gdB - gdA;
            int gfA = a.getGoalsFor() != null ? Integer.parseInt(a.getGoalsFor()) : 0;
            int gfB = b.getGoalsFor() != null ? Integer.parseInt(b.getGoalsFor()) : 0;
            return gfB - gfA;
        });

        // Set position based on sorted order
        Long humanTeamId = userContext.getTeamIdOrNull(request);
        for (int i = 0; i < teamCompetitionViews.size(); i++) {
            teamCompetitionViews.get(i).setPosition(i + 1);
            teamCompetitionViews.get(i).setHumanTeam(humanTeamId != null && teamCompetitionViews.get(i).getTeamId() == humanTeamId);
        }

        return teamCompetitionViews;
    }

    @GetMapping("/getAllCompetitions")
    public List<Competition> getAllCompetitions() {

        return competitionRepository
                .findAll();
    }

    @GetMapping("/getAllCompetitions/{typeId}")
    public List<Competition> getAllCompetitionsByTypeId(@PathVariable(name = "typeId") long typeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == typeId)
                .collect(Collectors.toList());
    }

    @GetMapping("/getTeamCompetitions/{teamId}")
    public List<Map<String, Object>> getTeamCompetitions(@PathVariable(name = "teamId") long teamId) {

        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> teamCompetitions = competitionTeamInfoRepository
                .findAllByTeamIdAndSeasonNumber(teamId, currentSeason);

        List<Map<String, Object>> result = new ArrayList<>();

        for (CompetitionTeamInfo cti : teamCompetitions) {
            Map<String, Object> comp = new HashMap<>();
            Competition competition = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
            if (competition == null) continue;

            comp.put("competitionId", competition.getId());
            comp.put("name", competition.getName());
            comp.put("typeId", competition.getTypeId());

            // Get standings for league competitions (typeId 1 or 3)
            if (competition.getTypeId() == 1 || competition.getTypeId() == 3) {
                TeamCompetitionDetail detail = teamCompetitionDetailRepository
                        .findFirstByTeamIdAndCompetitionId(teamId, competition.getId());
                if (detail != null) {
                    comp.put("games", detail.getGames());
                    comp.put("wins", detail.getWins());
                    comp.put("draws", detail.getDraws());
                    comp.put("loses", detail.getLoses());
                    comp.put("goalsFor", detail.getGoalsFor());
                    comp.put("goalsAgainst", detail.getGoalsAgainst());
                    comp.put("goalDifference", detail.getGoalDifference());
                    comp.put("points", detail.getPoints());
                    comp.put("form", detail.getForm());

                    // Calculate position
                    List<TeamCompetitionDetail> allTeams = teamCompetitionDetailRepository.findAll()
                            .stream()
                            .filter(d -> d.getCompetitionId() == competition.getId())
                            .sorted((a, b) -> {
                                if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                                if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                                return b.getGoalsFor() - a.getGoalsFor();
                            })
                            .toList();
                    int position = 1;
                    for (TeamCompetitionDetail t : allTeams) {
                        if (t.getTeamId() == teamId) break;
                        position++;
                    }
                    comp.put("position", position);
                    comp.put("totalTeams", allTeams.size());
                }
            }

            // For cups (typeId 2) and Stars Cup (typeId 5), add current round info
            if (competition.getTypeId() == 2 || competition.getTypeId() == 5) {
                comp.put("cupRound", cti.getRound());
            }

            // For LoC (typeId 4), add group info
            if (competition.getTypeId() == 4) {
                comp.put("groupNumber", cti.getGroupNumber());
                comp.put("cupRound", cti.getRound());
            }

            result.add(comp);
        }

        return result;
    }

    @GetMapping("/getCoefficients")
    public List<Map<String, Object>> getCoefficients() {
        return getCountryCoefficients();
    }

    @GetMapping("/getEuropeanGroups/{competitionId}/{season}")
    public List<Map<String, Object>> getEuropeanGroups(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") long season) {

        long currentSeason = Long.parseLong(getCurrentSeason());

        // CompetitionTeamInfo persists across seasons, so group assignments are always available
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(season).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getGroupNumber() > 0)
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompetitionTeamInfo cti : entries) {
            Map<String, Object> entry = new HashMap<>();
            Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
            entry.put("teamId", cti.getTeamId());
            entry.put("teamName", team != null ? team.getName() : "Unknown");
            entry.put("groupNumber", cti.getGroupNumber());
            entry.put("potNumber", cti.getPotNumber());

            if (season == currentSeason) {
                // Current season: compute group standings from match results only (exclude knockout rounds)
                Competition comp = competitionRepository.findById(competitionId).orElse(null);
                int groupRoundMin = 2, groupRoundMax = 7; // LoC defaults
                if (comp != null && comp.getTypeId() == 5) {
                    groupRoundMin = 1; groupRoundMax = 6; // Stars Cup
                }
                int games = 0, wins = 0, draws = 0, loses = 0, goalsFor = 0, goalsAgainst = 0;
                long teamId = cti.getTeamId();
                for (int r = groupRoundMin; r <= groupRoundMax; r++) {
                    List<CompetitionTeamInfoDetail> matchesAsHome = competitionTeamInfoDetailRepository
                            .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, r, season)
                            .stream().filter(d -> d.getTeam1Id() == teamId).toList();
                    List<CompetitionTeamInfoDetail> matchesAsAway = competitionTeamInfoDetailRepository
                            .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, r, season)
                            .stream().filter(d -> d.getTeam2Id() == teamId).toList();
                    for (CompetitionTeamInfoDetail d : matchesAsHome) {
                        if (d.getScore() == null) continue;
                        String[] parts = d.getScore().split("-");
                        if (parts.length != 2) continue;
                        int g1 = Integer.parseInt(parts[0].trim()), g2 = Integer.parseInt(parts[1].trim());
                        games++; goalsFor += g1; goalsAgainst += g2;
                        if (g1 > g2) wins++; else if (g1 < g2) loses++; else draws++;
                    }
                    for (CompetitionTeamInfoDetail d : matchesAsAway) {
                        if (d.getScore() == null) continue;
                        String[] parts = d.getScore().split("-");
                        if (parts.length != 2) continue;
                        int g1 = Integer.parseInt(parts[0].trim()), g2 = Integer.parseInt(parts[1].trim());
                        games++; goalsFor += g2; goalsAgainst += g1;
                        if (g2 > g1) wins++; else if (g2 < g1) loses++; else draws++;
                    }
                }
                entry.put("games", games);
                entry.put("wins", wins);
                entry.put("draws", draws);
                entry.put("loses", loses);
                entry.put("goalsFor", goalsFor);
                entry.put("goalsAgainst", goalsAgainst);
                entry.put("goalDifference", goalsFor - goalsAgainst);
                entry.put("points", wins * 3 + draws);
            } else {
                // Previous season: use CompetitionHistory
                Optional<CompetitionHistory> histOpt = competitionHistoryRepository.findAll().stream()
                        .filter(h -> h.getTeamId() == cti.getTeamId()
                                && h.getCompetitionId() == competitionId
                                && h.getSeasonNumber() == season)
                        .findFirst();
                if (histOpt.isPresent()) {
                    CompetitionHistory hist = histOpt.get();
                    entry.put("games", hist.getGames());
                    entry.put("wins", hist.getWins());
                    entry.put("draws", hist.getDraws());
                    entry.put("loses", hist.getLoses());
                    entry.put("goalsFor", hist.getGoalsFor());
                    entry.put("goalsAgainst", hist.getGoalsAgainst());
                    entry.put("goalDifference", hist.getGoalDifference());
                    entry.put("points", hist.getPoints());
                } else {
                    entry.put("games", 0); entry.put("wins", 0); entry.put("draws", 0);
                    entry.put("loses", 0); entry.put("goalsFor", 0); entry.put("goalsAgainst", 0);
                    entry.put("goalDifference", 0); entry.put("points", 0);
                }
            }
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/getMatchesByCompetitionAndSeason/{competitionId}/{season}")
    public List<CompetitionTeamInfoDetail> getMatchesByCompetitionAndSeason(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") long season) {

        List<CompetitionTeamInfoDetail> results = competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && d.getSeasonNumber() == season)
                .toList();

        // Debug: count matches per round
        Map<Long, Long> matchesPerRound = results.stream()
                .collect(Collectors.groupingBy(CompetitionTeamInfoDetail::getRoundId, Collectors.counting()));
        System.out.println("=== getMatchesByCompetitionAndSeason comp=" + competitionId + " season=" + season
                + " total=" + results.size() + " byRound=" + matchesPerRound);

        return results;
    }

    @GetMapping("/getCompetitionName/{competitionId}")
    public String getCompetitionName(@PathVariable(name = "competitionId") long competitionId) {
        return competitionRepository.findById(competitionId)
                .map(Competition::getName)
                .orElse("Unknown Competition");
    }

    @GetMapping("/getCupRoundCount/{competitionId}")
    public Map<String, Object> getCupRoundCount(@PathVariable(name = "competitionId") long competitionId) {
        Map<String, Object> result = new HashMap<>();
        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        if (comp == null) {
            result.put("totalRounds", 0);
            result.put("typeId", 0);
            return result;
        }
        result.put("typeId", comp.getTypeId());

        if (comp.getTypeId() == 2) {
            // Cup: compute from team count
            int numTeams = getTeamCountForCompetition(competitionId);
            if (numTeams == 0) {
                // Fallback: check from match history
                long maxRound = competitionTeamInfoDetailRepository.findAll().stream()
                        .filter(d -> d.getCompetitionId() == competitionId)
                        .mapToLong(CompetitionTeamInfoDetail::getRoundId)
                        .max().orElse(0);
                result.put("totalRounds", (int) maxRound);
            } else {
                int numRounds = Math.max(1, (int) Math.ceil(Math.log(numTeams) / Math.log(2)));
                result.put("totalRounds", numRounds);
            }
        } else if (comp.getTypeId() == 5) {
            // Stars Cup: rounds 1-6 = group stage, round 7 = playoff, rounds 8-10 = knockout
            result.put("totalRounds", 10);
            result.put("groupRounds", 6);
            result.put("playoffRound", 7);
        } else if (comp.getTypeId() == 4) {
            // LoC: round 0 = preliminary, round 1 = qualifying, rounds 2-7 = group stage, rounds 8-10 = knockout
            result.put("totalRounds", 10);
            result.put("groupRounds", 7);
            result.put("qualifyingRounds", 1);
            result.put("preliminaryRounds", 1);
        } else {
            result.put("totalRounds", 0);
        }
        return result;
    }

    private TeamCompetitionView adaptTeam(Team team, CompetitionHistory teamCompetitionHistory) {

        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        // Team information
        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        // TeamCompetitionDetail
        teamCompetitionView.setGames(String.valueOf(teamCompetitionHistory.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionHistory.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionHistory.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionHistory.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionHistory.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionHistory.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionHistory.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionHistory.getPoints()));
        teamCompetitionView.setForm(teamCompetitionHistory.getForm());

        return teamCompetitionView;
    }

    private TeamCompetitionView adaptTeam(Team team, TeamCompetitionDetail teamCompetitionDetail) {

        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        // Team information
        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        // TeamCompetitionDetail
        teamCompetitionView.setGames(String.valueOf(teamCompetitionDetail.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionDetail.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionDetail.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionDetail.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionDetail.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionDetail.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionDetail.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionDetail.getPoints()));
        teamCompetitionView.setForm(teamCompetitionDetail.getForm());
        teamCompetitionView.setPositions(teamCompetitionDetail.getLast10Positions());

        return teamCompetitionView;
    }

    private long getNextRound(long previousRound) {
        return previousRound + 1;
    }

    private boolean isKnockoutRound(long competitionId, long roundId) {
        return europeanCompetitionService.isKnockoutRound(competitionId, roundId);
    }

    @GetMapping("getResults/{competitionId}/{roundId}")
    public List<CompetitionTeamInfoDetail> getResults(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long currentSeason = Long.parseLong(getCurrentSeason());

        List<CompetitionTeamInfoDetail> competitionTeamInfoDetails =
                competitionTeamInfoDetailRepository
                        .findAll()
                        .stream()
                        .filter(d -> d.getRoundId() == _roundId)
                        .filter(d -> d.getCompetitionId() == _competitionId)
                        .filter(d -> d.getSeasonNumber() == currentSeason)
                        .toList();

        return competitionTeamInfoDetails;

    }

    @GetMapping("getResults/{competitionId}/{roundId}/{season}")
    public List<CompetitionTeamInfoDetail> getResultsBySeason(
            @PathVariable(name = "competitionId") String competitionId,
            @PathVariable(name = "roundId") String roundId,
            @PathVariable(name = "season") long season) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        return competitionTeamInfoDetailRepository
                .findAll()
                .stream()
                .filter(d -> d.getRoundId() == _roundId)
                .filter(d -> d.getCompetitionId() == _competitionId)
                .filter(d -> d.getSeasonNumber() == season)
                .toList();
    }

    @GetMapping("getParticipants/{competitionId}/{roundId}")
    public List<Long> getParticipants(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(_roundId, _competitionId, Long.parseLong(getCurrentSeason()));

        List<Long> participants = new ArrayList<>(competitionTeamInfos
                .stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .collect(Collectors.toSet()));

        return participants;
    }

    @GetMapping("getFuturesMatches/{competitionId}/{roundId}")
    public List<TeamMatchView> getNotPlayedMatches(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfoMatch> futureMatches =
                competitionTeamInfoMatchRepository
                        .findAll()
                        .stream()
                        .filter(competitionTeamInfoMatch -> competitionTeamInfoMatch.getCompetitionId() == _competitionId && competitionTeamInfoMatch.getRound() == _roundId
                                && competitionTeamInfoMatch.getSeasonNumber().equals(getCurrentSeason()))
                        .toList();

        List<TeamMatchView> matchViews = new ArrayList<>();

        for (CompetitionTeamInfoMatch match : futureMatches)
            matchViews.add(adaptCompetitionTeamInfoMatch(match, _competitionId, _roundId));

        return matchViews;
    }

    private TeamMatchView adaptCompetitionTeamInfoMatch(CompetitionTeamInfoMatch match, long competitionId, long roundId) {

        TeamMatchView teamMatchView = new TeamMatchView();
        teamMatchView.setTeamName1(teamRepository.findById(match.getTeam1Id()).get().getName());
        teamMatchView.setTeamName2(teamRepository.findById(match.getTeam2Id()).get().getName());

        CompetitionTeamInfoDetail matchDetail = competitionTeamInfoDetailRepository.findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(competitionId, roundId, match.getTeam1Id(), match.getTeam2Id(), Long.parseLong(getCurrentSeason())).stream().findFirst().orElse(null);
        if (matchDetail != null)
            teamMatchView.setScore(matchDetail.getScore());
        else
            teamMatchView.setScore("-");

        return teamMatchView;
    }

    @GetMapping("getFixtures/{competitionId}/{roundId}")
    public void getFixturesForRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<Long> participants = this.getParticipants(competitionId, roundId);

        // we need to get matches and to add them into CompetitionTeamInfoMatch
        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3);

        if (leagueCompetitionIds.contains(_competitionId) || secondLeagueCompetitionIds.contains(_competitionId)) {
            // Guard: league fixtures are generated once per season (all rounds at once)
            // from the round=1 entry point. Skip if any round already exists for this
            // competition+season to prevent duplicating the entire schedule.
            List<Long> existingRounds = competitionTeamInfoMatchRepository
                    .findDistinctRoundsByCompetitionIdAndSeasonNumber(_competitionId, getCurrentSeason());
            if (!existingRounds.isEmpty()) {
                System.out.println("=== getFixturesForRound league: comp=" + competitionId
                        + " season=" + getCurrentSeason() + " already has " + existingRounds.size()
                        + " rounds, skipping");
                return;
            }

            List<List<List<Long>>> schedule = roundRobin.getSchedule(participants);
            int currentRound = 1;

            // Encounters from league-config.json (default: 2 for 18+ teams, 4 for smaller)
            String compName = competitionRepository.findNameById(_competitionId);
            int encounters = leagueConfigService.getEncounters(compName, participants.size());
            int iterations = encounters / 2;

            boolean reverse = true;

            for (int i = 0; i < iterations; i++) {

                for (List<List<Long>> round : schedule) {

                    reverse = !reverse;
                    for (List<Long> match : round) {
                        long teamHomeId = match.get(0);
                        long teamAwayId = match.get(1);

                        CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                        competitionTeamInfoMatch.setCompetitionId(_competitionId);
                        competitionTeamInfoMatch.setRound(currentRound);
                        if (reverse) {
                            competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                            competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                        } else {
                            competitionTeamInfoMatch.setTeam1Id(teamAwayId);
                            competitionTeamInfoMatch.setTeam2Id(teamHomeId);
                        }
                        competitionTeamInfoMatch.setSeasonNumber(getCurrentSeason());
                        competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
                    }
                    currentRound++;
                }
            }


        } else {

            // Guard: skip if fixtures for this knockout round already exist (prevents duplicates)
            List<CompetitionTeamInfoMatch> existingKnockout = competitionTeamInfoMatchRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(_competitionId, _roundId, getCurrentSeason());
            if (!existingKnockout.isEmpty()) {
                System.out.println("=== getFixturesForRound knockout: comp=" + competitionId + " round=" + roundId + " already drawn, skipping");
                return;
            }

            System.out.println("=== getFixturesForRound knockout: comp=" + competitionId + " round=" + roundId + " participants=" + participants.size());

            // European competitions: use seeded draws for specific rounds
            Set<Long> locIdsForDraw = getCompetitionIdsByCompetitionType(4);
            Set<Long> starsCupIdsForDraw = getCompetitionIdsByCompetitionType(5);

            if (locIdsForDraw.contains(_competitionId) && (_roundId == 0 || _roundId == 1)) {
                // LoC preliminary (round 0) or qualifying (round 1): seeded vs unseeded by coefficient
                drawEuropeanKnockoutSeeded(_competitionId, _roundId, participants);
                return;
            }
            if (starsCupIdsForDraw.contains(_competitionId) && _roundId == 7) {
                // Stars Cup playoff: LoC 3rd place (seeded) vs SC runners-up (unseeded)
                drawStarsCupPlayoffSeeded(_competitionId, _roundId, participants);
                return;
            }

            // Default knockout draw (cups, European QF/SF/Final, etc.)
            Collections.shuffle(participants);

            // If odd number of participants, give the last team a bye to next round
            if (participants.size() % 2 != 0) {
                long byeTeamId = participants.remove(participants.size() - 1);
                long nextRoundForBye = _roundId + 1;
                CompetitionTeamInfo byeEntry = new CompetitionTeamInfo();
                byeEntry.setTeamId(byeTeamId);
                byeEntry.setCompetitionId(_competitionId);
                byeEntry.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                byeEntry.setRound(nextRoundForBye);
                competitionTeamInfoRepository.save(byeEntry);
            }

            for (int i = 0; i < participants.size(); i += 2) {
                long teamHomeId = participants.get(i);
                long teamAwayId = participants.get(i + 1);

                CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                competitionTeamInfoMatch.setCompetitionId(_competitionId);
                competitionTeamInfoMatch.setRound(_roundId);
                competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                competitionTeamInfoMatch.setSeasonNumber(getCurrentSeason());
                competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
            }
        }
    }

    // simulateRound + its batched AI helpers + per-round caches were extracted
    // to MatchSimulationOrchestrator (Stage 2 of matchday-orchestration
    // extraction). The REST mapping stays here; everything else delegates.
    @GetMapping("simulateRound/{competitionId}/{roundId}")
    public void simulateRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {
        matchSimulationOrchestrator.simulateRound(competitionId, roundId);
    }


    public double adjustTeamPowerByTacticalProperties(double teamRating, double opponentRating, PersonalizedTactic teamTactic) {

        double difference = teamRating - opponentRating;
        int percentage = 0;

        // 1. SAFETY FIRST: Setăm valori default (scăpăm de NullPointerException)
        // Aceste string-uri trebuie să fie identice cu ce trimite Frontend-ul
        String mentality = teamTactic.getMentality() != null ? teamTactic.getMentality() : "Balanced";
        String timeWasting = teamTactic.getTimeWasting() != null ? teamTactic.getTimeWasting() : "Sometimes";
        String inPossession = teamTactic.getInPossession() != null ? teamTactic.getInPossession() : "Standard";
        String passingType = teamTactic.getPassingType() != null ? teamTactic.getPassingType() : "Normal";
        String tempo = teamTactic.getTempo() != null ? teamTactic.getTempo() : "Standard";

        // --- LOGICA PENTRU MENTALITY VS DIFFERENCE ---

        if (difference > 500) { // Suntem mult mai buni
            if ("Very Attacking".equals(mentality)) percentage += 25;
            else if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 10;
            else if ("Very Defensive".equals(mentality)) percentage -= 25;

        } else if (difference > 200) { // Suntem puțin mai buni
            if ("Very Attacking".equals(mentality)) percentage += 15;
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15;

        } else if (difference >= -200 && difference <= 200) { // Meci echilibrat
            if ("Very Attacking".equals(mentality)) percentage -= 15; // Prea riscant
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15; // Prea pasiv

        } else if (difference < -200 && difference > -500) { // Suntem mai slabi
            if ("Very Attacking".equals(mentality)) percentage -= 15;
            else if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15;

        } else if (difference < -500) { // Suntem mult mai slabi
            if ("Very Attacking".equals(mentality)) percentage -= 25;
            else if ("Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 10;
            else if ("Very Defensive".equals(mentality)) percentage += 25;
        }

        // --- LOGICA PENTRU TIME WASTING ---
        // Frontend trimite: 'Never', 'Sometimes', 'Frequently', 'Always'
        // Mapăm 'Frequently' și 'Always' ca fiind YES (trag de timp)

        if ("Frequently".equals(timeWasting) || "Always".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Very Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 10;

        } else if ("Never".equals(timeWasting) || "Sometimes".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 10;
        }

        // --- LOGICA PENTRU POSSESSION ---
        // Frontend trimite: 'Keep Ball', 'Free Ball Early' (sau Standard)

        if ("Keep Ball".equals(inPossession)) {
            if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Very Attacking".equals(mentality)) percentage -= 15; // Prea lent pentru very attacking
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15; // Periculos sa tii mingea in aparare

        } else if ("Free Ball Early".equals(inPossession)) { // Sau 'Direct Passing'
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15; // Degajari lungi
        }

        // --- LOGICA PENTRU PASSING & TEMPO ---
        // Aici trebuie mare atenție la string-uri. Presupunem valorile standard.
        // Frontend Tempo: 'Much Lower', 'Lower', 'Standard', 'Higher', 'Much Higher'

        if ("Short".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage += 5;
            else if ("Lower".equals(tempo)) percentage += 10;
            else if ("Standard".equals(tempo)) percentage += 15;
            else if ("Higher".equals(tempo)) percentage += 20;
            else if ("Much Higher".equals(tempo)) percentage += 25; // Tiki Taka rapid

        } else if ("Normal".equals(passingType) || "Standard".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 10;
            else if ("Lower".equals(tempo)) percentage -= 5;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 5;
            else if ("Much Higher".equals(tempo)) percentage += 10;

        } else if ("Long".equals(passingType) || "Direct".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 30; // Pase lungi lent = pierzi mingea
            else if ("Lower".equals(tempo)) percentage -= 25;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 25; // Contraatac rapid
            else if ("Much Higher".equals(tempo)) percentage += 30;
        }

        // Calcul final
        return teamRating + (teamRating * percentage / 100D);
    }
    @GetMapping("/getAllCompetitionTypes")
    public List<CompetitionType> getAllCompetitionTypes() {

        List<CompetitionType> competitionTypes = new ArrayList<>();

        CompetitionType championship = new CompetitionType();
        championship.setId(1);
        championship.setTypeName("Championship");
        championship.setTypeId(1);
        competitionTypes.add(championship);

        CompetitionType cup = new CompetitionType();
        cup.setId(2);
        cup.setTypeName("Cup");
        cup.setTypeId(2);
        competitionTypes.add(cup);

        return competitionTypes;
    }

    // updateTeam / isDerbyMatch extracted to TeamPostMatchService (Stage 1 of
    // matchday-orchestration extraction). Thin wrappers below keep simulateRound
    // + processMatchHumanTeam call sites unchanged.

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference) {
        teamPostMatchService.updateTeam(teamId, competitionId, scoreHome, scoreAway, teamPowerDifference);
    }

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference, long opponentTeamId) {
        teamPostMatchService.updateTeam(teamId, competitionId, scoreHome, scoreAway, teamPowerDifference, opponentTeamId);
    }

    private boolean isDerbyMatch(long teamId, long opponentTeamId, long competitionId) {
        return teamPostMatchService.isDerbyMatch(teamId, opponentTeamId, competitionId);
    }

    private void updateTeamSimple(long teamId, long competitionId, int scoreHome, int scoreAway) {
        teamPostMatchService.updateTeamSimple(teamId, competitionId, scoreHome, scoreAway);
    }

    // updatePlayersMorale / sendInboxNotification / applyMoraleRecovery /
    // getManagerMoraleMultiplier / calculateMoraleChangeForTeamDifference /
    // consumePredeterminedScore / calculateScores / poissonGoals — extracted
    // to TeamPostMatchService. Thin wrappers below preserve internal call
    // sites (processMatchHumanTeam, simulateRound, play() end-of-season).

    private void updatePlayersMorale(long teamId, double baseMoraleChange, String matchResult) {
        teamPostMatchService.updatePlayersMorale(teamId, baseMoraleChange, matchResult);
    }

    private void sendInboxNotification(long teamId, int season, int roundNumber, String title, String content, String category) {
        teamPostMatchService.sendInboxNotification(teamId, season, roundNumber, title, content, category);
    }

    private void applyMoraleRecovery() {
        teamPostMatchService.applyMoraleRecovery();
    }

    private double getManagerMoraleMultiplier(long teamId) {
        return teamPostMatchService.getManagerMoraleMultiplier(teamId);
    }

    private double calculateMoraleChangeForTeamDifference(String result, double teamPowerDifference) {
        return teamPostMatchService.calculateMoraleChangeForTeamDifference(result, teamPowerDifference);
    }

    private int[] consumePredeterminedScore(long competitionId, int roundId, long team1Id, long team2Id) {
        return teamPostMatchService.consumePredeterminedScore(competitionId, roundId, team1Id, team2Id);
    }

    private List<Integer> calculateScores(double power1, double power2) {
        return teamPostMatchService.calculateScores(power1, power2);
    }

    private int poissonGoals(Random random, double expectedGoals) {
        return teamPostMatchService.poissonGoals(random, expectedGoals);
    }


    private List<Long> getAllTeams() {

        return teamRepository.findAll()
                .stream()
                .map(Team::getId)
                .collect(Collectors.toList());

    }

    public HashMap<String, Integer> getMinimumPositionNeeded() {

        HashMap<String, Integer> minimumPositionNeeded = new HashMap<>();
        minimumPositionNeeded.put("GK", 1);
        minimumPositionNeeded.put("DL", 1);
        minimumPositionNeeded.put("DC", 2);
        minimumPositionNeeded.put("DR", 1);
        minimumPositionNeeded.put("MC", 2);
        minimumPositionNeeded.put("ML", 1);
        minimumPositionNeeded.put("MR", 1);
        minimumPositionNeeded.put("ST", 2);

        return minimumPositionNeeded;
    }

    public HashMap<String, Integer> getMaximumPositionAllowed() {

        HashMap<String, Integer> maximumPositionAllowed = new HashMap<>();
        maximumPositionAllowed.put("GK", 3);
        maximumPositionAllowed.put("DL", 3);
        maximumPositionAllowed.put("DC", 5);
        maximumPositionAllowed.put("DR", 3);
        maximumPositionAllowed.put("MC", 5);
        maximumPositionAllowed.put("ML", 3);
        maximumPositionAllowed.put("MR", 3);
        maximumPositionAllowed.put("ST", 5);

        return maximumPositionAllowed;
    }


    /** Delegates to {@link TransferValueCalculator#calculate} — preserved as
     *  an instance method so existing callers (AdminController, …) need no
     *  change. New code should call the calculator directly. */
    public long calculateTransferValue(long age, String position, double rating) {
        return TransferValueCalculator.calculate(age, position, rating);
    }

    /** Best-eleven rating with morale + fitness + role suitability multipliers.
     *  Public so {@link com.footballmanagergamesimulator.service.MatchSimulationOrchestrator}
     *  can call it back through its lazy controller reference. */
    public double getBestElevenRatingByTactic(long teamId, String tactic) {

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);

        if (personalizedTacticOpt.isPresent()) {
            PersonalizedTactic personalized = personalizedTacticOpt.get();
            double rating = 0;

            try {
                // 1. Convertim JSON-ul salvat în List<FormationData>
                List<FormationData> formationDataList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                // 2. Iterăm prin listă
                for (FormationData data : formationDataList) {

                    // Ignorăm rezervele (indecșii >= 30 sunt pe bancă)
                    if (data.getPositionIndex() >= 30) continue;

                    // Găsim jucătorul
                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    // Skip injured players from power calculation
                    if (isPlayerInjured(player.getId())) continue;

                    // 3. Aflăm ce poziție reprezintă indexul de pe grilă (ex: 27 -> "GK")
                    String tacticPosition = tacticService.getPositionFromIndex(data.getPositionIndex());

                    // 4. Calculăm rating-ul (cu morale + fitness + role suitability)
                    // Morale: neutral at 70 (1.0), swing reduced 10x — squad value should
                    // dominate the result, morale only nudges it. Range now ~0.97..1.012.
                    double moraleMultiplier = 1.0 + (player.getMorale() - 70) * 0.0004;
                    double fitnessMultiplier = Math.max(0.7, player.getFitness() / 100D);

                    double playerRating;
                    // Compare using base positions so AMC≡MC, AML≡ML, AMR≡MR
                    String basePlayerPos = TacticService.getBasePosition(player.getPosition());
                    String baseTacticPos = TacticService.getBasePosition(tacticPosition);
                    if (basePlayerPos.equals(baseTacticPos)) {
                        // Use role-based effective rating if role is assigned
                        if (data.getRole() != null && !data.getRole().isEmpty()) {
                            PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId()).orElse(null);
                            if (skills != null) {
                                playerRating = playerRoleService.computeEffectiveRating(skills, data.getRole());
                            } else {
                                playerRating = player.getRating();
                            }
                        } else {
                            playerRating = player.getRating();
                        }
                        // Apply individual instruction multiplier (use base position for lookup)
                        double instructionMultiplier = PlayerInstructionService.computeInstructionMultiplier(
                                data.getInstructions(), baseTacticPos, "general");
                        rating += playerRating * moraleMultiplier * fitnessMultiplier * instructionMultiplier;
                    } else {
                        rating += player.getRating() / 2 * moraleMultiplier * fitnessMultiplier;
                    }
                }

                // Manager morale influence: low manager morale penalizes team
                rating *= getManagerMoraleMultiplier(teamId);

                return rating;

            } catch (Exception e) {
                e.printStackTrace();
                // Dacă parsarea eșuează, continuăm execuția spre fallback-ul de mai jos
            }
        }

        // --- FALLBACK (Dacă nu are tactică salvată sau a crăpat parsarea) ---
        // Folosește algoritmul automat pentru a determina cel mai bun 11
        List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);

        double fallbackRating = playerViews
                .stream()
                .mapToDouble(playerView -> {
                    // Morale nudge (1/10 of previous), fitness unchanged
                    double moraleMul = 1.0 + (playerView.getMorale() - 70) * 0.0004;
                    double fitMul = Math.max(0.7, playerView.getFitness() / 100D);
                    return playerView.getRating() * moraleMul * fitMul;
                })
                .sum();

        // Manager morale influence
        return fallbackRating * getManagerMoraleMultiplier(teamId);
    }

    public void getScorersForTeam(long teamId, long opponentTeamId, int teamScore, int opponentScore, String tactic, long competitionId) {

        Long competitionTypeIdObj = competitionRepository.findTypeIdById(competitionId);
        long competitionTypeId = competitionTypeIdObj != null ? competitionTypeIdObj : 0L;
        String teamName = teamRepository.findNameById(teamId);
        String opponentName = teamRepository.findNameById(opponentTeamId);
        String competitionName = competitionRepository.findNameById(competitionId);

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        List<Scorer> possibleScorers = new ArrayList<>();
        List<Scorer> substitutions = new ArrayList<>();

        boolean loadedSuccessfully = false;

        // 1. ÎNCERCĂM SĂ ÎNCĂRCĂM TACTICA PERSONALIZATĂ (JSON)
        if (personalizedTacticOpt.isPresent()) {
            try {
                PersonalizedTactic personalized = personalizedTacticOpt.get();

                // Citim JSON-ul salvat
                List<FormationData> formationList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                for (FormationData data : formationList) {
                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    // Skip injured players
                    if (isPlayerInjured(player.getId())) continue;

                    Scorer scorer = new Scorer();
                    scorer.setPlayerId(player.getId());
                    scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                    scorer.setTeamId(teamId);
                    scorer.setOpponentTeamId(opponentTeamId);
                    scorer.setPosition(player.getPosition());
                    scorer.setTeamScore(teamScore);
                    scorer.setOpponentScore(opponentScore);
                    scorer.setCompetitionId(competitionId);
                    scorer.setCompetitionTypeId((int) competitionTypeId);
                    scorer.setTeamName(teamName);
                    scorer.setOpponentTeamName(opponentName);
                    scorer.setCompetitionName(competitionName);
                    // Seed the player's base rating so weight = posMul × rating²/70 works
                    // when we sample scorers below. assignMatchRatings() will overwrite
                    // this with the actual match performance rating afterwards.
                    scorer.setRating(player.getRating());

                    // 🔹 LOGICA NOUĂ: Index >= 30 înseamnă Rezervă
                    if (data.getPositionIndex() >= 30) {
                        scorer.setSubstitute(true);
                        substitutions.add(scorer);
                    } else {
                        scorer.setSubstitute(false);
                        possibleScorers.add(scorer);
                    }
                }

                if (!possibleScorers.isEmpty()) {
                    loadedSuccessfully = true;
                }

            } catch (Exception e) {
                System.err.println("Error parsing tactic JSON for team " + teamId + ": " + e.getMessage());
                // Nu dăm throw, ci lăsăm loadedSuccessfully = false ca să intre pe fallback
            }
        }

        // 2. FALLBACK: LOGICA STANDARD (Dacă nu are tactică sau a crăpat JSON-ul)
        if (!loadedSuccessfully) {
            // Aici e codul tău vechi din blocul "else", neatins
            List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);
            playerViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                // Seed base rating so the scorer-distribution weighting below sees a
                // meaningful value (otherwise rating² = 0 and the position multiplier
                // gets clamped to the 0.1 floor — strikers/defenders end up equally likely).
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(false);
                return scorer;
            }).forEach(possibleScorers::add);

            List<PlayerView> substitutionViews = tacticController.getSubstitutions(String.valueOf(teamId), tactic);
            substitutionViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(true);
                return scorer;
            }).forEach(substitutions::add);
        }

        // 3. SIMULARE SCHIMBĂRI ȘI GOLURI (Rămâne la fel)
        Random random = new Random();
        int substitutesDone = random.nextInt(0, Math.min(6, substitutions.size() + 1)); // fix bounds check
        if (!substitutions.isEmpty()) {
            Collections.shuffle(substitutions);
            // Asigură-te că nu ceri mai mulți decât ai
            for (int i = 0; i < Math.min(substitutesDone, substitutions.size()); i++) {
                possibleScorers.add(substitutions.get(i));
            }
        }

        List<Pair<Scorer, Double>> weightedPlayers = new ArrayList<>();
        for (Scorer scorer: possibleScorers) {
            if ("GK".equals(scorer.getPosition())) continue;
            double weight = competitionService.getDifferentValueForScoringBasedOnPosition(scorer);
            if (weight <= 0) weight = 0.1; // Ensure positive weight
            weightedPlayers.add(new Pair<>(scorer, weight));
        }

        if (!weightedPlayers.isEmpty()) {
            for (int i = 0; i < teamScore; i++) {
                try {
                    EnumeratedDistribution<Scorer> distribution = new EnumeratedDistribution<>(weightedPlayers);
                    Scorer selected = distribution.sample();
                    selected.setGoals(selected.getGoals() + 1);

                    // Assists: ~75% of goals have an assist; assister is weighted by
                    // creative-position bias (wide mids and AMs over defenders/strikers)
                    // and must be a different player from the scorer.
                    if (random.nextDouble() < 0.75) {
                        List<Pair<Scorer, Double>> assistCandidates = new ArrayList<>();
                        for (Scorer s : possibleScorers) {
                            if (s.getPlayerId() == selected.getPlayerId()) continue;
                            if ("GK".equals(s.getPosition())) continue;
                            double w = getAssistWeight(s);
                            if (w > 0) assistCandidates.add(new Pair<>(s, w));
                        }
                        if (!assistCandidates.isEmpty()) {
                            EnumeratedDistribution<Scorer> assistDist = new EnumeratedDistribution<>(assistCandidates);
                            Scorer assister = assistDist.sample();
                            assister.setAssists(assister.getAssists() + 1);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Distribution error (negative weights?): " + e.getMessage());
                }
            }
        }

        // Assign match performance ratings (1-10 scale) and determine Man of the Match
        matchSimulationService.assignMatchRatings(possibleScorers, teamScore, opponentScore);

        for (Scorer scorer: possibleScorers) {

            scorerRepository.save(scorer);

            Optional<Human> possiblePlayer = humanRepository.findById(scorer.getPlayerId());
            if (possiblePlayer.isPresent()) {
                Human player = possiblePlayer.get();

                ScorerLeaderboardEntry scorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(player.getId()).orElseGet(() -> {
                    ScorerLeaderboardEntry newEntry = new ScorerLeaderboardEntry();
                    newEntry.setPlayerId(player.getId());
                    newEntry.setName(player.getName());
                    newEntry.setPosition(player.getPosition());
                    newEntry.setTeamId(player.getTeamId() != null ? player.getTeamId() : 0);
                    newEntry.setTeamName(player.getTeamId() != null ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");
                    newEntry.setActive(true);
                    newEntry.setCurrentRating(player.getRating());
                    newEntry.setBestEverRating(player.getRating());
                    newEntry.setSeasonOfBestEverRating(Integer.parseInt(getCurrentSeason()));
                    newEntry.setAge(player.getAge());
                    return scorerLeaderboardRepository.save(newEntry);
                });
                scorerLeaderboardEntry.setAge(player.getAge());
                scorerLeaderboardEntry.setName(player.getName());

                scorerLeaderboardEntry.setName(player.getName());
                if (player.getTeamId() != null) {
                    scorerLeaderboardEntry.setTeamName(teamRepository.findNameById(player.getTeamId()));
                } else {
                    scorerLeaderboardEntry.setTeamName("Free Agent");
                }
                if (player.isRetired()) {
                    scorerLeaderboardEntry.setTeamName("Retired");
                }
                scorerLeaderboardEntry.setPosition(player.getPosition());
                scorerLeaderboardEntry.setActive(!player.isRetired());
                if (player.getRating() > scorerLeaderboardEntry.getBestEverRating()) {
                    scorerLeaderboardEntry.setBestEverRating(player.getRating());
                    scorerLeaderboardEntry.setSeasonOfBestEverRating(Integer.parseInt(getCurrentSeason()));
                }
                scorerLeaderboardEntry.setAge(player.getAge());
                scorerLeaderboardEntry.setCurrentRating(player.getRating());

                scorerLeaderboardEntry.setMatches(scorerLeaderboardEntry.getMatches() + 1);
                scorerLeaderboardEntry.setGoals(scorerLeaderboardEntry.getGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGoals(scorerLeaderboardEntry.getCurrentSeasonGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGames(scorerLeaderboardEntry.getCurrentSeasonGames() + 1);

                if (cachedLeagueCompIds == null) {
                    cachedLeagueCompIds = getCompetitionIdsByCompetitionType(1);
                    cachedCupCompIds = getCompetitionIdsByCompetitionType(2);
                    cachedSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
                }

                if (cachedLeagueCompIds.contains(competitionId)) {

                    scorerLeaderboardEntry.setLeagueGoals(scorerLeaderboardEntry.getLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setLeagueMatches(scorerLeaderboardEntry.getLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGames(scorerLeaderboardEntry.getCurrentSeasonLeagueGames() + 1);

                } else if (cachedCupCompIds.contains(competitionId)) {

                    scorerLeaderboardEntry.setCupGoals(scorerLeaderboardEntry.getCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCupMatches(scorerLeaderboardEntry.getCupMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonCupGoals(scorerLeaderboardEntry.getCurrentSeasonCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonCupGames(scorerLeaderboardEntry.getCurrentSeasonCupGames() + 1);

                } else if (cachedSecondLeagueCompIds.contains(competitionId)){

                    scorerLeaderboardEntry.setSecondLeagueGoals(scorerLeaderboardEntry.getSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setSecondLeagueMatches(scorerLeaderboardEntry.getSecondLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGames() + 1);

                }
                scorerLeaderboardRepository.save(scorerLeaderboardEntry);
            }
        }

    }

    public void generateMatchEvents(long competitionId, int seasonNumber, int roundNumber,
                                      long teamId1, long teamId2, int teamScore1, int teamScore2,
                                      String tactic1, String tactic2) {

        Random random = new Random();
        List<MatchEvent> events = new ArrayList<>();

        boolean isHumanMatch = userContext.isHumanTeam(teamId1) || userContext.isHumanTeam(teamId2);

        String[] goalDescriptions = {"Tap in", "Header", "Long range shot", "Free kick", "Penalty", "Solo run", "Volley"};

        // Load set piece takers for both teams
        Optional<PersonalizedTactic> tactic1Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
        Optional<PersonalizedTactic> tactic2Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

        // Generate random sorted minutes for all goals
        int totalGoals = teamScore1 + teamScore2;
        List<Integer> goalMinutes = new ArrayList<>();
        for (int i = 0; i < totalGoals; i++) {
            goalMinutes.add(random.nextInt(1, 91));
        }
        Collections.sort(goalMinutes);

        // Assign goals to teams
        List<Integer> team1GoalMinutes = new ArrayList<>();
        List<Integer> team2GoalMinutes = new ArrayList<>();
        List<Integer> shuffledIndices = new ArrayList<>();
        for (int i = 0; i < totalGoals; i++) shuffledIndices.add(i);
        Collections.shuffle(shuffledIndices);

        for (int i = 0; i < totalGoals; i++) {
            if (i < teamScore1) {
                team1GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
            } else {
                team2GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
            }
        }
        Collections.sort(team1GoalMinutes);
        Collections.sort(team2GoalMinutes);

        // Get players for each team
        List<Human> team1Players = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.PLAYER_TYPE);
        List<Human> team2Players = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.PLAYER_TYPE);

        List<Human> team1Outfield = team1Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());
        List<Human> team2Outfield = team2Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());

        if (team1Outfield.isEmpty()) team1Outfield = new ArrayList<>(team1Players);
        if (team2Outfield.isEmpty()) team2Outfield = new ArrayList<>(team2Players);

        // Generate goal events for team 1
        for (int minute : team1GoalMinutes) {
            String description = goalDescriptions[random.nextInt(goalDescriptions.length)];
            Human scorer = resolveGoalScorer(description, team1Outfield, tactic1Opt.orElse(null), random);
            MatchEvent goalEvent = new MatchEvent();
            goalEvent.setCompetitionId(competitionId);
            goalEvent.setSeasonNumber(seasonNumber);
            goalEvent.setRoundNumber(roundNumber);
            goalEvent.setTeamId1(teamId1);
            goalEvent.setTeamId2(teamId2);
            goalEvent.setMinute(minute);
            goalEvent.setEventType("goal");
            goalEvent.setPlayerId(scorer.getId());
            goalEvent.setPlayerName(scorer.getName());
            goalEvent.setTeamId(teamId1);
            goalEvent.setDetails(description);
            events.add(goalEvent);

            // 70% chance of assist (no assist for penalties)
            if (!"Penalty".equals(description) && random.nextDouble() < 0.7 && team1Outfield.size() > 1) {
                List<Human> possibleAssisters = team1Outfield.stream()
                        .filter(p -> p.getId() != scorer.getId()).collect(Collectors.toList());
                if (!possibleAssisters.isEmpty()) {
                    Human assister = possibleAssisters.get(random.nextInt(possibleAssisters.size()));
                    MatchEvent assistEvent = new MatchEvent();
                    assistEvent.setCompetitionId(competitionId);
                    assistEvent.setSeasonNumber(seasonNumber);
                    assistEvent.setRoundNumber(roundNumber);
                    assistEvent.setTeamId1(teamId1);
                    assistEvent.setTeamId2(teamId2);
                    assistEvent.setMinute(minute);
                    assistEvent.setEventType("assist");
                    assistEvent.setPlayerId(assister.getId());
                    assistEvent.setPlayerName(assister.getName());
                    assistEvent.setTeamId(teamId1);
                    assistEvent.setDetails("Assist");
                    events.add(assistEvent);
                }
            }
        }

        // Generate goal events for team 2
        for (int minute : team2GoalMinutes) {
            String description = goalDescriptions[random.nextInt(goalDescriptions.length)];
            Human scorer = resolveGoalScorer(description, team2Outfield, tactic2Opt.orElse(null), random);
            MatchEvent goalEvent = new MatchEvent();
            goalEvent.setCompetitionId(competitionId);
            goalEvent.setSeasonNumber(seasonNumber);
            goalEvent.setRoundNumber(roundNumber);
            goalEvent.setTeamId1(teamId1);
            goalEvent.setTeamId2(teamId2);
            goalEvent.setMinute(minute);
            goalEvent.setEventType("goal");
            goalEvent.setPlayerId(scorer.getId());
            goalEvent.setPlayerName(scorer.getName());
            goalEvent.setTeamId(teamId2);
            goalEvent.setDetails(description);
            events.add(goalEvent);

            if (!"Penalty".equals(description) && random.nextDouble() < 0.7 && team2Outfield.size() > 1) {
                List<Human> possibleAssisters = team2Outfield.stream()
                        .filter(p -> p.getId() != scorer.getId()).collect(Collectors.toList());
                if (!possibleAssisters.isEmpty()) {
                    Human assister = possibleAssisters.get(random.nextInt(possibleAssisters.size()));
                    MatchEvent assistEvent = new MatchEvent();
                    assistEvent.setCompetitionId(competitionId);
                    assistEvent.setSeasonNumber(seasonNumber);
                    assistEvent.setRoundNumber(roundNumber);
                    assistEvent.setTeamId1(teamId1);
                    assistEvent.setTeamId2(teamId2);
                    assistEvent.setMinute(minute);
                    assistEvent.setEventType("assist");
                    assistEvent.setPlayerId(assister.getId());
                    assistEvent.setPlayerName(assister.getName());
                    assistEvent.setTeamId(teamId2);
                    assistEvent.setDetails("Assist");
                    events.add(assistEvent);
                }
            }
        }

        // Only generate detailed events (cards, subs) for human team matches
        if (isHumanMatch) {

            // Yellow cards: 0-4 per team
            for (long teamId : new long[]{teamId1, teamId2}) {
                List<Human> teamPlayers = (teamId == teamId1) ? team1Players : team2Players;
                if (teamPlayers.isEmpty()) continue;
                int yellowCards = random.nextInt(5);
                List<Human> shuffledPlayers = new ArrayList<>(teamPlayers);
                Collections.shuffle(shuffledPlayers);
                for (int i = 0; i < Math.min(yellowCards, shuffledPlayers.size()); i++) {
                    MatchEvent cardEvent = new MatchEvent();
                    cardEvent.setCompetitionId(competitionId);
                    cardEvent.setSeasonNumber(seasonNumber);
                    cardEvent.setRoundNumber(roundNumber);
                    cardEvent.setTeamId1(teamId1);
                    cardEvent.setTeamId2(teamId2);
                    cardEvent.setMinute(random.nextInt(1, 91));
                    cardEvent.setEventType("yellow_card");
                    cardEvent.setPlayerId(shuffledPlayers.get(i).getId());
                    cardEvent.setPlayerName(shuffledPlayers.get(i).getName());
                    cardEvent.setTeamId(teamId);
                    cardEvent.setDetails("Yellow card");
                    events.add(cardEvent);
                }
            }

            // 5% chance of red card per match
            if (random.nextDouble() < 0.05) {
                long redCardTeamId = random.nextBoolean() ? teamId1 : teamId2;
                List<Human> redCardPlayers = (redCardTeamId == teamId1) ? team1Players : team2Players;
                if (!redCardPlayers.isEmpty()) {
                    Human redCardPlayer = redCardPlayers.get(random.nextInt(redCardPlayers.size()));
                    MatchEvent redEvent = new MatchEvent();
                    redEvent.setCompetitionId(competitionId);
                    redEvent.setSeasonNumber(seasonNumber);
                    redEvent.setRoundNumber(roundNumber);
                    redEvent.setTeamId1(teamId1);
                    redEvent.setTeamId2(teamId2);
                    redEvent.setMinute(random.nextInt(20, 91));
                    redEvent.setEventType("red_card");
                    redEvent.setPlayerId(redCardPlayer.getId());
                    redEvent.setPlayerName(redCardPlayer.getName());
                    redEvent.setTeamId(redCardTeamId);
                    redEvent.setDetails("Red card");
                    events.add(redEvent);
                }
            }

            // 3 substitutions per team (minutes 46-85)
            for (long teamId : new long[]{teamId1, teamId2}) {
                List<Human> teamPlayers = (teamId == teamId1) ? team1Players : team2Players;
                if (teamPlayers.size() < 4) continue;
                List<Human> shuffledPlayers = new ArrayList<>(teamPlayers);
                Collections.shuffle(shuffledPlayers);
                List<Integer> subMinutes = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    subMinutes.add(random.nextInt(46, 86));
                }
                Collections.sort(subMinutes);
                for (int i = 0; i < 3; i++) {
                    MatchEvent subEvent = new MatchEvent();
                    subEvent.setCompetitionId(competitionId);
                    subEvent.setSeasonNumber(seasonNumber);
                    subEvent.setRoundNumber(roundNumber);
                    subEvent.setTeamId1(teamId1);
                    subEvent.setTeamId2(teamId2);
                    subEvent.setMinute(subMinutes.get(i));
                    subEvent.setEventType("substitution");
                    subEvent.setPlayerId(shuffledPlayers.get(i).getId());
                    subEvent.setPlayerName(shuffledPlayers.get(i).getName());
                    subEvent.setTeamId(teamId);
                    subEvent.setDetails("Substitution");
                    events.add(subEvent);
                }
            }
        }

        matchEventRepository.saveAll(events);
    }

    /**
     * Resolve the goal scorer based on goal type and set piece taker assignments.
     * - "Penalty" → use designated penalty taker (if set)
     * - "Free kick" → use designated free kick taker (if set)
     * - Otherwise → random outfield player
     */
    private Human resolveGoalScorer(String goalDescription, List<Human> outfieldPlayers,
                                     PersonalizedTactic tactic, Random random) {
        if (tactic != null && outfieldPlayers != null && !outfieldPlayers.isEmpty()) {
            Long takerId = null;
            if ("Penalty".equals(goalDescription) && tactic.getPenaltyTakerId() != null) {
                takerId = tactic.getPenaltyTakerId();
            } else if ("Free kick".equals(goalDescription) && tactic.getFreeKickTakerId() != null) {
                takerId = tactic.getFreeKickTakerId();
            }
            if (takerId != null) {
                final long id = takerId;
                Human taker = outfieldPlayers.stream()
                        .filter(p -> p.getId() == id)
                        .findFirst().orElse(null);
                if (taker != null) return taker;
                // Taker not in outfield list (may be injured/subbed) — fallback to random
            }
        }
        return outfieldPlayers.get(random.nextInt(outfieldPlayers.size()));
    }

    @GetMapping("/getCompetitionInfo/{id}")
    public Map<String, Object> getCompetitionInfo(@PathVariable Long id) {

        Competition comp = competitionRepository.findById(id).orElse(null);
        Map<String, Object> info = new HashMap<>();
        if (comp != null) {
            info.put("typeId", comp.getTypeId());
            info.put("name", comp.getName());

            // For leagues (typeId 1 or 3), include European qualification zones
            if (comp.getTypeId() == 1 || comp.getTypeId() == 3) {
                Map<String, Object> zones = getQualificationZones(comp);
                info.put("locSpots", zones.get("locSpots"));
                info.put("starsCupSpots", zones.get("starsCupSpots"));
                info.put("relegationFrom", zones.get("relegationFrom"));
            }
        }

        return info;
    }

    /**
     * Returns the number of LoC and Stars Cup qualification spots for a league,
     * based on the league's coefficient ranking.
     */
    private Map<String, Object> getQualificationZones(Competition comp) {
        Map<String, Object> zones = new HashMap<>();
        int locSpots = 0;
        int starsCupStart = 0;
        int starsCupEnd = 0;

        // Only first division leagues (typeId=1) qualify for Europe
        if (comp.getTypeId() == 1) {
            List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();
            int rank = sortedLeagueIds.indexOf(comp.getId()) + 1; // 1-based rank

            if (rank >= 1 && rank <= 7) {
                // LoC: direct + qualifying + preliminary (non-increasing: 4,4,3,3,3,2,2)
                int[] directSpotsDisplay =      {3, 3, 2, 2, 1, 1, 0};
                int[] qualifyingSpotsDisplay =  {1, 1, 1, 1, 2, 1, 0};
                int[] preliminarySpotsDisplay = {0, 0, 0, 0, 0, 0, 2};
                locSpots = directSpotsDisplay[rank - 1] + qualifyingSpotsDisplay[rank - 1] + preliminarySpotsDisplay[rank - 1];

                // Stars Cup positions (0-based): non-increasing (2,2,2,2,1,1,1)
                int[][] starsCupPositions = {
                    {4, 5},     // Rank 1: 5th-6th (1 league + 1 cup)
                    {4, 5},     // Rank 2: 5th-6th
                    {3, 4},     // Rank 3: 4th-5th (1 league + 1 cup)
                    {3, 4},     // Rank 4: 4th-5th
                    {3},        // Rank 5: cup spot only (shown at 4th)
                    {3},        // Rank 6: cup spot only
                    {2}         // Rank 7: cup spot only
                };
                int[] scPos = starsCupPositions[rank - 1];
                starsCupStart = scPos[0] + 1; // convert to 1-based position
                starsCupEnd = scPos[scPos.length - 1] + 1;
            }
        }

        zones.put("locSpots", locSpots);
        // Stars Cup range as total: from position (locSpots+1) to starsCupEnd
        zones.put("starsCupSpots", starsCupEnd > 0 ? starsCupEnd - starsCupStart + 1 : 0);
        // locSpots already tells the frontend where Stars Cup starts (locSpots+1)
        zones.put("relegationFrom", 18); // standard relegation zone
        return zones;
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getTeamNameByTeamId(@PathVariable(name = "competitionId") long competitionId) {

        return competitionRepository.findNameById(competitionId);
    }

    private List<Human> getBestEleven(long teamId) {

        List<Human> players = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .sorted(Comparator.comparing(Human::getRating))
                .toList();

        List<String> positions = getPositionsForBestEleven(teamId);
        List<Human> bestEleven = new ArrayList<>();

        for (String position : positions) {
            for (Human player : players) {
                if (player.getPosition().equals(position)) {
                    bestEleven.add(player);
                    break;
                }
            }
        }

        return bestEleven;
    }

    private List<String> getPositionsForBestEleven(long teamId) {

        return List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST");
    }

    private boolean canBeTransfered(PlayerTransferView playerTransferView, BuyPlanTransferView clubPlan, TransferPlayer desiredPlayer) {

        if (playerTransferView.getAge() > clubPlan.getMaxAge())
            return false; // club does not want to buy player, too old
        if (playerTransferView.getDesiredReputation() - 1000 > clubPlan.getTeamReputation())
            return false; // club can't buy player, reputation too low
        if (!playerTransferView.getPosition().equals(desiredPlayer.getPosition()))
            return false; // not desired position
        if (playerTransferView.getRating() < desiredPlayer.getMinRating() - 10)
            return false; // player rating too low
        if (playerTransferView.getTeamId() == clubPlan.getTeamId())
            return false; // club already owns player

        return true;
    }

    private void generateAiOffersForHumanPlayers(Team aiTeam, BuyPlanTransferView buyPlanTransferView) {
        if (buyPlanTransferView == null) return;

        int season = (int) round.getSeason();

        for (long humanTeamId : userContext.getAllHumanTeamIds()) {
            List<Human> humanTeamPlayers = humanRepository.findAllByTeamId(humanTeamId);
            Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
            if (humanTeam == null) continue;

            for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {
                for (Human player : humanTeamPlayers) {
                    if (player.isRetired()) continue;
                    if (player.getPosition() == null || player.getTypeId() != TypeNames.PLAYER_TYPE) continue;
                    if (!player.getPosition().equals(clubPlan.getPosition())) continue;
                    if (player.getAge() > buyPlanTransferView.getMaxAge()) continue;
                    if (player.getRating() < clubPlan.getMinRating() - 10) continue;

                    // Check if there's already a pending offer for this player this season
                    List<TransferOffer> existingOffers = transferOfferRepository
                            .findAllByPlayerIdAndSeasonNumberAndStatusNot(player.getId(), season, "rejected");
                    if (!existingOffers.isEmpty()) continue;

                    long transferValue = calculateTransferValue(player.getAge(), player.getPosition(), player.getRating());
                    if (transferValue > aiTeam.getTransferBudget()) continue;

                    TransferOffer offer = new TransferOffer();
                    offer.setPlayerId(player.getId());
                    offer.setPlayerName(player.getName());
                    offer.setFromTeamId(aiTeam.getId());
                    offer.setFromTeamName(aiTeam.getName());
                    offer.setToTeamId(humanTeamId);
                    offer.setToTeamName(humanTeam.getName());
                    offer.setOfferAmount(transferValue);
                    offer.setAskingPrice(transferValue);
                    offer.setStatus("pending");
                    offer.setSeasonNumber(season);
                    offer.setDirection("incoming");
                    offer.setCreatedAt(System.currentTimeMillis());
                    transferOfferRepository.save(offer);

                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(humanTeamId);
                    inbox.setSeasonNumber(season);
                    inbox.setRoundNumber((int) round.getRound());
                    inbox.setTitle("Transfer Offer Received");
                    inbox.setContent(aiTeam.getName() + " have made an offer of " + transferValue +
                            " for your player " + player.getName() + " (" + player.getPosition() +
                            ", Rating: " + player.getRating() + "). Review the offer in the transfer section.");
                    inbox.setCategory("transfer");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);

                    break; // Only one offer per position per AI team
                }
            }
        }
    }

    private void refreshTeamBudgets(int season) {
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();

        // Track teams already processed (to avoid double-counting)
        Set<Long> processedTeamIds = new HashSet<>();

        // Determine league tiers dynamically from coefficient ranking
        List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();
        Map<Long, Integer> leagueTierMap = new HashMap<>(); // leagueId -> tier (1=top, 2=mid, 3=lower)
        for (int i = 0; i < sortedLeagueIds.size(); i++) {
            int tier;
            if (i < 2) tier = 1;       // Top 2 leagues
            else if (i < 4) tier = 2;  // Next 2 leagues
            else tier = 3;             // Rest
            leagueTierMap.put(sortedLeagueIds.get(i), tier);
        }

        // 1. League prize money + TV income (based on final position + coefficient-based tier)
        for (Competition comp : allComps) {
            if (comp.getTypeId() != 1 && comp.getTypeId() != 3) continue;

            // For second leagues, find their parent first league's tier via nationId
            int tier = 3; // default
            if (comp.getTypeId() == 3) {
                long nationId = comp.getNationId();
                for (Competition firstLeague : allComps) {
                    if (firstLeague.getTypeId() == 1 && firstLeague.getNationId() == nationId) {
                        tier = leagueTierMap.getOrDefault(firstLeague.getId(), 3);
                        break;
                    }
                }
            } else {
                tier = leagueTierMap.getOrDefault(comp.getId(), 3);
            }

            long leagueBase;
            if (comp.getTypeId() == 3) {
                leagueBase = (tier == 1) ? 5_000_000L : (tier == 2) ? 3_000_000L : 2_000_000L;
            } else {
                switch (tier) {
                    case 1: leagueBase = 50_000_000L; break;
                    case 2: leagueBase = 20_000_000L; break;
                    default: leagueBase = 8_000_000L; break;
                }
            }

            List<TeamCompetitionDetail> standings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == comp.getId())
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            int numTeams = standings.size();
            if (numTeams == 0) continue;

            int position = 1;
            for (TeamCompetitionDetail detail : standings) {
                double positionFactor = 1.0 - (0.8 * (position - 1.0) / Math.max(numTeams - 1, 1));
                long leagueIncome = (long) (leagueBase * positionFactor);

                Team team = teamRepository.findById(detail.getTeamId()).orElse(null);
                if (team == null) { position++; continue; }

                // European income: coefficient points * 2,000,000
                Optional<ClubCoefficient> cc = clubCoefficientRepository
                        .findByTeamIdAndSeasonNumber(detail.getTeamId(), season);
                long europeanIncome = cc.map(c -> (long) (c.getPoints() * 2_000_000L)).orElse(0L);

                // TV income distributed by league position
                int tvTier = (comp.getTypeId() == 3) ? tier + 1 : tier; // Second leagues get lower TV tier
                long tvIncome = financeService.calculateTvIncome(position, numTeams, Math.min(tvTier, 3));

                // Record financial transactions
                financeService.recordTransaction(team.getId(), season, 340, "PRIZE_MONEY",
                        comp.getName() + " prize money (Position " + position + ")", leagueIncome);
                if (tvIncome > 0) {
                    financeService.recordTransaction(team.getId(), season, 340, "TV_INCOME",
                            comp.getName() + " TV revenue (Position " + position + ")", tvIncome);
                }
                if (europeanIncome > 0) {
                    financeService.recordTransaction(team.getId(), season, 340, "PRIZE_MONEY",
                            "European competition revenue", europeanIncome);
                }

                // Decay unspent budget by 15%
                team = teamRepository.findById(detail.getTeamId()).orElse(null);
                if (team != null) {
                    team.setTransferBudget((long) (team.getTransferBudget() * 0.85));
                    teamRepository.save(team);
                }

                // Update board confidence based on league position
                financeService.updateBoardConfidence(detail.getTeamId(), position, numTeams);

                processedTeamIds.add(detail.getTeamId());
                position++;
            }
        }

        // 2. Cup prize money (for teams that participated)
        for (Competition comp : allComps) {
            if (comp.getTypeId() != 2) continue;

            int cupTier = 3;
            long nationId = comp.getNationId();
            for (Competition firstLeague : allComps) {
                if (firstLeague.getTypeId() == 1 && firstLeague.getNationId() == nationId) {
                    cupTier = leagueTierMap.getOrDefault(firstLeague.getId(), 3);
                    break;
                }
            }

            long cupWinnerPrize;
            switch (cupTier) {
                case 1: cupWinnerPrize = 10_000_000L; break;
                case 2: cupWinnerPrize = 4_000_000L; break;
                default: cupWinnerPrize = 1_500_000L; break;
            }

            List<CompetitionHistory> cupHistory = competitionHistoryRepository.findByCompetitionId(comp.getId()).stream()
                    .filter(h -> h.getSeasonNumber() == season && h.getLastPosition() == 1)
                    .toList();
            for (CompetitionHistory ch : cupHistory) {
                financeService.recordTransaction(ch.getTeamId(), season, 340, "PRIZE_MONEY",
                        comp.getName() + " Winner prize", cupWinnerPrize);
            }
        }

        // 3. Owner injections for high-reputation clubs
        List<Team> allTeams = teamRepository.findAll();
        for (Team team : allTeams) {
            financeService.processOwnerInjection(team.getId(), season);
        }

        // 4. European competition prizes are now awarded per-match via awardEuropeanMatchPrizeMoney()
    }

    private void qualifyTeamsForEuropeanCompetitions() {
        long nextSeason = Long.parseLong(getCurrentSeason()) + 1;
        System.out.println("=== qualifyTeamsForEuropeanCompetitions: qualifying for season " + nextSeason + " ===");

        // Find European competition IDs dynamically
        Optional<Competition> locOpt = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).findFirst();
        Optional<Competition> starsCupOpt = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 5).findFirst();
        if (locOpt.isEmpty() || starsCupOpt.isEmpty()) return;

        long locCompetitionId = locOpt.get().getId();
        long starsCupCompetitionId = starsCupOpt.get().getId();

        // Sort leagues by country coefficient (with reputation fallback for early seasons)
        List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();

        int numLeagues = sortedLeagueIds.size();
        if (numLeagues == 0) return;

        // Build standings for each league
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        Map<Long, List<TeamCompetitionDetail>> standingsByLeague = new HashMap<>();
        for (Long leagueId : sortedLeagueIds) {
            long lid = leagueId;
            List<TeamCompetitionDetail> standings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == lid)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        if (a.getGoalsFor() != b.getGoalsFor()) return b.getGoalsFor() - a.getGoalsFor();
                        // Fallback: sort by team reputation (for when no matches were played)
                        Team teamA = teamRepository.findById(a.getTeamId()).orElse(null);
                        Team teamB = teamRepository.findById(b.getTeamId()).orElse(null);
                        int repA = teamA != null ? teamA.getReputation() : 0;
                        int repB = teamB != null ? teamB.getReputation() : 0;
                        return Integer.compare(repB, repA);
                    })
                    .toList();
            standingsByLeague.put(leagueId, standings);
        }

        // LoC allocation — spots decrease with rank (non-increasing):
        // Rank 1: 4 (3 direct + 1 qualifying)
        // Rank 2: 4 (3 direct + 1 qualifying)
        // Rank 3: 3 (2 direct + 1 qualifying)
        // Rank 4: 3 (2 direct + 1 qualifying)
        // Rank 5: 3 (1 direct + 2 qualifying)
        // Rank 6: 2 (1 direct + 1 qualifying)
        // Rank 7: 2 (2 preliminary)
        // Flow: 2 preliminary → 1 winner joins qualifying (7+1=8) → 4 winners → groups (12+4=16)
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            if (standings == null || standings.isEmpty()) continue;
            int idx = rank - 1;

            // Direct to group stage (round 2)
            for (int i = 0; i < directSpots[idx]; i++) {
                if (standings.size() > i) {
                    saveLocQualifier(standings.get(i).getTeamId(), locCompetitionId, nextSeason, 2);
                }
            }

            // Qualifying round (round 1)
            int qStart = directSpots[idx];
            for (int i = 0; i < qualifyingSpots[idx]; i++) {
                int pos = qStart + i;
                if (standings.size() > pos) {
                    saveLocQualifier(standings.get(pos).getTeamId(), locCompetitionId, nextSeason, 1);
                }
            }

            // Preliminary qualifying (round 0)
            for (int i = 0; i < preliminarySpots[idx]; i++) {
                if (standings.size() > i) {
                    saveLocQualifier(standings.get(i).getTeamId(), locCompetitionId, nextSeason, 0);
                }
            }
        }

        // Collect all already-qualified team IDs (LoC + Stars Cup from league positions)
        Set<Long> alreadyQualified = new HashSet<>();
        List<CompetitionTeamInfo> nextSeasonEntries = competitionTeamInfoRepository.findAllBySeasonNumber(nextSeason);
        for (CompetitionTeamInfo cti : nextSeasonEntries) {
            if (cti.getCompetitionId() == locCompetitionId || cti.getCompetitionId() == starsCupCompetitionId) {
                alreadyQualified.add(cti.getTeamId());
            }
        }

        // Stars Cup allocation — spots decrease with rank (non-increasing):
        // 1 spot per nation is ALWAYS reserved for cup winner.
        // League-based spots:
        // Rank 1: 5th (1 spot) + 1 cup = 2
        // Rank 2: 5th (1 spot) + 1 cup = 2
        // Rank 3: 4th (1 spot) + 1 cup = 2
        // Rank 4: 4th (1 spot) + 1 cup = 2
        // Rank 5: (0 spots) + 1 cup = 1
        // Rank 6: (0 spots) + 1 cup = 1
        // Rank 7: (0 spots) + 1 cup = 1 (LoC losers add extra spots)
        int[][] starsCupPositions = {
            {4},        // Rank 1: 5th (0-based: 4)
            {4},        // Rank 2: 5th
            {3},        // Rank 3: 4th
            {3},        // Rank 4: 4th
            {},         // Rank 5: none (cup spot only)
            {},         // Rank 6: none (cup spot only)
            {}          // Rank 7: none (cup + LoC losers cover spots)
        };

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            int[] positions = starsCupPositions[rank - 1];
            for (int pos : positions) {
                if (standings.size() > pos) {
                    saveStarsCupQualifier(standings.get(pos).getTeamId(), starsCupCompetitionId, nextSeason);
                    alreadyQualified.add(standings.get(pos).getTeamId());
                }
            }
        }

        // Cup winner qualification for Stars Cup (1 reserved spot per nation)
        // The spot is always reserved. Rules:
        // 1. Cup winner already in LoC or Stars Cup → reserved spot goes to first non-qualified league team
        // 2. Cup winner NOT qualified → cup winner gets the reserved spot directly (no one removed)
        List<Competition> allComps = competitionRepository.findAll();

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            long leagueId = sortedLeagueIds.get(rank - 1);
            Competition league = competitionRepository.findById(leagueId).orElse(null);
            if (league == null) continue;
            long nationId = league.getNationId();

            // Find the cup for this nation
            Optional<Competition> cupOpt = allComps.stream()
                    .filter(c -> c.getTypeId() == 2 && c.getNationId() == nationId)
                    .findFirst();
            if (cupOpt.isEmpty()) continue;

            long cupCompId = cupOpt.get().getId();

            // Find cup winner from TeamCompetitionDetail (sorted by points, the winner has most wins)
            List<TeamCompetitionDetail> cupStandings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == cupCompId)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            if (cupStandings.isEmpty()) continue;

            long cupWinnerTeamId = cupStandings.get(0).getTeamId();
            System.out.println("=== Cup winner for nation " + nationId + ": team " + cupWinnerTeamId + " ===");

            // Check if cup winner is already qualified for European competition
            boolean alreadyInEurope = alreadyQualified.contains(cupWinnerTeamId);

            List<TeamCompetitionDetail> leagueStandings = standingsByLeague.get(leagueId);

            if (alreadyInEurope) {
                // Cup winner already qualified (LoC or Stars Cup from league)
                // Reserved cup spot goes to first non-qualified league team
                if (leagueStandings != null) {
                    for (TeamCompetitionDetail tcd : leagueStandings) {
                        if (!alreadyQualified.contains(tcd.getTeamId())) {
                            saveStarsCupQualifier(tcd.getTeamId(), starsCupCompetitionId, nextSeason);
                            alreadyQualified.add(tcd.getTeamId());
                            System.out.println("=== Cup winner already in Europe. Reserved spot to team " + tcd.getTeamId() + " ===");
                            break;
                        }
                    }
                }
            } else {
                // Cup winner NOT qualified — gets the reserved Stars Cup spot directly
                saveStarsCupQualifier(cupWinnerTeamId, starsCupCompetitionId, nextSeason);
                alreadyQualified.add(cupWinnerTeamId);
                System.out.println("=== Cup winner team " + cupWinnerTeamId + " qualified for Stars Cup (reserved cup spot) ===");
            }
        }
    }

    private void saveLocQualifier(long teamId, long locCompetitionId, long season, int round) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(locCompetitionId);
        cti.setSeasonNumber(season);
        cti.setRound(round);
        competitionTeamInfoRepository.save(cti);
        String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
        System.out.println("=== LoC qualifier: " + teamName + " (team " + teamId + ") → round " + round + " season " + season + " ===");
    }

    private void saveStarsCupQualifier(long teamId, long starsCupCompetitionId, long season) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(starsCupCompetitionId);
        cti.setSeasonNumber(season);
        cti.setRound(1);
        competitionTeamInfoRepository.save(cti);
    }

    private int getTeamCountForCompetition(long competitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        return (int) competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .map(CompetitionTeamInfo::getTeamId)
                .distinct()
                .count();
    }

    private int[] getCupSchedule(int numRounds) {
        int[] schedule = new int[numRounds];
        for (int i = 0; i < numRounds; i++) {
            schedule[i] = 5 + (i * 35 / Math.max(1, numRounds - 1));
        }
        if (numRounds == 1) schedule[0] = 5;
        return schedule;
    }

    // ====== COEFFICIENT SYSTEM ======

    public void awardCoefficientPoints(long competitionId, long roundId, long team1Id, long team2Id, int score1, int score2) {
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4);
        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5);

        if (!locIds.contains(competitionId) && !starsCupIds.contains(competitionId)) return;

        boolean isLoC = locIds.contains(competitionId);
        int season = Integer.parseInt(getCurrentSeason());

        double winPoints;
        double drawPoints;

        if (isLoC) {
            if (roundId <= 1) { winPoints = 1.0; drawPoints = 0; }           // Preliminary/QR (knockout)
            else if (roundId <= 7) { winPoints = 2.0; drawPoints = 1.0; }    // Group stage
            else if (roundId == 8) { winPoints = 3.0; drawPoints = 0; }      // QF
            else if (roundId == 9) { winPoints = 4.0; drawPoints = 0; }      // SF
            else { winPoints = 5.0; drawPoints = 0; }                        // Final
        } else {
            // Stars Cup
            if (roundId <= 6) { winPoints = 1.0; drawPoints = 0.5; }         // Group stage
            else if (roundId == 7) { winPoints = 1.0; drawPoints = 0; }      // Playoff
            else if (roundId == 8) { winPoints = 1.5; drawPoints = 0; }      // QF
            else if (roundId == 9) { winPoints = 2.0; drawPoints = 0; }      // SF
            else { winPoints = 2.5; drawPoints = 0; }                        // Final
        }

        if (score1 > score2) {
            addClubCoefficient(team1Id, season, winPoints);
        } else if (score2 > score1) {
            addClubCoefficient(team2Id, season, winPoints);
        } else {
            // Draw (only possible in LoC group stage)
            addClubCoefficient(team1Id, season, drawPoints);
            addClubCoefficient(team2Id, season, drawPoints);
        }

        // Award per-match European prize money
        awardEuropeanMatchPrizeMoney(competitionId, roundId, team1Id, team2Id, score1, score2, isLoC, season);
    }

    private void awardEuropeanMatchPrizeMoney(long competitionId, long roundId, long team1Id, long team2Id,
                                               int score1, int score2, boolean isLoC, int season) {
        Round currentRound = roundRepository.findById(1L).orElse(new Round());
        int roundNumber = (int) currentRound.getRound();
        String compName = isLoC ? "League of Champions" : "Stars Cup";

        if (isLoC) {
            // LoC Group Stage participation bonus (awarded once at round 2, first group match)
            if (roundId == 2) {
                awardPrizeMoney(team1Id, 20_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
                awardPrizeMoney(team2Id, 20_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
            }

            // LoC per-match results
            if (roundId >= 2 && roundId <= 7) {
                // Group stage: win = 5M, draw = 1.5M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (roundId == 8) {
                // QF qualification bonus (both teams qualified to play QF)
                awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
            } else if (roundId == 9) {
                // SF qualification bonus
                awardPrizeMoney(team1Id, 40_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 40_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
            } else if (roundId == 10) {
                // Final: winner gets 100M, runner-up gets 50M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 100_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team2Id, 50_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                } else {
                    awardPrizeMoney(team2Id, 100_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team1Id, 50_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                }
            }
        } else {
            // Stars Cup prizes (with group stage)
            if (roundId == 1) {
                // Group stage participation bonus (first matchday only)
                awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
                awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
            }
            if (roundId >= 1 && roundId <= 6) {
                // Group stage: win = 1.5M, draw = 500K
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (roundId == 8) {
                // QF qualification
                awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
            } else if (roundId == 9) {
                // SF qualification
                awardPrizeMoney(team1Id, 10_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 10_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
            } else if (roundId == 10) {
                // Final: winner 15M, runner-up 8M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team2Id, 8_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                } else {
                    awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team1Id, 8_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                }
            }
        }
    }

    private void awardPrizeMoney(long teamId, long amount, int season, int roundNumber, String reason, String category) {
        financeService.recordTransaction(teamId, season, roundNumber, "PRIZE_MONEY", reason, amount);

        // Send inbox notification only for human teams
        if (userContext.isHumanTeam(teamId)) {
            String formattedAmount = formatPrizeMoney(amount);
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(roundNumber);
            inbox.setTitle(reason);
            inbox.setContent("Your club has received " + formattedAmount + " for " + reason + ".");
            inbox.setCategory(category);
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    private String formatPrizeMoney(long amount) {
        if (amount >= 1_000_000L) {
            double millions = amount / 1_000_000.0;
            if (millions == (long) millions) return (long) millions + "M";
            return String.format("%.1fM", millions);
        } else if (amount >= 1_000L) {
            return (amount / 1_000) + "K";
        }
        return String.valueOf(amount);
    }

    private void addClubCoefficient(long teamId, int season, double points) {
        if (points <= 0) return;
        Optional<ClubCoefficient> existing = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, season);
        if (existing.isPresent()) {
            ClubCoefficient cc = existing.get();
            cc.setPoints(cc.getPoints() + points);
            clubCoefficientRepository.save(cc);
        } else {
            ClubCoefficient cc = new ClubCoefficient();
            cc.setTeamId(teamId);
            cc.setSeasonNumber(season);
            cc.setPoints(points);
            clubCoefficientRepository.save(cc);
        }
    }

    /** Public so {@link EuropeanCompetitionService} can call back for seeded
     *  draws. Same rolling-5-season sum used elsewhere on the controller. */
    public double getClubCoefficientRolling(long teamId, int currentSeason) {
        double total = 0;
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, s);
            if (cc.isPresent()) total += cc.get().getPoints();
        }
        return total;
    }

    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // LoC allocation totals
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
        int numLeagues = (int) competitionRepository.findAll().stream().filter(c -> c.getTypeId() == 1).count();
        int totalRanks = Math.min(numLeagues, 7);

        int totalDirect = 0, totalQualifying = 0, totalPreliminary = 0;
        for (int i = 0; i < totalRanks; i++) {
            totalDirect += directSpots[i];
            totalQualifying += qualifyingSpots[i];
            totalPreliminary += preliminarySpots[i];
        }
        int locTotal = totalDirect + totalQualifying + totalPreliminary;
        int groupStageTeams = totalDirect + (totalQualifying + totalPreliminary / 2) / 2;

        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("totalTeams", locTotal);
        loc.put("directToGroups", totalDirect);
        loc.put("qualifyingTeams", totalQualifying);
        loc.put("preliminaryTeams", totalPreliminary);
        loc.put("groupStageTeams", 16);
        loc.put("groups", 4);
        loc.put("teamsPerGroup", 4);
        loc.put("advancePerGroup", 2);
        summary.put("loc", loc);

        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("totalTeams", 16);
        sc.put("groups", 4);
        sc.put("teamsPerGroup", 4);
        sc.put("format", "Group winners to QF, runners-up play LoC 3rd place in Playoff, then QF → SF → Final");
        summary.put("starsCup", sc);

        // Points system
        Map<String, Object> locPoints = new LinkedHashMap<>();
        locPoints.put("Preliminary/Qualifying win", "1 pt");
        locPoints.put("Group stage win", "2 pts");
        locPoints.put("Group stage draw", "1 pt");
        locPoints.put("Quarter-Final win", "3 pts");
        locPoints.put("Semi-Final win", "4 pts");
        locPoints.put("Final win", "5 pts");
        summary.put("locPoints", locPoints);

        Map<String, Object> scPoints = new LinkedHashMap<>();
        scPoints.put("Group stage win", "1 pt");
        scPoints.put("Group stage draw", "0.5 pts");
        scPoints.put("Playoff win", "1 pt");
        scPoints.put("Quarter-Final win", "1.5 pts");
        scPoints.put("Semi-Final win", "2 pts");
        scPoints.put("Final win", "2.5 pts");
        summary.put("starsCupPoints", scPoints);

        return summary;
    }

    @GetMapping("/getCountryCoefficients")
    public List<Map<String, Object>> getCountryCoefficients() {
        int currentSeason = Integer.parseInt(getCurrentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            long nationId = league.getNationId();

            // Calculate country coefficient: for each of last 5 seasons,
            // sum(club points) / count(clubs that participated), then sum the per-season ratios
            double countryCoefficient = 0;
            Map<Integer, Double> perSeasonValues = new LinkedHashMap<>();

            for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
                // Find clubs from this nation that participated in European comps in season s
                int seasonFinal = s;
                List<CompetitionTeamInfo> europeanEntries = competitionTeamInfoRepository
                        .findAllBySeasonNumber(seasonFinal).stream()
                        .filter(cti -> {
                            Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                            if (comp == null) return false;
                            return (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                        })
                        .toList();

                // Match by nationId: team belongs to this country if their league has same nationId
                Set<Long> clubsFromNation = new HashSet<>();
                double seasonPoints = 0;
                for (CompetitionTeamInfo cti : europeanEntries) {
                    Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
                    if (team == null) continue;
                    Competition teamLeague = competitionRepository.findById(team.getCompetitionId()).orElse(null);
                    if (teamLeague != null && teamLeague.getNationId() == nationId) {
                        clubsFromNation.add(cti.getTeamId());
                    }
                }

                for (long clubId : clubsFromNation) {
                    Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, seasonFinal);
                    if (cc.isPresent()) seasonPoints += cc.get().getPoints();
                }

                double seasonRatio = clubsFromNation.isEmpty() ? 0 : seasonPoints / clubsFromNation.size();
                perSeasonValues.put(s, seasonRatio);
                countryCoefficient += seasonRatio;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("leagueId", leagueId);
            entry.put("leagueName", league.getName());
            entry.put("nationId", nationId);
            entry.put("coefficient", Math.round(countryCoefficient * 100.0) / 100.0);
            entry.put("perSeason", perSeasonValues);
            result.add(entry);
        }

        // Sort by coefficient descending
        result.sort((a, b) -> Double.compare((double) b.get("coefficient"), (double) a.get("coefficient")));

        // Assign ranks and allocation
        for (int i = 0; i < result.size(); i++) {
            int rank = i + 1;
            Map<String, Object> entry = result.get(i);
            entry.put("rank", rank);
            assignEuropeanAllocation(entry, rank);
        }

        return result;
    }

    @GetMapping("/getClubCoefficients")
    public List<Map<String, Object>> getClubCoefficients() {
        int currentSeason = Integer.parseInt(getCurrentSeason());

        // Find all clubs that ever participated in European comps
        Set<Long> europeanClubIds = new HashSet<>();
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            int sFinal = s;
            competitionTeamInfoRepository.findAllBySeasonNumber(sFinal).stream()
                    .filter(cti -> {
                        Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                        return comp != null && (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                    })
                    .forEach(cti -> europeanClubIds.add(cti.getTeamId()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (long clubId : europeanClubIds) {
            Team team = teamRepository.findById(clubId).orElse(null);
            if (team == null) continue;

            double totalCoeff = 0;
            Map<Integer, Double> perSeason = new LinkedHashMap<>();
            for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
                Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, s);
                double pts = cc.map(ClubCoefficient::getPoints).orElse(0.0);
                perSeason.put(s, pts);
                totalCoeff += pts;
            }

            Competition league = competitionRepository.findById(team.getCompetitionId()).orElse(null);

            Map<String, Object> entry = new HashMap<>();
            entry.put("teamId", clubId);
            entry.put("teamName", team.getName());
            entry.put("leagueName", league != null ? league.getName() : "");
            entry.put("coefficient", Math.round(totalCoeff * 100.0) / 100.0);
            entry.put("perSeason", perSeason);
            result.add(entry);
        }

        result.sort((a, b) -> Double.compare((double) b.get("coefficient"), (double) a.get("coefficient")));

        // Assign rank
        for (int i = 0; i < result.size(); i++) {
            result.get(i).put("rank", i + 1);
        }

        return result;
    }

    private void assignEuropeanAllocation(Map<String, Object> entry, int rank) {
        // LoC direct + qualifying + preliminary spots per rank (non-increasing)
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
        // Stars Cup: league spots + 1 cup reserved per nation (non-increasing)
        int[] scLeagueSpots = {1, 1, 1, 1, 0, 0, 0};
        int[] scCupSpots =    {1, 1, 1, 1, 1, 1, 1};

        if (rank >= 1 && rank <= 7) {
            int idx = rank - 1;
            int locTotal = directSpots[idx] + qualifyingSpots[idx] + preliminarySpots[idx];
            entry.put("locSpots", locTotal);

            String locEntry;
            if (directSpots[idx] > 0 && qualifyingSpots[idx] > 0) {
                locEntry = directSpots[idx] + " Group Stage + " + qualifyingSpots[idx] + " Qualifying";
            } else if (preliminarySpots[idx] > 0) {
                locEntry = preliminarySpots[idx] + " Preliminary Qualifying";
            } else if (directSpots[idx] > 0) {
                locEntry = directSpots[idx] + " Group Stage";
            } else {
                locEntry = "None";
            }
            entry.put("locEntry", locEntry);
            entry.put("starsCupSpots", scLeagueSpots[idx] + scCupSpots[idx]);
        } else {
            entry.put("locSpots", 0);
            entry.put("locEntry", "None");
            entry.put("starsCupSpots", 0);
        }
    }

    private List<Long> getLeagueIdsSortedByCoefficient() {
        int currentSeason = Integer.parseInt(getCurrentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        Map<Long, Double> leagueCoefficients = new HashMap<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            long nationId = league.getNationId();
            double countryCoefficient = 0;

            for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
                int sFinal = s;
                List<CompetitionTeamInfo> europeanEntries = competitionTeamInfoRepository
                        .findAllBySeasonNumber(sFinal).stream()
                        .filter(cti -> {
                            Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                            return comp != null && (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                        })
                        .toList();

                Set<Long> clubsFromNation = new HashSet<>();
                double seasonPoints = 0;
                for (CompetitionTeamInfo cti : europeanEntries) {
                    Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
                    if (team == null) continue;
                    Competition teamLeague = competitionRepository.findById(team.getCompetitionId()).orElse(null);
                    if (teamLeague != null && teamLeague.getNationId() == nationId) {
                        clubsFromNation.add(cti.getTeamId());
                    }
                }
                for (long clubId : clubsFromNation) {
                    Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, sFinal);
                    if (cc.isPresent()) seasonPoints += cc.get().getPoints();
                }
                double seasonRatio = clubsFromNation.isEmpty() ? 0 : seasonPoints / clubsFromNation.size();
                countryCoefficient += seasonRatio;
            }

            // Fallback: if no coefficient data yet, use avg reputation
            if (countryCoefficient == 0) {
                List<Team> teams = teamRepository.findAll().stream()
                        .filter(t -> t.getCompetitionId() == leagueId).toList();
                countryCoefficient = teams.stream().mapToInt(Team::getReputation).average().orElse(0) / 100.0;
            }

            leagueCoefficients.put(leagueId, countryCoefficient);
        }

        List<Long> sorted = new ArrayList<>(leagueCoefficients.keySet());
        sorted.sort((a, b) -> Double.compare(leagueCoefficients.get(b), leagueCoefficients.get(a)));
        return sorted;
    }


    // ============================================================
    //  European competition methods — delegate to
    //  EuropeanCompetitionService. Kept as thin wrappers so the
    //  controller's internal callers (play() orchestration,
    //  simulateRound dispatch) don't have to change.
    // ============================================================

    private void drawEuropeanGroups(long competitionId, int groupStageRound) {
        europeanCompetitionService.drawEuropeanGroups(competitionId, groupStageRound);
    }

    private long getTeamNationId(long teamId) {
        return europeanCompetitionService.getTeamNationId(teamId);
    }

    private void drawEuropeanKnockoutSeeded(long competitionId, long roundId, List<Long> participants) {
        europeanCompetitionService.drawEuropeanKnockoutSeeded(competitionId, roundId, participants);
    }

    private void drawStarsCupPlayoffSeeded(long starsCupCompetitionId, long roundId, List<Long> participants) {
        europeanCompetitionService.drawStarsCupPlayoffSeeded(starsCupCompetitionId, roundId, participants);
    }

    private void resetEuropeanStats(long competitionId) {
        europeanCompetitionService.resetEuropeanStats(competitionId);
    }

    private void generateGroupStageFixtures(long competitionId) {
        europeanCompetitionService.generateGroupStageFixtures(competitionId);
    }

    private void qualifyFromGroupStage(long locCompetitionId) {
        europeanCompetitionService.qualifyFromGroupStage(locCompetitionId);
    }

    private void assignLocLosersToStarsCup(long locCompetitionId, int locRound) {
        europeanCompetitionService.assignLocLosersToStarsCup(locCompetitionId, locRound);
    }

    private void qualifyFromStarsCupGroupStage(long starsCupCompetitionId) {
        europeanCompetitionService.qualifyFromStarsCupGroupStage(starsCupCompetitionId);
    }

    private void initialization() {

        initializeCompetitions();
        initializeTeams1();
        initializeTeams2();
        initializeTeams3();
        initializeTeams4();
        initializeTeams5();
        initializeTeams6();
        initializeTeams7();
        initializeTeams8();

        initializeSpecialPlayers();
    }

    private void initializeCompetitions() {

        List<List<Integer>> values = new ArrayList<>(List.of(List.of(1, 1, 1), List.of(1, 2, 2), List.of(3, 1, 1),
                List.of(3, 2, 2), List.of(3, 3, 3), List.of(2, 2, 1), List.of(2, 3, 2), List.of(4, 2, 1), List.of(4, 3, 2),
                List.of(0, 1, 4), List.of(0, 2, 5),
                List.of(5, 1, 1), List.of(5, 2, 2),
                List.of(6, 1, 1), List.of(6, 2, 2),
                List.of(7, 1, 1), List.of(7, 2, 2)));

        List<String> names = new ArrayList<>(List.of("Gallactick Football First League", "Gallactick Football Cup",
                "Khess First League", "Khess Cup", "Khess Second League", "Dong Championship", "Dong Cup", "FootieCup League",
                "FootieCup Cup", "League of Champions", "Stars Cup",
                "Cards League", "Cards Cup", "Literature League", "Literature Cup",
                "Eleven League", "Eleven Cup"));

        for (int i = 0; i < values.size(); i++) {
            Competition competition = new Competition();
            competition.setNationId(values.get(i).get(0));
            competition.setPrizesId(values.get(i).get(1));
            competition.setTypeId(values.get(i).get(2));
            competition.setName(names.get(i));

            competitionRepository.save(competition);
        }
    }

    private void initializeTeams1() {

        List<List<String>> teamNames = List.of(
                List.of("Shadows", "black", "grey", "25"),
                List.of("Ligthnings", "blue", "darkblue", "55"),
                List.of("Xenon", "green", "darkgreen", "35"),
                List.of("Snow Kids", "white", "blue", "65"),
                List.of("Wambas", "yellow", "green", "5"),
                List.of("Technoid", "grey", "green", "70"),
                List.of("Cyclops", "orange", "black", "45"),
                List.of("Red Tigers", "red", "grey", "25"),
                List.of("Akillian", "white", "grey", "35"),
                List.of("Rykers", "orange", "yellow", "60"),
                List.of("Pirates", "blue", "black", "95"),
                List.of("Elektras", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5),
                List.of(9000, 5),
                List.of(9000, 5),
                List.of(8600, 2),
                List.of(8000, 4),
                List.of(7900, 3),
                List.of(7000, 2),
                List.of(6900, 1),
                List.of(6000, 1),
                List.of(7000, 2),
                List.of(6700, 1),
                List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(16, 20, 20),
                List.of(15, 20, 18),
                List.of(15, 20, 18),
                List.of(10, 18, 16),
                List.of(10, 16, 16),
                List.of(10, 15, 15),
                List.of(10, 12, 14),
                List.of(10, 14, 13),
                List.of(10, 12, 12),
                List.of(8, 12, 10),
                List.of(7, 11, 9),
                List.of(6, 10, 9)
        );

        int addedModulo = 0;
        long leagueId = 1L;
        long cupId = 2L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams2() {

        List<List<String>> teamNames = List.of(
                List.of("FC San Marino", "black", "grey", "25"),
                List.of("Tik Tok", "blue", "darkblue", "55"),
                List.of("No Merci", "green", "darkgreen", "35"),
                List.of("Karagandy", "white", "blue", "65"),
                List.of("Krioyv", "yellow", "green", "5"),
                List.of("Korbordi", "grey", "green", "70"),
                List.of("Kavantaly", "orange", "black", "45"),
                List.of("Kaspersky", "red", "grey", "25"),
                List.of("Kadaver", "white", "grey", "35"),
                List.of("Kavi Kan", "orange", "yellow", "60"),
                List.of("Koroga", "blue", "black", "95"),
                List.of("Kugantuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5),
                List.of(9000, 5),
                List.of(9000, 5),
                List.of(8600, 2),
                List.of(8000, 4),
                List.of(7900, 3),
                List.of(7000, 2),
                List.of(6900, 1),
                List.of(6000, 1),
                List.of(7000, 2),
                List.of(6700, 1),
                List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(20, 20, 20),
                List.of(20, 20, 20),
                List.of(15, 20, 18),
                List.of(10, 18, 16),
                List.of(10, 16, 16),
                List.of(10, 15, 15),
                List.of(10, 12, 14),
                List.of(10, 14, 13),
                List.of(10, 12, 12),
                List.of(8, 12, 10),
                List.of(7, 11, 9),
                List.of(6, 10, 9)
        );

        int addedModulo = 12;
        long leagueId = 3L;
        long cupId = 4L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams3() {

        List<List<String>> teamNames = List.of(
                List.of("Karyo", "black", "grey", "25"),
                List.of("Korny", "blue", "darkblue", "55"),
                List.of("La Kavardi", "green", "darkgreen", "35"),
                List.of("Kadaveriki", "white", "blue", "65"),
                List.of("Konstenti", "yellow", "green", "5"),
                List.of("Kirokiri", "grey", "green", "70"),
                List.of("Kusparsky", "orange", "black", "45"),
                List.of("Kindonersky", "red", "grey", "25"),
                List.of("Kor Kory", "white", "grey", "35"),
                List.of("Kuvertini", "orange", "yellow", "60"),
                List.of("Kora", "blue", "black", "95"),
                List.of("Kuntuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(7, 4, 1),
                List.of(6, 3, 4),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 24;
        long leagueId = 5L;
        long cupId = 4L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams4() {

        List<List<String>> teamNames = List.of(
                List.of("Ding Dong", "black", "grey", "25"),
                List.of("Dinamo Kanibali", "blue", "darkblue", "55"),
                List.of("Grobienii", "green", "darkgreen", "35"),
                List.of("Grodienii", "white", "blue", "65"),
                List.of("Artistii", "yellow", "green", "5"),
                List.of("Mumiile", "grey", "green", "70"),
                List.of("Vikingii", "orange", "black", "45"),
                List.of("Vanatorii", "red", "grey", "25"),
                List.of("Faraonii", "white", "grey", "35"),
                List.of("Kuvertini", "orange", "yellow", "60"),
                List.of("Kora", "blue", "black", "95"),
                List.of("Kuntuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15),
                List.of(13, 8, 14),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 36;
        long leagueId = 6L;
        long cupId = 7L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams5() {

        List<List<String>> teamNames = List.of(
                List.of("EuroFlava", "red", "white", "25"),
                List.of("Kossack Team", "green", "darkgreen", "55"),
                List.of("Pro Lapad Sport", "red", "darkred", "35"),
                List.of("FC Blue", "white", "blue", "65"),
                List.of("Athletic Sohatu", "yellow", "green", "5"),
                List.of("Tutucea Team", "grey", "green", "70"),
                List.of("FC Arges IV", "orange", "black", "45"),
                List.of("ManCester Sibiu", "red", "grey", "25"),
                List.of("FC Angells", "white", "grey", "35"),
                List.of("Club 16", "orange", "yellow", "60"),
                List.of("FC Spicul Tamaseni", "blue", "black", "95"),
                List.of("Chris Team", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15),
                List.of(13, 8, 14),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 48;
        long leagueId = 8L;
        long cupId = 9L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams6() {

        List<List<String>> teamNames = List.of(
                List.of("Yu Gi Oh", "purple", "gold", "45"),
                List.of("Duel Masters", "red", "black", "30"),
                List.of("Cardisio", "blue", "silver", "60"),
                List.of("Bidaman", "green", "white", "15"),
                List.of("Bay Blade", "orange", "grey", "75"),
                List.of("Pokemon", "yellow", "red", "50"),
                List.of("Dragon Ball Z", "orange", "blue", "20"),
                List.of("Digimon", "cyan", "green", "85"));

        List<List<Integer>> teamValues = List.of(
                List.of(5000, 3),
                List.of(4800, 4),
                List.of(4600, 2),
                List.of(4400, 3),
                List.of(4200, 5),
                List.of(4000, 2),
                List.of(3800, 1),
                List.of(3600, 4));

        List<List<Integer>> facilities = List.of(
                List.of(8, 8, 8),
                List.of(7, 7, 7),
                List.of(6, 8, 6),
                List.of(7, 6, 5),
                List.of(5, 7, 6),
                List.of(6, 5, 7),
                List.of(5, 6, 5),
                List.of(4, 5, 4));

        int addedModulo = 60;
        long leagueId = 12L;
        long cupId = 13L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams7() {

        List<List<String>> teamNames = List.of(
                List.of("Toamna Patriarhului", "brown", "gold", "40"),
                List.of("Carabusul de Aur", "gold", "black", "55"),
                List.of("Moara cu Noroc", "green", "brown", "25"),
                List.of("Ion Tara FC", "white", "blue", "70"),
                List.of("Enigma Otiliei", "purple", "white", "35"),
                List.of("Harap Alb FC", "white", "red", "10"),
                List.of("Morometii", "darkgreen", "grey", "80"),
                List.of("Baltagul FC", "brown", "red", "45"),
                List.of("Ultima Noapte", "darkblue", "white", "60"),
                List.of("Floare Albastra", "blue", "lightblue", "15"),
                List.of("Rascoala FC", "red", "orange", "90"),
                List.of("Padurea Spinzuratilor", "darkgreen", "black", "30"),
                List.of("Maitreyi FC", "orange", "gold", "50"),
                List.of("Fram Ursul Polar", "white", "cyan", "65"),
                List.of("Don Quijote FC", "red", "yellow", "20"),
                List.of("Gatsby United", "gold", "white", "75"),
                List.of("Moby Dick FC", "blue", "grey", "5"),
                List.of("Sherlock FC", "brown", "darkbrown", "85"));

        List<List<Integer>> teamValues = List.of(
                List.of(4500, 3), List.of(4300, 4), List.of(4100, 2),
                List.of(4000, 3), List.of(3900, 5), List.of(3800, 2),
                List.of(3700, 1), List.of(3600, 4), List.of(3500, 3),
                List.of(3400, 2), List.of(3300, 1), List.of(3200, 3),
                List.of(3100, 4), List.of(3000, 2), List.of(2900, 1),
                List.of(2800, 3), List.of(2700, 2), List.of(2600, 1));

        List<List<Integer>> facilities = List.of(
                List.of(6, 6, 6), List.of(5, 7, 5), List.of(6, 5, 4),
                List.of(5, 6, 5), List.of(4, 5, 6), List.of(5, 4, 3),
                List.of(4, 5, 4), List.of(3, 4, 5), List.of(5, 3, 4),
                List.of(4, 4, 3), List.of(3, 5, 3), List.of(4, 3, 4),
                List.of(3, 4, 3), List.of(3, 3, 4), List.of(4, 3, 3),
                List.of(3, 3, 3), List.of(3, 2, 3), List.of(2, 3, 2));

        int addedModulo = 68;
        long leagueId = 14L;
        long cupId = 15L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeTeams8() {

        List<List<String>> teamNames = List.of(
                List.of("Inazuma Japan", "blue", "yellow", "40"),
                List.of("Zero", "black", "red", "55"),
                List.of("FF Raimon", "orange", "blue", "30"),
                List.of("Royal Academy", "purple", "white", "70"),
                List.of("Zeus", "gold", "white", "45"),
                List.of("Occult", "darkgreen", "black", "60"),
                List.of("Kirkwood", "red", "white", "35"),
                List.of("Gemini Storm", "cyan", "blue", "50"),
                List.of("Epsilon", "white", "purple", "25"),
                List.of("Diamond Dust", "lightblue", "white", "65"),
                List.of("Prominence", "red", "orange", "15"),
                List.of("The Genesis", "black", "gold", "80"),
                List.of("Knights of Queen", "white", "red", "90"),
                List.of("Orpheus", "blue", "white", "20"),
                List.of("Fire Dragon", "red", "yellow", "75"),
                List.of("Little Gigant", "green", "yellow", "10"),
                List.of("Unicorn", "blue", "red", "85"),
                List.of("Desert Lion", "gold", "brown", "95"),
                List.of("Big Waves", "cyan", "white", "5"),
                List.of("The Empire", "grey", "black", "50"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5), List.of(8000, 4), List.of(7800, 3),
                List.of(7600, 5), List.of(7400, 4), List.of(7200, 2),
                List.of(7000, 3), List.of(6800, 4), List.of(6600, 5),
                List.of(6400, 2), List.of(6200, 1), List.of(6000, 5),
                List.of(5800, 3), List.of(5600, 4), List.of(5400, 1),
                List.of(5200, 2), List.of(5000, 3), List.of(4800, 1),
                List.of(4600, 2), List.of(4400, 4));

        List<List<Integer>> facilities = List.of(
                List.of(14, 18, 17), List.of(13, 17, 16), List.of(13, 16, 15),
                List.of(12, 15, 14), List.of(12, 14, 13), List.of(11, 14, 13),
                List.of(11, 13, 12), List.of(10, 12, 11), List.of(10, 11, 11),
                List.of(9, 11, 10), List.of(9, 10, 10), List.of(8, 10, 9),
                List.of(8, 9, 9), List.of(7, 9, 8), List.of(7, 8, 8),
                List.of(6, 8, 7), List.of(6, 7, 7), List.of(5, 7, 6),
                List.of(5, 6, 6), List.of(4, 5, 5));

        int addedModulo = 86;
        long leagueId = 16L;
        long cupId = 17L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeSpecialPlayers() {

        Human Kvekrpur = new Human();
        Kvekrpur.setRating(300);
        Kvekrpur.setName("Kvekrpur");
        Kvekrpur.setTeamId(14L); // Tik Tok
        Kvekrpur.setAge(20);
        Kvekrpur.setMorale(70D);
        Kvekrpur.setFitness(100D);
        Kvekrpur.setCurrentAbility(300);
        Kvekrpur.setPotentialAbility(350);
        Kvekrpur.setBestEverRating(300);
        Kvekrpur.setSeasonCreated(1);
        Kvekrpur.setTypeId(1);
        Kvekrpur.setPosition("ST");
        Kvekrpur.setCurrentStatus("Junior");

        humanRepository.save(Kvekrpur);
    }

    private void createTeamsAndCompetitions(List<List<String>> teamNames, List<List<Integer>> teamValues, List<List<Integer>> facilities, int addedModulo, long leagueId, long cupId) {

        for (int i = 0; i < teamNames.size(); i++) {

            Team team = new Team();
            team.setId(i + addedModulo + 1);
            team.setName(teamNames.get(i).get(0));
            team.setColor1(teamNames.get(i).get(1));
            team.setColor2(teamNames.get(i).get(2));
            team.setBorder(teamNames.get(i).get(3));
            team.setReputation(teamValues.get(i).get(0));
            team.setStrategy((long) teamValues.get(i).get(1));
            team.setCompetitionId(leagueId);
            team.setTransferBudget(0L);
            team.setTotalFinances(0L);
            // Stadium capacity based on reputation: 10,000 base + reputation * 8
            int stadiumCapacity = 10_000 + teamValues.get(i).get(0) * 8;
            team.setStadiumCapacity(stadiumCapacity);
            team.setStadiumName(teamNames.get(i).get(0) + " Stadium");
            team.setBoardConfidence(50);
            teamRepository.save(team);

            CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(1);
            competitionTeamInfo.setCompetitionId(leagueId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            int numTeams = teamNames.size();
            int numCupRounds = (int) Math.ceil(Math.log(numTeams) / Math.log(2));
            int numByes = (int) Math.pow(2, numCupRounds) - numTeams;

            competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(i < numByes ? 2 : 1);
            competitionTeamInfo.setCompetitionId(cupId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            TeamFacilities teamFacilities = new TeamFacilities();
            teamFacilities.setTeamId(i + addedModulo + 1);
            teamFacilities.setYouthAcademyLevel(facilities.get(i).get(0));
            teamFacilities.setYouthTrainingLevel(facilities.get(i).get(1));
            teamFacilities.setSeniorTrainingLevel(facilities.get(i).get(2));
            teamFacilities.setScoutingLevel(Math.min(20, Math.max(1, (facilities.get(i).get(0) + facilities.get(i).get(1)) / 2)));
            _teamFacilitiesRepository.save(teamFacilities);

            // Create Stadium entity
            Stadium stadium = new Stadium();
            stadium.setTeamId(i + addedModulo + 1);
            stadium.setStadiumName(teamNames.get(i).get(0) + " Stadium");
            stadium.setCapacity(stadiumCapacity);
            // Initialize stadium facilities based on reputation tier
            int rep = teamValues.get(i).get(0);
            if (rep >= 9000) {
                stadium.setVipBoxesLevel(5);
                stadium.setCateringLevel(4);
                stadium.setFanShopLevel(4);
                stadium.setFastFoodLevel(3);
                stadium.setHeadquartersLevel(5);
                stadium.setTrainingPitchLevel(5);
                stadium.setParkingLevel(4);
            } else if (rep >= 7000) {
                stadium.setVipBoxesLevel(3);
                stadium.setCateringLevel(2);
                stadium.setFanShopLevel(2);
                stadium.setFastFoodLevel(2);
                stadium.setHeadquartersLevel(3);
                stadium.setTrainingPitchLevel(3);
                stadium.setParkingLevel(2);
            } else if (rep >= 5000) {
                stadium.setVipBoxesLevel(1);
                stadium.setCateringLevel(1);
                stadium.setFanShopLevel(1);
                stadium.setFastFoodLevel(1);
                stadium.setHeadquartersLevel(2);
                stadium.setTrainingPitchLevel(2);
                stadium.setParkingLevel(1);
            }
            stadiumRepository.save(stadium);

            // Initialize default training schedule for this team
            long currentTeamId = i + addedModulo + 1;
            List<TrainingSchedule> defaultSchedule = TrainingController.buildDefaultSchedule(currentTeamId);
            trainingScheduleRepository.saveAll(defaultSchedule);

            // Initialize manager for this team
            Human manager = new Human();
            manager.setName(compositeNameGenerator.generateName(1L));
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            manager.setTeamId(currentTeamId);
            manager.setManagerReputation(team.getReputation() / 3);
            manager.setAge(35 + new Random().nextInt(20)); // 35-54
            manager.setSeasonCreated(1);
            manager.setMorale(70D);
            manager.setFitness(100D);
            manager.setRating(0);
            String[] kit = tacticService.buildManagerTacticKit((int) manager.getRating(), new Random());
            manager.setTacticStyle(kit[0]);
            manager.setKnownTactics(kit[1]);
            humanRepository.save(manager);
        }
    }

    private void applyTrainingEffect(List<Long> teamIds) {
        // Pre-load all training schedules in one query
        List<TrainingSchedule> allSchedules = trainingScheduleRepository.findAll();
        Map<Long, Double> teamAvgIntensity = allSchedules.stream()
                .collect(Collectors.groupingBy(TrainingSchedule::getTeamId,
                        Collectors.averagingInt(TrainingSchedule::getIntensity)));

        List<Human> playersToSave = new ArrayList<>();

        for (Long teamId : teamIds) {
            double avgIntensity = teamAvgIntensity.getOrDefault(teamId, 0.0);
            if (avgIntensity == 0) continue;

            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player : players) {
                if (player.isRetired()) continue;

                // Season-end rating boost: 0.5 to 2.0 depending on training intensity
                double baseBoost = 0.5 + (avgIntensity / 100.0) * 1.5;

                double ageModifier;
                if (player.getAge() < 23) {
                    ageModifier = 1.5;
                } else if (player.getAge() > 30) {
                    ageModifier = 0.5;
                } else {
                    ageModifier = 1.0;
                }

                double ratingBoost = baseBoost * ageModifier;

                double newRating = player.getRating() + ratingBoost;
                if (player.getPotentialAbility() > 0 && newRating > player.getPotentialAbility()) {
                    newRating = player.getPotentialAbility();
                }

                player.setRating(newRating);

                if (newRating > player.getBestEverRating()) {
                    player.setBestEverRating(newRating);
                    player.setSeasonOfBestEverRating((int) round.getSeason());
                }

                playersToSave.add(player);
            }
        }

        humanRepository.saveAll(playersToSave);
    }

    public Set<Long> getCompetitionIdsByCompetitionType(int competitionTypeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == competitionTypeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    public Round getRound() {

        return round;
    }

    // getHumanTeamId() removed - replaced by userContext.getTeamId(request), userContext.isHumanTeam(teamId), or userContext.getAllHumanTeamIds()
    private Set<Long> injuredPlayerIdsCache = new HashSet<>();

    public void generateMatchReport(long competitionId, long roundId, long teamId1, long teamId2, int teamScore1, int teamScore2) {
        // Only generate inbox reports for human players' teams
        boolean team1IsHuman = userContext.isHumanTeam(teamId1);
        boolean team2IsHuman = userContext.isHumanTeam(teamId2);
        if (!team1IsHuman && !team2IsHuman) return;

        String teamName1 = teamRepository.findById(teamId1).map(Team::getName).orElse("Unknown");
        String teamName2 = teamRepository.findById(teamId2).map(Team::getName).orElse("Unknown");
        String competitionName = competitionRepository.findById(competitionId).map(Competition::getName).orElse("Unknown");
        int seasonNumber = Integer.parseInt(getCurrentSeason());
        int roundNumber = (int) roundId;

        if (team1IsHuman) {
            generateMatchReportForTeam(teamId1, teamName1, teamId2, teamName2, teamScore1, teamScore2,
                    competitionName, seasonNumber, roundNumber);
        }
        if (team2IsHuman) {
            generateMatchReportForTeam(teamId2, teamName2, teamId1, teamName1, teamScore2, teamScore1,
                    competitionName, seasonNumber, roundNumber);
        }
    }

    private void generateMatchReportForTeam(long teamId, String teamName, long opponentTeamId, String opponentName,
                                            int teamScore, int opponentScore, String competitionName,
                                            int seasonNumber, int roundNumber) {
        String resultPrefix;
        if (teamScore > opponentScore) {
            resultPrefix = "Victory! ";
        } else if (teamScore < opponentScore) {
            resultPrefix = "Defeat. ";
        } else {
            resultPrefix = "Draw. ";
        }

        String title = resultPrefix + teamName + " " + teamScore + "-" + opponentScore + " " + opponentName;

        // Build content with match details
        StringBuilder content = new StringBuilder();
        content.append("Competition: ").append(competitionName).append("\n");
        content.append("Result: ").append(teamName).append(" ").append(teamScore)
                .append(" - ").append(opponentScore).append(" ").append(opponentName).append("\n");

        // Find goal scorers for this team in this match (use season-scoped query)
        List<Scorer> matchScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber).stream()
                .filter(s -> s.getOpponentTeamId() == opponentTeamId)
                .filter(s -> s.getTeamScore() == teamScore)
                .filter(s -> s.getOpponentScore() == opponentScore)
                .filter(s -> s.getGoals() > 0)
                .collect(Collectors.toList());

        if (!matchScorers.isEmpty()) {
            content.append("Goals: ");
            String scorersList = matchScorers.stream()
                    .map(scorer -> {
                        String playerName = humanRepository.findById(scorer.getPlayerId())
                                .map(Human::getName).orElse("Unknown");
                        return playerName + " (" + scorer.getGoals() + ")";
                    })
                    .collect(Collectors.joining(", "));
            content.append(scorersList).append("\n");
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(seasonNumber);
        inbox.setRoundNumber(roundNumber);
        inbox.setTitle(title);
        inbox.setContent(content.toString());
        inbox.setCategory("match_result");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }

    // ==================== INJURY SYSTEM ====================

    private void decrementInjuryDays() {
        List<Injury> activeInjuries = injuryRepository.findAll().stream()
                .filter(injury -> injury.getDaysRemaining() > 0)
                .toList();

        for (Injury injury : activeInjuries) {
            injury.setDaysRemaining(injury.getDaysRemaining() - 1);
            injuryRepository.save(injury);

            // When injury heals, update player status
            if (injury.getDaysRemaining() <= 0) {
                Optional<Human> playerOpt = humanRepository.findById(injury.getPlayerId());
                if (playerOpt.isPresent()) {
                    Human player = playerOpt.get();
                    player.setCurrentStatus("Available");
                    humanRepository.save(player);
                }
            }
        }
    }

    public void processInjuriesForTeam(long teamId) {
        Random random = new Random();
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        // Pre-load injured player IDs to avoid N+1 queries
        Set<Long> injuredPlayerIds = injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream().map(Injury::getPlayerId).collect(Collectors.toSet());

        String[] injuryTypes = {"Hamstring Strain", "Knee Ligament", "Ankle Sprain", "Muscle Fatigue", "Broken Bone", "Concussion"};

        for (Human player : players) {
            if (player.isRetired()) continue;
            if (injuredPlayerIds.contains(player.getId())) continue;

            // Base injury chance: 0.2%
            double injuryChance = 0.002;

            // Lower fitness increases injury chance
            if (player.getFitness() < 50) {
                injuryChance += 0.001;
            }

            if (random.nextDouble() < injuryChance) {
                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(teamId);
                injury.setSeasonNumber(Integer.parseInt(getCurrentSeason()));

                // Random injury type
                String injuryType = injuryTypes[random.nextInt(injuryTypes.length)];
                injury.setInjuryType(injuryType);

                // Determine severity and duration
                double severityRoll = random.nextDouble();
                if (severityRoll < 0.55) {
                    // Minor: 1-3 rounds
                    injury.setSeverity("Minor");
                    injury.setDaysRemaining(random.nextInt(1, 4));
                } else if (severityRoll < 0.85) {
                    // Moderate: 4-8 rounds
                    injury.setSeverity("Moderate");
                    injury.setDaysRemaining(random.nextInt(4, 9));
                } else {
                    // Serious: 10-20 rounds
                    injury.setSeverity("Serious");
                    injury.setDaysRemaining(random.nextInt(10, 21));
                }

                injuryRepository.save(injury);

                // Update player status
                player.setCurrentStatus("Injured - " + injuryType);
                humanRepository.save(player);
            }
        }
    }

    public boolean isPlayerInjured(long playerId) {
        return injuredPlayerIdsCache.contains(playerId);
    }

    // ==================== TEAM TALK ====================

    @Autowired
    private TeamTalkService teamTalkService;

    public void resetTeamTalkUsed() {
        teamTalkUsedThisRound = false;
        teamTalkService.resetAllForNewMatch();
    }

    @GetMapping("/teamTalkStatus")
    public Map<String, Object> getTeamTalkStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("used", teamTalkUsedThisRound);
        status.put("round", round.getRound());
        return status;
    }

    /**
     * Legacy team talk endpoint (backward compatible).
     * Maps old types (calm, motivated, aggressive, no_pressure) to new PRE_MATCH phase.
     */
    @PostMapping("/teamTalk")
    public Map<String, Object> giveTeamTalk(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (teamTalkUsedThisRound) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Team talk already used this round.");
            return response;
        }

        String type = body.get("type");
        // Map legacy types to new types
        String mappedType = switch (type) {
            case "calm" -> "focus";
            case "motivated" -> "show_passion";
            case "aggressive" -> "expect_win";
            case "no_pressure" -> "no_pressure";
            default -> type;
        };

        long teamId = userContext.getTeamId(request);
        int season = Integer.parseInt(getCurrentSeason());
        Map<String, Object> result = teamTalkService.giveTeamTalk(teamId, "PRE_MATCH", mappedType, null, season);

        if (Boolean.TRUE.equals(result.get("success"))) {
            teamTalkUsedThisRound = true;
        }
        return result;
    }

    /**
     * Expanded team talk endpoint with phase support (PRE_MATCH, HALF_TIME, POST_MATCH).
     */
    @PostMapping("/teamTalkExpanded")
    public Map<String, Object> giveExpandedTeamTalk(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String phase = body.getOrDefault("phase", "PRE_MATCH");
        String type = body.get("type");
        String matchContext = body.get("matchContext"); // "winning", "losing", "drawing"
        long teamId = userContext.getTeamId(request);
        int season = Integer.parseInt(getCurrentSeason());

        Map<String, Object> result = teamTalkService.giveTeamTalk(teamId, phase, type, matchContext, season);

        // Legacy flag for PRE_MATCH
        if ("PRE_MATCH".equals(phase) && Boolean.TRUE.equals(result.get("success"))) {
            teamTalkUsedThisRound = true;
        }
        return result;
    }

    /**
     * Get available talk options for a phase.
     */
    @GetMapping("/teamTalkOptions/{phase}")
    public List<Map<String, Object>> getTeamTalkOptions(
            @PathVariable String phase,
            @RequestParam(required = false, defaultValue = "") String matchContext) {
        return teamTalkService.getAvailableTalks(phase, matchContext);
    }

    /**
     * Individual player talk.
     */
    @PostMapping("/playerTalk")
    public Map<String, Object> givePlayerTalk(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long teamId = userContext.getTeamId(request);
        long playerId = ((Number) body.get("playerId")).longValue();
        String type = (String) body.get("type");
        int season = Integer.parseInt(getCurrentSeason());
        return teamTalkService.giveIndividualTalk(teamId, playerId, type, season);
    }

    /**
     * Get available individual talk options.
     */
    @GetMapping("/playerTalkOptions")
    public List<Map<String, Object>> getPlayerTalkOptions() {
        return teamTalkService.getIndividualTalkOptions();
    }

    private void generateSeasonObjectives(int season) {
        List<Team> allTeams = teamRepository.findAll();
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        List<CompetitionTeamInfo> allCompTeamInfos = competitionTeamInfoRepository.findAll();

        for (Team team : allTeams) {
            List<SeasonObjective> teamObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            if (!teamObjectives.isEmpty()) continue;

            int reputation = team.getReputation();

            // Find which competitions this team is in (via CompetitionTeamInfo for current season, or TeamCompetitionDetail)
            Set<Long> teamCompIds = allCompTeamInfos.stream()
                    .filter(i -> i.getTeamId() == team.getId() && i.getSeasonNumber() == season)
                    .map(CompetitionTeamInfo::getCompetitionId)
                    .collect(Collectors.toSet());
            // Also check TeamCompetitionDetail (for season 1 before CompetitionTeamInfo is populated)
            allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId())
                    .forEach(d -> teamCompIds.add((long) d.getCompetitionId()));

            // Pre-compute predicted position for each competition this team is in
            // by ranking all teams in that competition by reputation
            Map<Long, Integer> predictedPositionByComp = new HashMap<>();
            Map<Long, Integer> teamCountByComp = new HashMap<>();
            for (Long compId : teamCompIds) {
                List<Team> teamsInComp = allTeams.stream()
                        .filter(t -> allCompTeamInfos.stream()
                                .anyMatch(i -> i.getTeamId() == t.getId() && i.getCompetitionId() == compId && i.getSeasonNumber() == season)
                                || allDetails.stream()
                                .anyMatch(d -> d.getTeamId() == t.getId() && d.getCompetitionId() == compId))
                        .sorted(Comparator.comparingInt(Team::getReputation).reversed())
                        .collect(Collectors.toList());
                teamCountByComp.put(compId, teamsInComp.size());
                for (int pos = 0; pos < teamsInComp.size(); pos++) {
                    if (teamsInComp.get(pos).getId() == team.getId()) {
                        predictedPositionByComp.put(compId, pos + 1); // 1-based
                        break;
                    }
                }
            }

            for (Competition comp : allCompetitions) {
                if (!teamCompIds.contains(comp.getId())) continue;

                SeasonObjective objective = new SeasonObjective();
                objective.setTeamId(team.getId());
                objective.setSeasonNumber(season);
                objective.setCompetitionId(comp.getId());
                objective.setCompetitionName(comp.getName());
                objective.setStatus("active");

                int numTeams = teamCountByComp.getOrDefault(comp.getId(), 12);
                if (numTeams == 0) numTeams = 12;
                int predicted = predictedPositionByComp.getOrDefault(comp.getId(), numTeams / 2);

                if (comp.getTypeId() == 1) { // First League — has relegation
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int target = Math.min(predicted + 2, numTeams);
                    target = Math.max(target, 1);
                    if (target <= 3) {
                        objective.setDescription("Finish in the top " + target);
                    } else if (target <= numTeams / 2) {
                        objective.setDescription("Finish in the top half (top " + target + ")");
                    } else if (target >= numTeams - 1) {
                        objective.setDescription("Avoid relegation");
                    } else {
                        objective.setDescription("Finish in position " + target + " or higher");
                    }
                    objective.setTargetValue(target);
                } else if (comp.getTypeId() == 3) { // Second League — promotion target, no relegation
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int target = Math.min(predicted + 2, numTeams);
                    target = Math.max(target, 1);
                    if (target <= 2) {
                        objective.setDescription("Promote to the first league (top " + target + ")");
                    } else if (target <= numTeams / 2) {
                        objective.setDescription("Finish in the top half (top " + target + ")");
                    } else {
                        objective.setDescription("Finish in position " + target + " or higher");
                    }
                    objective.setTargetValue(target);
                } else if (comp.getTypeId() == 2) { // Cup — scale realistically by predicted strength
                    objective.setObjectiveType("cup_round");
                    objective.setImportance("medium");
                    if (predicted <= 3) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the cup");
                    } else if (predicted <= 6) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the cup final");
                    } else if (predicted <= numTeams / 2) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the cup semi-final");
                    } else if (predicted <= 3 * numTeams / 4) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the cup quarter-final");
                    } else {
                        // Weak side: just being in the cup is fine, no realistic deep run
                        objective.setTargetValue(0);
                        objective.setDescription("Compete honorably in the cup");
                    }
                } else if (comp.getTypeId() == 4) { // LoC — scale by predicted strength
                    objective.setObjectiveType("european_round");
                    objective.setImportance("high");
                    if (predicted <= 2) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the League of Champions");
                    } else if (predicted <= 5) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the LoC final");
                    } else if (predicted <= 8) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the LoC semi-final");
                    } else if (predicted <= 11) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the LoC quarter-final");
                    } else {
                        // Mid/lower-table side qualifying through coefficient cascade —
                        // just getting past the group stage is the realistic ceiling.
                        objective.setTargetValue(0);
                        objective.setDescription("Qualify from the group stage");
                    }
                } else if (comp.getTypeId() == 5) { // Stars Cup — scale by predicted strength
                    objective.setObjectiveType("european_round");
                    objective.setImportance("medium");
                    if (predicted <= 3) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the Stars Cup final");
                    } else if (predicted <= 8) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the Stars Cup semi-final");
                    } else if (predicted <= 11) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the Stars Cup quarter-final");
                    } else {
                        objective.setTargetValue(0);
                        objective.setDescription("Qualify from the group stage");
                    }
                } else {
                    continue; // Unknown competition type
                }

                seasonObjectiveRepository.save(objective);
            }
        }
        System.out.println("=== SEASON OBJECTIVES GENERATED FOR SEASON " + season + " ===");
    }

    private void evaluateSeasonObjectives(int season) {
        List<SeasonObjective> allObjectives = seasonObjectiveRepository.findAll().stream()
                .filter(o -> o.getSeasonNumber() == season && "active".equals(o.getStatus()))
                .toList();

        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        List<CompetitionTeamInfo> allCompTeamInfos = competitionTeamInfoRepository.findAll();

        for (SeasonObjective objective : allObjectives) {
            if ("league_position".equals(objective.getObjectiveType())) {
                List<TeamCompetitionDetail> leagueDetails = allDetails.stream()
                        .filter(d -> d.getCompetitionId() == objective.getCompetitionId())
                        .sorted((o1, o2) -> {
                            if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                            if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                            return o2.getGoalsFor() - o1.getGoalsFor();
                        })
                        .toList();

                int position = 1;
                for (TeamCompetitionDetail detail : leagueDetails) {
                    if (detail.getTeamId() == objective.getTeamId()) {
                        objective.setActualValue(position);
                        objective.setStatus(position <= objective.getTargetValue() ? "achieved" : "failed");
                        break;
                    }
                    position++;
                }
            } else if ("cup_round".equals(objective.getObjectiveType()) || "european_round".equals(objective.getObjectiveType())) {
                // Find highest round reached (check current season's CompetitionTeamInfo, or match data)
                CompetitionTeamInfo info = allCompTeamInfos.stream()
                        .filter(i -> i.getTeamId() == objective.getTeamId()
                                && i.getCompetitionId() == objective.getCompetitionId()
                                && i.getSeasonNumber() == season)
                        .findFirst()
                        .orElse(null);

                if (info != null) {
                    int roundReached = (int) info.getRound();
                    objective.setActualValue(roundReached);
                    objective.setStatus(roundReached >= objective.getTargetValue() ? "achieved" : "failed");
                } else {
                    objective.setActualValue(0);
                    objective.setStatus("failed");
                }
            }
            seasonObjectiveRepository.save(objective);
        }

        // Record manager history for all teams
        recordManagerHistory(season, allDetails);

        // Manager firing check for human team
        checkManagerFiring(season);

        System.out.println("=== SEASON OBJECTIVES EVALUATED FOR SEASON " + season + " ===");
    }

    /**
     * Persist a MatchEvent "goal" row for the extra-time decider so the
     * match-report timeline never shows fewer goals than the final score
     * after a knockout tiebreaker bumps it.
     *
     * Picks an outfield player from the winning team via position+rating
     * weighting (so a striker is more likely to be credited than a defender)
     * and writes both a "goal" and an "assist" MatchEvent.
     */
    private void appendKnockoutWinnerGoal(long competitionId, int season, int roundNumber,
                                          long teamId1, long teamId2,
                                          long winnerTeamId, long loserTeamId) {
        List<Human> winners = humanRepository.findAllByTeamIdAndTypeId(winnerTeamId, TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired())
                .filter(h -> !"GK".equals(h.getPosition()))
                .toList();
        if (winners.isEmpty()) return;

        // Position weights mirror the live-sim attacker selection.
        double totalWeight = 0;
        double[] weights = new double[winners.size()];
        for (int i = 0; i < winners.size(); i++) {
            double posMul = switch (winners.get(i).getPosition()) {
                case "ST" -> 3.0;
                case "AMC", "AML", "AMR" -> 2.0;
                case "MC", "ML", "MR" -> 1.2;
                case "DC", "DL", "DR", "DM" -> 0.4;
                default -> 1.0;
            };
            weights[i] = winners.get(i).getRating() * posMul;
            totalWeight += weights[i];
        }
        Random rnd = new Random();
        double r = rnd.nextDouble() * totalWeight;
        double cum = 0;
        Human scorer = winners.get(winners.size() - 1);
        for (int i = 0; i < winners.size(); i++) {
            cum += weights[i];
            if (r < cum) { scorer = winners.get(i); break; }
        }

        MatchEvent goal = new MatchEvent();
        goal.setCompetitionId(competitionId);
        goal.setSeasonNumber(season);
        goal.setRoundNumber(roundNumber);
        goal.setTeamId1(teamId1);
        goal.setTeamId2(teamId2);
        goal.setMinute(120); // extra time
        goal.setEventType("goal");
        goal.setPlayerId(scorer.getId());
        goal.setPlayerName(scorer.getName());
        goal.setTeamId(winnerTeamId);
        goal.setDetails("Extra time winner");
        matchEventRepository.save(goal);
    }

    /**
     * Weight for picking an assister. Differs from the goal-weighting: creative
     * positions (wingers, attacking mids) get the highest share; pure forwards and
     * defenders contribute less; goalkeepers are excluded upstream.
     * Rating scales linearly here (not squared) so assists are more evenly spread
     * across the squad than goals.
     */
    private double getAssistWeight(Scorer scorer) {
        Map<String, Double> positionToValue = Map.of(
                "GK", 0D,
                "DL", 0.8,
                "DR", 0.8,
                "DC", 0.4,
                "ML", 2.5,
                "MR", 2.5,
                "MC", 2.0,
                "ST", 1.2);
        double posMul = positionToValue.getOrDefault(scorer.getPosition(), 1.0);
        double ratingFactor = Math.max(scorer.getRating(), 1.0);
        double w = posMul * ratingFactor;
        if (scorer.isSubstitute()) w /= 2;
        return Math.max(w, 0);
    }

    private void recordManagerHistory(int season, List<TeamCompetitionDetail> allDetails) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<CompetitionHistory> allCompHistory = competitionHistoryRepository.findAll().stream()
                .filter(h -> h.getSeasonNumber() == season)
                .toList();

        // Build a fast lookup: league competition ids (typeId 1 = league, 3 = second league)
        Set<Long> leagueCompetitionIds = allCompetitions.stream()
                .filter(c -> c.getTypeId() == 1 || c.getTypeId() == 3)
                .map(Competition::getId)
                .collect(Collectors.toSet());

        for (Team team : allTeams) {
            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst()
                    .orElse(null);

            if (manager == null) continue;

            // Find this team's LEAGUE detail (not just any competition — used to be a bug
            // where findFirst() could pick up a cup row and report cup stats as the season).
            TeamCompetitionDetail leagueDetail = allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId() && leagueCompetitionIds.contains(d.getCompetitionId()))
                    .findFirst()
                    .orElse(null);

            if (leagueDetail == null) continue;

            // Calculate league position
            long competitionId = leagueDetail.getCompetitionId();
            List<TeamCompetitionDetail> leagueStandings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == competitionId)
                    .sorted((o1, o2) -> {
                        if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                        if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                        return o2.getGoalsFor() - o1.getGoalsFor();
                    })
                    .toList();

            int leaguePosition = 1;
            for (TeamCompetitionDetail standing : leagueStandings) {
                if (standing.getTeamId() == team.getId()) break;
                leaguePosition++;
            }

            // Determine trophies won
            List<String> trophies = new ArrayList<>();
            if (leaguePosition == 1) {
                Competition comp = allCompetitions.stream()
                        .filter(c -> c.getId() == competitionId)
                        .findFirst().orElse(null);
                if (comp != null) trophies.add(comp.getName());
            }

            // Check cup wins - position 1 in competition history for cups
            for (CompetitionHistory ch : allCompHistory) {
                if (ch.getTeamId() == team.getId() && ch.getLastPosition() == 1) {
                    Competition comp = allCompetitions.stream()
                            .filter(c -> c.getId() == ch.getCompetitionId() && (c.getTypeId() == 2 || c.getTypeId() == 4 || c.getTypeId() == 5))
                            .findFirst().orElse(null);
                    if (comp != null) trophies.add(comp.getName());
                }
            }

            // Detect promotion/relegation
            boolean promoted = false;
            boolean relegated = false;
            Competition leagueComp = allCompetitions.stream()
                    .filter(c -> c.getId() == competitionId)
                    .findFirst().orElse(null);
            if (leagueComp != null) {
                int numTeams = leagueStandings.size();
                if (leagueComp.getTypeId() == 3 && leaguePosition <= 2) { // second league, top 2 promoted
                    promoted = true;
                }
                if (leagueComp.getTypeId() == 1 && leaguePosition >= numTeams - 1) { // first league, bottom 2 relegated
                    relegated = true;
                }
            }

            ManagerHistory mh = new ManagerHistory();
            mh.setManagerId(manager.getId());
            mh.setManagerName(manager.getName());
            mh.setTeamId(team.getId());
            mh.setTeamName(team.getName());
            mh.setSeasonNumber(season);
            mh.setGamesPlayed(leagueDetail.getGames());
            mh.setWins(leagueDetail.getWins());
            mh.setDraws(leagueDetail.getDraws());
            mh.setLosses(leagueDetail.getLoses());
            mh.setGoalsFor(leagueDetail.getGoalsFor());
            mh.setGoalsAgainst(leagueDetail.getGoalsAgainst());
            mh.setLeaguePosition(leaguePosition);
            mh.setTrophiesWon(String.join(",", trophies));
            mh.setPromoted(promoted);
            mh.setRelegated(relegated);
            managerHistoryRepository.save(mh);

            // Update manager reputation at end of season (scale 0-10000)
            double repChange = 0;

            // League position bonuses
            if (leaguePosition == 1) repChange += 100;
            else if (leaguePosition == 2) repChange += 40;
            else if (leaguePosition == 3) repChange += 20;

            // Bottom of the table penalty
            int numTeams = leagueStandings.size();
            if (leaguePosition == numTeams) repChange -= 50;
            else if (leaguePosition >= numTeams - 2) repChange -= 25;

            // Trophy bonuses based on competition type
            for (String trophy : trophies) {
                String lower = trophy.toLowerCase();
                if (lower.contains("champions") || lower.contains("loc") || lower.contains("league of champions")) {
                    repChange += 500; // League of Champions
                } else if (lower.contains("stars")) {
                    repChange += 200; // Stars Cup
                } else if (lower.contains("cup")) {
                    repChange += 75; // Domestic cup
                }
            }

            // Promotion/relegation
            if (promoted) repChange += 50;
            if (relegated) repChange -= 200;

            // Failed objectives
            List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            for (SeasonObjective obj : objectives) {
                if ("failed".equals(obj.getStatus())) {
                    if ("critical".equals(obj.getImportance())) repChange -= 50;
                    else if ("high".equals(obj.getImportance())) repChange -= 25;
                    else repChange -= 10;
                }
            }

            double newRep = Math.max(0, Math.min(10000, manager.getManagerReputation() + repChange));
            manager.setManagerReputation((int) newRep);
            humanRepository.save(manager);
        }

        System.out.println("=== MANAGER HISTORY RECORDED FOR SEASON " + season + " ===");
    }

    /**
     * Update manager reputation after each match (scale 0-10000).
     * Normal win: +2, big upset: +10. Normal loss: -2, embarrassing loss: -10.
     */
    public void updateManagerReputationAfterMatch(long teamId1, long teamId2, int score1, int score2) {
        Team team1 = teamRepository.findById(teamId1).orElse(null);
        Team team2 = teamRepository.findById(teamId2).orElse(null);
        if (team1 == null || team2 == null) return;

        List<Human> managers1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE);
        List<Human> managers2 = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE);

        if (!managers1.isEmpty()) {
            double change = calculateMatchRepChange(score1, score2, team1.getReputation(), team2.getReputation());
            Human mgr = managers1.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            humanRepository.save(mgr);
        }

        if (!managers2.isEmpty()) {
            double change = calculateMatchRepChange(score2, score1, team2.getReputation(), team1.getReputation());
            Human mgr = managers2.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            humanRepository.save(mgr);
        }
    }

    public double calculateMatchRepChange(int myGoals, int oppGoals, int myTeamRep, int oppTeamRep) {
        // repDiff positive = opponent stronger
        double repDiff = oppTeamRep - myTeamRep;
        // strengthFactor: 0.2x (much weaker opp) to 5x (much stronger opp)
        double strengthFactor = Math.max(0.2, Math.min(5.0, 1.0 + repDiff / 50.0));

        if (myGoals > oppGoals) {
            // Win: base +2, big upset (opp 50+ rep stronger) base +10
            double base = repDiff > 50 ? 10.0 : 2.0;
            return base * strengthFactor;
        } else if (myGoals == oppGoals) {
            // Draw vs stronger: small gain, vs weaker: small loss
            return repDiff > 0 ? 1.0 * strengthFactor : -1.0;
        } else {
            // Loss: base -2, embarrassing (opp 50+ rep weaker) base -10
            double base = repDiff < -50 ? -10.0 : -2.0;
            // Invert factor: losing to weaker = bigger penalty
            double lossFactor = Math.max(0.2, Math.min(5.0, 1.0 - repDiff / 50.0));
            return base * lossFactor;
        }
    }

    private void checkManagerFiring(int season) {
        for (long humanTeamId : userContext.getAllHumanTeamIds()) {
            checkManagerFiringForTeam(humanTeamId, season);
        }

        // Fire AI managers that performed very badly
        fireAIManagers(season);
    }

    private void checkManagerFiringForTeam(long humanTeamId, int season) {
        List<SeasonObjective> humanObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(humanTeamId, season);
        if (humanObjectives.isEmpty()) return;

        int firingScore = 0;
        for (SeasonObjective obj : humanObjectives) {
            if (!"failed".equals(obj.getStatus())) continue;

            int weight = "critical".equals(obj.getImportance()) ? 3 : "high".equals(obj.getImportance()) ? 2 : 1;

            if ("league_position".equals(obj.getObjectiveType())) {
                int miss = obj.getActualValue() - obj.getTargetValue();
                firingScore += weight * Math.min(miss, 5);
            } else {
                int miss = obj.getTargetValue() - obj.getActualValue();
                firingScore += weight * Math.min(miss, 3);
            }
        }

        String title, content, category = "board";

        if (firingScore >= 12) {
            // Actually fire the human manager
            title = "You Have Been Sacked!";
            content = "The board has lost all confidence in your ability to manage this club. "
                    + "You have been relieved of your duties with immediate effect. "
                    + "Check the available jobs to find a new position.";
            // Set fired flag on User(s) managing this team, preserve lastTeamId for inbox
            List<User> usersWithTeam = userRepository.findAllByTeamId(humanTeamId);
            for (User user : usersWithTeam) {
                user.setFired(true);
                user.setLastTeamId(humanTeamId);
                user.setTeamId(null);
                userRepository.save(user);
            }

            // Also set managerFired on GameCalendar so GameAdvanceService pauses
            List<GameCalendar> nextCalendars = gameCalendarRepository.findBySeason(season + 1);
            if (!nextCalendars.isEmpty()) {
                GameCalendar cal = nextCalendars.get(0);
                cal.setManagerFired(true);
                gameCalendarRepository.save(cal);
            } else {
                List<GameCalendar> calendars = gameCalendarRepository.findBySeason(season);
                if (!calendars.isEmpty()) {
                    GameCalendar cal = calendars.get(0);
                    cal.setManagerFired(true);
                    gameCalendarRepository.save(cal);
                }
            }

            // Remove human manager from team
            Human humanManager = humanRepository.findAllByTeamIdAndTypeId(humanTeamId, TypeNames.MANAGER_TYPE)
                    .stream().findFirst().orElse(null);
            if (humanManager != null) {
                humanManager.setManagerReputation(Math.max(0, humanManager.getManagerReputation() - 100));
                humanManager.setTeamId(0L);
                humanRepository.save(humanManager);
            }

            System.out.println("=== HUMAN MANAGER FIRED - Game paused for job selection ===");
        } else if (firingScore >= 8) {
            title = "Board Disappointed - Final Warning";
            content = "The board is extremely disappointed with your performance this season. "
                    + "You have failed to meet critical objectives. "
                    + "Another season like this and you will be relieved of your duties.";
        } else if (firingScore >= 5) {
            title = "Board Concerns";
            content = "The board has expressed concerns about your management. "
                    + "Several objectives were not met. You are expected to improve next season.";
        } else if (firingScore > 0) {
            title = "Board Review";
            content = "The board acknowledges a mixed season. Some objectives were not fully met, "
                    + "but they remain supportive of your vision.";
        } else {
            title = "Board Praise";
            content = "The board is delighted with your performance this season! "
                    + "All objectives have been met. Keep up the excellent work!";
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(humanTeamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(50);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    private void fireAIManagers(int season) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);

        for (Team team : allTeams) {
            if (userContext.isHumanTeam(team.getId())) continue;

            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst().orElse(null);

            if (manager == null) continue;

            List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            int firingScore = 0;
            for (SeasonObjective obj : objectives) {
                if (!"failed".equals(obj.getStatus())) continue;
                int weight = "critical".equals(obj.getImportance()) ? 3 : "high".equals(obj.getImportance()) ? 2 : 1;
                if ("league_position".equals(obj.getObjectiveType())) {
                    firingScore += weight * Math.min(obj.getActualValue() - obj.getTargetValue(), 5);
                } else {
                    firingScore += weight * Math.min(obj.getTargetValue() - obj.getActualValue(), 3);
                }
            }

            if (firingScore >= 12) {
                // Fire the AI manager and create a replacement
                manager.setTeamId(0L);
                manager.setRetired(true);
                humanRepository.save(manager);

                Human newManager = new Human();
                newManager.setName(compositeNameGenerator.generateName(1L));
                newManager.setTypeId(TypeNames.MANAGER_TYPE);
                newManager.setTeamId(team.getId());
                newManager.setManagerReputation(team.getReputation() / 3);
                newManager.setAge(35 + new Random().nextInt(20));
                newManager.setSeasonCreated(season);
                newManager.setMorale(100D);
                newManager.setFitness(100D);
                newManager.setRating(0);
                String[] kit = tacticService.buildManagerTacticKit((int) newManager.getRating(), new Random());
                newManager.setTacticStyle(kit[0]);
                newManager.setKnownTactics(kit[1]);
                humanRepository.save(newManager);

                System.out.println("=== AI MANAGER FIRED: " + manager.getName() + " from " + team.getName()
                        + " | Replaced by: " + newManager.getName() + " ===");
            }
        }
    }

    public void handleContractExpiries(int newSeason) {
        Random random = new Random();
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            if (player.getTeamId() == null) continue;
            if (player.getContractEndSeason() <= 0) continue; // skip players without contract data
            if (player.getContractEndSeason() > newSeason) continue; // contract not yet expired

            long previousTeamId = player.getTeamId();

            if (userContext.isHumanTeam(player.getTeamId())) {
                // Human team: player leaves + send inbox notification
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(player.getTeamId());
                inbox.setSeasonNumber(newSeason);
                inbox.setRoundNumber(1);
                inbox.setTitle("Player Left - Contract Expired");
                inbox.setContent(player.getName() + " (" + player.getPosition() + ", Rating "
                        + Math.round(player.getRating()) + ") has left the club as their contract expired.");
                inbox.setCategory("contract");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);

                // Update salary budget
                Team team = teamRepository.findById(player.getTeamId()).orElse(null);
                if (team != null) {
                    team.setSalaryBudget(Math.max(0, team.getSalaryBudget() - player.getWage()));
                    teamRepository.save(team);
                }

                // Player becomes free agent
                player.setTeamId(null);
                player.setContractEndSeason(0);
                humanRepository.save(player);
            } else {
                // AI team: 50% auto-renew, 50% become free agent
                if (random.nextBoolean()) {
                    player.setContractEndSeason(newSeason + random.nextInt(2, 5));
                    player.setWage((long) (player.getRating() * 50));
                    humanRepository.save(player);
                } else {
                    player.setTeamId(null);
                    humanRepository.save(player);
                }
            }
        }
    }

    /**
     * Simulates a matchday for a specific competition.
     * Called by GameAdvanceService when processing MATCH events from the calendar.
     *
     * IMPORTANT: matchday is 1-based (from CalendarEvent), but competition rounds differ:
     *   LoC (typeId 4): 11 matchdays → rounds 0-10 (matchday - 1 = round)
     *     matchday 1 = round 0 (preliminary), matchday 2 = round 1 (qualifying),
     *     matchdays 3-8 = rounds 2-7 (groups), matchdays 9-11 = rounds 8-10 (QF/SF/Final)
     *   Stars Cup (typeId 5): 10 matchdays → rounds 1-10 (matchday = round)
     *     matchdays 1-6 = rounds 1-6 (groups), matchday 7 = round 7 (playoff),
     *     matchdays 8-10 = rounds 8-10 (QF/SF/Final)
     *   Cup (typeId 2): matchday = round (1-based knockout)
     */
    @Transactional
    public void simulateMatchday(long competitionId, int matchday, int season) {
        long _tMatchdayStart = System.nanoTime();
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return;

        int typeId = (int) competition.getTypeId();
        String compIdStr = String.valueOf(competitionId);

        // Map matchday (1-based) to competition round
        int round;
        if (typeId == 4) {
            round = matchday - 1; // LoC: matchday 1 = round 0
        } else {
            round = matchday; // Stars Cup & Cup: matchday = round
        }
        String roundStr = String.valueOf(round);

        System.out.println("=== simulateMatchday: comp=" + competitionId + " typeId=" + typeId
                + " matchday=" + matchday + " → round=" + round + " season=" + season + " ===");

        try {

        // Guard: skip if this round was already simulated
        List<CompetitionTeamInfoDetail> existing = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, round, season);
        if (!existing.isEmpty()) {
            System.out.println("Round " + round + " already simulated, skipping");
            return;
        }

        // === LoC (typeId 4) ===
        if (typeId == 4) {
            if (round == 0) {
                // Preliminary: knockout draw + simulate + losers to Stars Cup
                this.getFixturesForRound(compIdStr, roundStr);
                this.simulateRound(compIdStr, roundStr);
                assignLocLosersToStarsCup(competitionId, 0);
                return;
            }
            if (round == 1) {
                // Qualifying: knockout draw + simulate + losers to Stars Cup
                this.getFixturesForRound(compIdStr, roundStr);
                this.simulateRound(compIdStr, roundStr);
                assignLocLosersToStarsCup(competitionId, 1);
                return;
            }
            if (round >= 2 && round <= 7) {
                // Group stage
                if (round == 2) {
                    // First group matchday: draw groups + generate all group fixtures
                    drawEuropeanGroups(competitionId, 2);
                    resetEuropeanStats(competitionId);
                    generateGroupStageFixtures(competitionId);
                    // Assign calendar days for remaining group matchdays
                    for (int md = matchday + 1; md <= matchday + 5; md++) {
                        fixtureSchedulingService.assignMatchDayForNewRound(competitionId, md, season);
                    }
                }
                this.simulateRound(compIdStr, roundStr);
                if (round == 7) {
                    // Last group matchday: qualify top 2 to QF, 3rd to Stars Cup playoff
                    qualifyFromGroupStage(competitionId);
                }
                return;
            }
            // Knockout rounds 8-10
            this.getFixturesForRound(compIdStr, roundStr);
            this.simulateRound(compIdStr, roundStr);
            // Draw next knockout round
            if (round < 10) {
                int nextRound = round + 1;
                this.getFixturesForRound(compIdStr, String.valueOf(nextRound));
                fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday + 1, season);
            }
            return;
        }

        // === Stars Cup (typeId 5) ===
        if (typeId == 5) {
            if (round >= 1 && round <= 6) {
                // Group stage
                if (round == 1) {
                    // First group matchday: draw groups + generate all group fixtures
                    drawEuropeanGroups(competitionId, 1);
                    resetEuropeanStats(competitionId);
                    generateGroupStageFixtures(competitionId);
                    for (int md = matchday + 1; md <= matchday + 5; md++) {
                        fixtureSchedulingService.assignMatchDayForNewRound(competitionId, md, season);
                    }
                }
                this.simulateRound(compIdStr, roundStr);
                if (round == 6) {
                    // Last group matchday: winners to QF, runners-up to playoff
                    qualifyFromStarsCupGroupStage(competitionId);
                }
                return;
            }
            // Knockout rounds 7-10 (7 = playoff, 8 = QF, 9 = SF, 10 = Final)
            this.getFixturesForRound(compIdStr, roundStr);
            this.simulateRound(compIdStr, roundStr);
            // Draw next knockout round
            if (round < 10) {
                int nextRound = round + 1;
                this.getFixturesForRound(compIdStr, String.valueOf(nextRound));
                fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday + 1, season);
            }
            return;
        }

        // === League (typeId 1) / Second League (typeId 3) ===
        // Fixtures are pre-generated at season start. Calling getFixturesForRound
        // here would duplicate every round in competition_team_info_match and cause
        // unique-constraint violations on match_stats during simulateRound.
        if (typeId == 1 || typeId == 3) {
            this.simulateRound(compIdStr, roundStr);
            return;
        }

        // === Cup (typeId 2) ===
        // Bracket is fully pre-generated at season start by CupBracketService — no
        // per-round draw, no "draw next round" step. We just simulate this round;
        // simulateRound propagates winners into the pre-created next-round slots
        // via cupBracketService.propagateWinner().
        this.simulateRound(compIdStr, roundStr);

        // Still need to assign a calendar day for the next round if there is one,
        // so the existing calendar/event flow knows when to fire it.
        int numTeams = getTeamCountForCompetition(competitionId);
        int maxRounds = Math.max(1, (int) Math.ceil(Math.log(numTeams) / Math.log(2)));
        if (round < maxRounds) {
            fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday + 1, season);
        }

        } finally {
            long totalMs = (System.nanoTime() - _tMatchdayStart) / 1_000_000;
            System.out.println(String.format(
                    "<<< simulateMatchday comp=%d matchday=%d DONE in %dms",
                    competitionId, matchday, totalMs));
        }
    }

    /**
     * Gets the human team's match result AFTER all matches have been simulated.
     * Called once, only for the human team's competition.
     */
    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Long> humanTeamIds = userContext.getAllHumanTeamIds();
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return results;

        List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, matchday, season);
        for (CompetitionTeamInfoDetail d : details) {
            // Skip any human team's match (they already see it in the popup)
            if (humanTeamIds.contains(d.getTeam1Id()) || humanTeamIds.contains(d.getTeam2Id())) continue;
            if (d.getScore() == null || d.getScore().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("competitionName", competition.getName());
            m.put("team1Name", d.getTeamName1());
            m.put("team2Name", d.getTeamName2());
            m.put("score", d.getScore());
            results.add(m);
        }
        return results;
    }

    /**
     * Finalize an interactive live match — runs ALL the post-match work that
     * {@code simulateMatchday} skipped (scorers, stats, injuries, standings,
     * coefficient points, match report, manager reputation, suspensions, news,
     * post-match press conference).
     *
     * <p>Called by {@code POST /match/live/{key}/commit} when the frontend has
     * finished polling the engine to full time. The session's final scores
     * become the source of truth for the round — any manual sub the user made
     * during playback is now baked into the standings.
     *
     * <p>Idempotent: a session already marked as {@code committed} returns
     * an unchanged result map.
     */
    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        LiveMatchSession session = liveMatchSimulationService.getSession(liveKey);
        if (session == null) {
            throw new RuntimeException("No interactive session for key=" + liveKey);
        }
        if (!session.isFinished()) {
            throw new RuntimeException("Cannot commit: match is still in progress (currentMinute < totalMinutes).");
        }
        if (session.isCommitted()) {
            // Idempotent — return a result map without re-running side effects.
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("alreadyCommitted", true);
            already.put("homeScore", session.getHomeScore());
            already.put("awayScore", session.getAwayScore());
            return already;
        }

        long teamId1 = session.getTeamId1();
        long teamId2 = session.getTeamId2();
        long _competitionId = session.getCompetitionId();
        long _roundId = session.getRound();
        int season = session.getSeason();
        int teamScore1 = session.getHomeScore();
        int teamScore2 = session.getAwayScore();
        double teamPower1 = session.getDeferredTeamPower1();
        double teamPower2 = session.getDeferredTeamPower2();
        String tactic1 = session.getDeferredTactic1();
        String tactic2 = session.getDeferredTactic2();
        boolean knockout = session.isDeferredKnockout();

        // Knockout extra-time decider (mirrors the live path in simulateMatchday)
        if (knockout && teamScore1 == teamScore2) {
            double total = teamPower1 + teamPower2;
            double winChance = total > 0 ? (teamPower1 / total) * 0.3 + 0.35 : 0.5;
            boolean homeWins = new Random().nextDouble() < winChance;
            long winnerTeamId, loserTeamId;
            if (homeWins) { session.bumpHomeScore(); teamScore1++; winnerTeamId = teamId1; loserTeamId = teamId2; }
            else          { session.bumpAwayScore(); teamScore2++; winnerTeamId = teamId2; loserTeamId = teamId1; }
            appendKnockoutWinnerGoal(_competitionId, season, (int) _roundId, teamId1, teamId2, winnerTeamId, loserTeamId);
        }

        // Scorer tracking + match stats from the live data
        getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId);
        getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId);
        matchSimulationService.persistLiveMatchStats(
                _competitionId, season, (int) _roundId, teamId1, teamId2,
                session.asLiveMatchData(), teamPower1, teamPower2);

        // The same post-match work the legacy path runs inline
        processInjuriesForTeam(teamId1);
        processInjuriesForTeam(teamId2);
        updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, teamId2);
        updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, teamId1);
        awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
        generateMatchReport(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
        updateManagerReputationAfterMatch(teamId1, teamId2, teamScore1, teamScore2);

        // Detail record (the simulateMatchday loop skipped this for interactive
        // matches). Standings + results page now show the real final score.
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setCompetitionId(_competitionId);
        detail.setRoundId(_roundId);
        detail.setTeam1Id(teamId1);
        detail.setTeam2Id(teamId2);
        detail.setTeamName1(matchSimulationOrchestrator.roundTeamName(teamId1));
        detail.setTeamName2(matchSimulationOrchestrator.roundTeamName(teamId2));
        detail.setScore(teamScore1 + " - " + teamScore2);
        detail.setSeasonNumber((long) season);
        competitionTeamInfoDetailRepository.save(detail);

        session.markCommitted();

        // Build result map for the frontend — includes the match result so the
        // FE can chain into the post-match press conference flow.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("homeScore", teamScore1);
        result.put("awayScore", teamScore2);
        result.put("homeTeamId", teamId1);
        result.put("awayTeamId", teamId2);
        result.put("competitionId", _competitionId);
        result.put("matchday", _roundId);
        result.put("season", season);
        // The post-match PC + suspensions + news are wired up by the caller
        // (MatchController) so this method stays purely "finalize the engine
        // state" without coupling to GameAdvanceService internals.
        return result;
    }

    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return result;

        List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, matchday, season)
                .stream()
                .filter(d -> d.getTeam1Id() == humanTeamId || d.getTeam2Id() == humanTeamId)
                .toList();

        if (!details.isEmpty()) {
            CompetitionTeamInfoDetail detail = details.get(0);
            result.put("competitionName", competition.getName());
            result.put("competitionId", competitionId);
            result.put("matchday", matchday);
            result.put("team1Id", detail.getTeam1Id());
            result.put("team2Id", detail.getTeam2Id());
            result.put("team1Name", detail.getTeamName1());
            result.put("team2Name", detail.getTeamName2());
            result.put("score", detail.getScore());
            result.put("isHome", detail.getTeam1Id() == humanTeamId);

            List<MatchEvent> matchEvents = matchEventRepository
                    .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                            competitionId, season, matchday, detail.getTeam1Id(), detail.getTeam2Id());
            matchEvents.sort(java.util.Comparator.comparingInt(MatchEvent::getMinute));

            List<Map<String, Object>> eventsList = new ArrayList<>();
            for (MatchEvent me : matchEvents) {
                Map<String, Object> eventMap = new LinkedHashMap<>();
                eventMap.put("minute", me.getMinute());
                eventMap.put("eventType", me.getEventType());
                eventMap.put("playerName", me.getPlayerName());
                eventMap.put("teamId", me.getTeamId());
                eventMap.put("details", me.getDetails());
                eventsList.add(eventMap);
            }
            result.put("matchEvents", eventsList);
        }

        return result;
    }
}
