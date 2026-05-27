package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.algorithms.*;
import com.footballmanagergamesimulator.frontend.*;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.*;
import com.footballmanagergamesimulator.user.*;
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
    TransferMarketService transferMarketService;
    @Autowired
    SeasonObjectiveService seasonObjectiveService;
    @Autowired
    BootstrapService bootstrapService;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    FixtureSchedulingService fixtureSchedulingService;
    @Autowired
    UserContext userContext;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.StaffService staffService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    com.footballmanagergamesimulator.service.SeasonTransitionService seasonTransitionService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired
    com.footballmanagergamesimulator.service.JobOfferService jobOfferService;
    @Autowired
    com.footballmanagergamesimulator.service.CompetitionDisplayService competitionDisplayService;
    @Autowired
    com.footballmanagergamesimulator.service.CupBracketService cupBracketService;

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
        return competitionDisplayService.getLeaguesOverview(topN);
    }

    @GetMapping("/cupsOverview")
    public Map<String, Object> getCupsOverview() {
        return cupBracketService.getCupsOverview();
    }

    @GetMapping("/cupBracket/{cupId}/{season}")
    public Map<String, Object> getCupBracket(@PathVariable long cupId,
                                              @PathVariable int season) {
        return cupBracketService.getCupBracket(cupId, season);
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
        return jobOfferService.getAvailableJobs();
    }

    @PostMapping("/acceptJob")
    public String acceptJob(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        User currentUser = userContext.getUserOrNull(request);
        return jobOfferService.acceptJob(currentUser, body.get("teamId"));
    }

    // Phase 1 — final standings + relegation/promotion + AI transfers/loans
    // + objectives + transfer window open. Body in SeasonTransitionService.processEndOfSeason.
    // Kept as delegate so the GameAdvanceService call site stays unchanged.
    public void processEndOfSeason(int season) {
        seasonTransitionService.processEndOfSeason(season);
    }

    // Phase 2 — aging, retirement, regens, new fixtures, scorers init, cup brackets.
    // Body in SeasonTransitionService.processNewSeasonSetup.
    public void processNewSeasonSetup(int season) {
        seasonTransitionService.processNewSeasonSetup(season);
    }

    // Thin delegate to SeasonTransitionService.handleContractExpiries — kept so
    // GameAdvanceService (CONTRACT_EXPIRY_CHECK calendar event) can keep calling
    // through the controller.
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
        return competitionDisplayService.getHistoricalTeamDetails(competitionId, seasonNumber);
    }

    @GetMapping("/getTeams/{competitionId}")
    public List<TeamCompetitionView> getTeamDetails(@PathVariable(name = "competitionId") long competitionId, HttpServletRequest request) {
        return competitionDisplayService.getTeamDetails(competitionId, userContext.getTeamIdOrNull(request));
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
        return competitionDisplayService.getTeamCompetitions(teamId);
    }

    @GetMapping("/getCoefficients")
    public List<Map<String, Object>> getCoefficients() {
        return getCountryCoefficients();
    }

    @GetMapping("/getEuropeanGroups/{competitionId}/{season}")
    public List<Map<String, Object>> getEuropeanGroups(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") long season) {
        return europeanCompetitionService.getEuropeanGroups(competitionId, season);
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
        return cupBracketService.getCupRoundCount(competitionId);
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
        return competitionDisplayService.getNotPlayedMatches(Long.parseLong(competitionId), Long.parseLong(roundId));
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
        return competitionDisplayService.getCompetitionInfo(id);
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getTeamNameByTeamId(@PathVariable(name = "competitionId") long competitionId) {

        return competitionRepository.findNameById(competitionId);
    }

    // Thin delegate — body in CompetitionDisplayService.getTeamCountForCompetition
    // (called from MatchSimulationOrchestrator via controllerRef + from getCupRoundCount below).
    public int getTeamCountForCompetition(long competitionId) {
        return competitionDisplayService.getTeamCountForCompetition(competitionId);
    }


    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        return europeanCompetitionService.getEuropeanSummary();
    }

    @GetMapping("/getCountryCoefficients")
    public List<Map<String, Object>> getCountryCoefficients() {
        return europeanCompetitionService.getCountryCoefficients();
    }

    @GetMapping("/getClubCoefficients")
    public List<Map<String, Object>> getClubCoefficients() {
        return europeanCompetitionService.getClubCoefficients();
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

    // Thin delegate — body in MatchSimulationOrchestrator.simulateMatchday (called by GameAdvanceService).
    public void simulateMatchday(long competitionId, int matchday, int season) {
        matchSimulationOrchestrator.simulateMatchday(competitionId, matchday, season);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.getAllMatchResults (called by GameAdvanceService).
    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        return matchSimulationOrchestrator.getAllMatchResults(competitionId, matchday, season);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.finalizeInteractiveLiveMatch (called by MatchController.commit).
    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        return matchSimulationOrchestrator.finalizeInteractiveLiveMatch(liveKey);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.getHumanMatchResult (called by GameAdvanceService).
    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        return matchSimulationOrchestrator.getHumanMatchResult(competitionId, matchday, season, humanTeamId);
    }

}
