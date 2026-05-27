package com.footballmanagergamesimulator.controller;
import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import org.springframework.transaction.annotation.Transactional;
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
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
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
    PersonalizedTacticRepository personalizedTacticRepository;
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
    MatchEventRepository matchEventRepository;
    @Autowired
    FixtureSchedulingService fixtureSchedulingService;
    @Autowired
    GameCalendarRepository gameCalendarRepository;
    @Autowired
    UserContext userContext;
    @Autowired
    UserRepository userRepository;
    @Autowired
    LiveMatchSimulationService liveMatchSimulationService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.StaffService staffService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.SeasonTransitionService seasonTransitionService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationService matchSimulationService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired
    com.footballmanagergamesimulator.service.LineupRatingService lineupRatingService;



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

    /** @deprecated thin delegate to {@link TransferMarketService#canBeTransfered};
     *  remaining for callers that still go through the controller back-ref. */
    @Deprecated
    public boolean canBeTransfered(PlayerTransferView playerTransferView, BuyPlanTransferView clubPlan, TransferPlayer desiredPlayer) {
        return transferMarketService.canBeTransfered(playerTransferView, clubPlan, desiredPlayer);
    }

    /** @deprecated thin delegate to {@link TransferMarketService#generateAiOffersForHumanPlayers};
     *  remaining for callers that still go through the controller back-ref. */
    @Deprecated
    public void generateAiOffersForHumanPlayers(Team aiTeam, BuyPlanTransferView buyPlanTransferView) {
        transferMarketService.generateAiOffersForHumanPlayers(aiTeam, buyPlanTransferView);
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



    // ============================================================
    //  European competition methods — delegate to
    //  EuropeanCompetitionService. Kept as thin wrappers so the
    //  controller's internal callers (play() orchestration,
    //  simulateRound dispatch) don't have to change.
    // ============================================================

    private void drawEuropeanGroups(long competitionId, int groupStageRound) {
        europeanCompetitionService.drawEuropeanGroups(competitionId, groupStageRound);
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

    /** @deprecated thin delegate to {@link SeasonObjectiveService#generateSeasonObjectives};
     *  remaining for the {@code initializeRound()} bootstrap path. */
    @Deprecated
    public void generateSeasonObjectives(int season) {
        seasonObjectiveService.generateSeasonObjectives(season);
    }

    /** @deprecated thin delegate to {@link SeasonObjectiveService#evaluateSeasonObjectives};
     *  remaining for callers that still go through the controller back-ref. */
    @Deprecated
    public void evaluateSeasonObjectives(int season) {
        seasonObjectiveService.evaluateSeasonObjectives(season);
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
        lineupRatingService.getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId);
        lineupRatingService.getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId);
        matchSimulationService.persistLiveMatchStats(
                _competitionId, season, (int) _roundId, teamId1, teamId2,
                session.asLiveMatchData(), teamPower1, teamPower2);

        // The same post-match work the legacy path runs inline
        processInjuriesForTeam(teamId1);
        processInjuriesForTeam(teamId2);
        updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, teamId2);
        updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, teamId1);
        europeanCompetitionService.awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
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
