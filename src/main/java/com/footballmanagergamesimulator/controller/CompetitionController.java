package com.footballmanagergamesimulator.controller;
import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.FixtureSchedulingService;
import com.footballmanagergamesimulator.service.LeagueConfigService;
import com.footballmanagergamesimulator.service.BootstrapService;
import com.footballmanagergamesimulator.service.SeasonObjectiveService;
import com.footballmanagergamesimulator.service.SquadGenerationService;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TeamPostMatchService;
import com.footballmanagergamesimulator.service.TeamTalkService;
import com.footballmanagergamesimulator.service.TransferValueCalculator;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.*;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
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
    TeamFacilitiesRepository _teamFacilitiesRepository;
    @Autowired
    TacticService tacticService;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    SquadGenerationService squadGenerationService;
    @Autowired
    EuropeanCompetitionService europeanCompetitionService;
    @Autowired
    TeamPostMatchService teamPostMatchService;
    @Autowired
    TransferMarketService transferMarketService;
    @Autowired
    SeasonObjectiveService seasonObjectiveService;
    @Autowired
    BootstrapService bootstrapService;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    FixtureSchedulingService fixtureSchedulingService;
    @Autowired
    GameCalendarRepository gameCalendarRepository;
    @Autowired
    UserContext userContext;
    @Autowired
    UserRepository userRepository;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.StaffService staffService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.SeasonTransitionService seasonTransitionService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;

    Round round;

    /** Exposes the cached {@code round} field for the season-transition service.
     *  Services hold mutated state through this reference so {@link #getCurrentSeason()}
     *  (which reads the field directly) stays in sync with what they save. */
    public Round getRoundCache() {
        return round;
    }

    // managerFired is now per-user on User.fired, not a global boolean
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
        bootstrapService.initialization();

        // Generate fixtures for all leagues (was previously in play() round==1 block)
        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3);
        leagueCompetitionIds.addAll(secondLeagueCompetitionIds);
        for (Long competitionId : leagueCompetitionIds)
            this.getFixturesForRound(String.valueOf(competitionId), "1");

        // Generate calendar AFTER league fixtures exist so updateMatchDays can set the day field
        fixtureSchedulingService.generateSeasonCalendar(1);

        seasonObjectiveService.generateSeasonObjectives((int) round.getSeason());

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
                ScorerLeaderboardEntry scorerLeaderboardEntry =
                        com.footballmanagergamesimulator.service.SeasonTransitionService.resetCurrentSeasonStats(optionalScorerLeaderboardEntry);
                scorerLeaderboardRepository.save(scorerLeaderboardEntry);
            }
        }

        System.out.println("=== Initialization complete: teams, players, fixtures, and scorers created ===");

        // Build proper full cup brackets for season 1 (wipes the legacy per-league
        // cup CompetitionTeamInfo records and replaces with bracket-aware fixtures).
        seasonTransitionService.regenerateAllCupBrackets((int) round.getSeason());
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
        List<Long> sortedLeagueIds = europeanCompetitionService.getLeagueIdsSortedByCoefficient();

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
        List<Long> sortedLeagueIds = europeanCompetitionService.getLeagueIdsSortedByCoefficient();
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
        return transferMarketService.isOpen();
    }

    public void setTransferWindowOpen(boolean open) {
        transferMarketService.setOpen(open);
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

    /** Phase 1 — final standings + relegation/promotion + AI transfers/loans
     *  + objectives + transfer window open. Body lives in
     *  {@link SeasonTransitionService#processEndOfSeason(int)}; kept here as a
     *  delegate so the {@code GameAdvanceService} call site stays unchanged. */
    public void processEndOfSeason(int season) {
        seasonTransitionService.processEndOfSeason(season);
    }

    /** Phase 2 — aging, retirement, regens, new fixtures, scorers init, cup
     *  brackets. Body lives in
     *  {@link SeasonTransitionService#processNewSeasonSetup(int)}. */
    public void processNewSeasonSetup(int season) {
        seasonTransitionService.processNewSeasonSetup(season);
    }

    /** Thin delegate to {@link SeasonTransitionService#handleContractExpiries(int)};
     *  kept here so {@code GameAdvanceService} (CONTRACT_EXPIRY_CHECK calendar event)
     *  can keep calling through the controller. */
    public void handleContractExpiries(int newSeason) {
        seasonTransitionService.handleContractExpiries(newSeason);
    }

    /** Per-match manager reputation update, called from {@link
     *  com.footballmanagergamesimulator.service.MatchSimulationOrchestrator} via
     *  the lazy controller back-reference. (TODO: candidate for
     *  {@link TeamPostMatchService} extraction in a later slice.) */
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
                europeanCompetitionService.drawEuropeanKnockoutSeeded(_competitionId, _roundId, participants);
                return;
            }
            if (starsCupIdsForDraw.contains(_competitionId) && _roundId == 7) {
                // Stars Cup playoff: LoC 3rd place (seeded) vs SC runners-up (unseeded)
                europeanCompetitionService.drawStarsCupPlayoffSeeded(_competitionId, _roundId, participants);
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


    // adjustTeamPowerByTacticalProperties + getBestElevenRatingByTactic +
    // getScorersForTeam + getAssistWeight + getManagerMoraleMultiplier extracted
    // to LineupRatingService. Orchestrator + interactive-live-match commit path
    // call the service directly — no controller delegates remain.

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

    // Only the 6-arg updateTeam wrapper around TeamPostMatchService still has an
    // internal caller; the other 10 wrappers + getManagerMoraleMultiplier were
    // pruned after the Stage-1 and LineupRatingService extractions.

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference, long opponentTeamId) {
        teamPostMatchService.updateTeam(teamId, competitionId, scoreHome, scoreAway, teamPowerDifference, opponentTeamId);
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
            List<Long> sortedLeagueIds = europeanCompetitionService.getLeagueIdsSortedByCoefficient();
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

    public int getTeamCountForCompetition(long competitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        return (int) competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .map(CompetitionTeamInfo::getTeamId)
                .distinct()
                .count();
    }


    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        return europeanCompetitionService.getEuropeanSummary();
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
            europeanCompetitionService.assignEuropeanAllocation(entry, rank);
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



    // European competition delegates removed — internal callers
    // (simulateMatchday dispatch + getFixturesForRound knockout draws)
    // call europeanCompetitionService.X() directly.

    public Set<Long> getCompetitionIdsByCompetitionType(int competitionTypeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == competitionTypeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    // getHumanTeamId() removed - replaced by userContext.getTeamId(request), userContext.isHumanTeam(teamId), or userContext.getAllHumanTeamIds()
    // getRound() removed - getRoundCache() is the canonical accessor

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

    /** Thin delegate so {@link com.footballmanagergamesimulator.service.GameAdvanceService}
     *  call sites stay stable. Body lives in {@link MatchSimulationOrchestrator#simulateMatchday}. */
    public void simulateMatchday(long competitionId, int matchday, int season) {
        matchSimulationOrchestrator.simulateMatchday(competitionId, matchday, season);
    }

    /** Thin delegate so {@link com.footballmanagergamesimulator.service.GameAdvanceService}
     *  call sites stay stable. Body lives in {@link MatchSimulationOrchestrator#getAllMatchResults}. */
    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        return matchSimulationOrchestrator.getAllMatchResults(competitionId, matchday, season);
    }

    /** Thin delegate so {@code MatchController#commit} stays stable. Body
     *  lives in {@link MatchSimulationOrchestrator#finalizeInteractiveLiveMatch}. */
    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        return matchSimulationOrchestrator.finalizeInteractiveLiveMatch(liveKey);
    }

    /** Thin delegate so {@link com.footballmanagergamesimulator.service.GameAdvanceService}
     *  call sites stay stable. Body lives in {@link MatchSimulationOrchestrator#getHumanMatchResult}. */
    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        return matchSimulationOrchestrator.getHumanMatchResult(competitionId, matchday, season, humanTeamId);
    }

}
