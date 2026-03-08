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
import com.footballmanagergamesimulator.service.FixtureSchedulingService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.util.*;
import jakarta.annotation.PostConstruct;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
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

    private final ObjectMapper objectMapper = new ObjectMapper(); // <--- Ai nevoie de asta


    Round round;
    private boolean transferWindowOpen = false;
    private boolean managerFired = false;
    private boolean teamTalkUsedThisRound = false;

    // Cached competition type ID sets to avoid repeated DB queries
    private Set<Long> cachedLeagueCompIds = null;
    private Set<Long> cachedCupCompIds = null;
    private Set<Long> cachedSecondLeagueCompIds = null;

    @PostConstruct
    public void initializeRound() {

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

        // Generate calendar AFTER fixtures exist so updateMatchDays can set the day field
        fixtureSchedulingService.generateSeasonCalendar(1);

        generateSeasonObjectives((int) round.getSeason());

        // Create players and managers for all teams (season 1 only)
        List<Team> teams = teamRepository.findAll();
        Random random = new Random();
        for (Team team : teams) {
            TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(team.getId());
            int nrPlayers = 22;
            for (int i = 0; i < nrPlayers; i++) {
                String name = compositeNameGenerator.generateName(team.getCompetitionId());
                Human player = new Human();
                player.setTeamId(team.getId());
                player.setName(name);
                player.setTypeId(TypeNames.PLAYER_TYPE);
                if (i < 2) player.setPosition("GK");
                else if (i < 4) player.setPosition("DL");
                else if (i < 6) player.setPosition("DR");
                else if (i < 10) player.setPosition("DC");
                else if (i < 12) player.setPosition("ML");
                else if (i < 14) player.setPosition("MR");
                else if (i < 18) player.setPosition("MC");
                else player.setPosition("ST");
                player.setAge(random.nextInt(23, 30));
                player.setSeasonCreated(1L);
                player.setCurrentStatus("Senior");
                player.setMorale(100);
                player.setFitness(100);

                int reputation = 100;
                if (teamFacilities != null)
                    reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                int playerRating = random.nextInt(Math.max(10, reputation - 20), Math.max(11, reputation + 20));
                player.setRating(playerRating);
                player.setCurrentAbility(playerRating);
                player.setPotentialAbility(playerRating + random.nextInt(10, 40));
                player.setBestEverRating(playerRating);
                player.setTransferValue(calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));
                player.setContractEndSeason((int) round.getSeason() + random.nextInt(2, 6));
                player.setWage((long) (player.getRating() * 50));
                long transferVal = calculateTransferValue(player.getAge(), player.getPosition(), player.getRating());
                player.setReleaseClause(random.nextInt(10) < 3 ? 0 : transferVal * 2);
                player = humanRepository.save(player);

                TeamPlayerHistoricalRelation teamPlayerHistoricalRelation = new TeamPlayerHistoricalRelation();
                teamPlayerHistoricalRelation.setPlayerId(player.getId());
                teamPlayerHistoricalRelation.setTeamId(team.getId());
                teamPlayerHistoricalRelation.setSeasonNumber(1);
                teamPlayerHistoricalRelation.setRating(player.getRating());
                teamPlayerHistoricalRelationRepository.save(teamPlayerHistoricalRelation);

                PlayerSkills playerSkills = new PlayerSkills();
                playerSkills.setPlayerId(player.getId());
                playerSkills.setPosition(player.getPosition());
                competitionService.generateSkills(playerSkills, player.getRating());
                playerSkillsRepository.save(playerSkills);
            }

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
            List<String> tactics = List.of("442", "433", "343", "352", "451");
            manager.setTacticStyle(tactics.get(random.nextInt(0, tactics.size())));
            humanRepository.save(manager);
        }

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
                    scorer.setCompetitionTypeId((int) competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()));
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

    @GetMapping("/isManagerFired")
    public boolean isManagerFired() {
        return managerFired;
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
    public String acceptJob(@RequestBody Map<String, Long> body) {
        if (!managerFired) {
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

        // Find the human manager (typeId=4, team=HUMAN_TEAM_ID or unemployed)
        Human humanManager = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                .filter(m -> m.getTeamId() != null && m.getTeamId() == HUMAN_TEAM_ID)
                .findFirst()
                .orElse(null);

        // If human manager was already removed from team, find by old human team
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

        managerFired = false;

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
            }
            humanRepository.saveAll(players);
        }

        // Handle contract expiries
        handleContractExpiries((int) round.getSeason());

        transferWindowOpen = false;

        // Generate season objectives for all teams
        generateSeasonObjectives((int) round.getSeason());

        System.out.println("=== NEW SEASON " + round.getSeason() + " STARTED ===");

        return "Transfer window closed. Season " + round.getSeason() + " started.";
    }

    @GetMapping("/play")
    // @Scheduled(fixedDelay = 3000L) -- Disabled: game advance is now driven by GameAdvanceService
    public void play() {
      try {

        // Game paused during transfer window or manager firing - wait for resolution
        if (transferWindowOpen || managerFired) {
            return;
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
                // Skip human team from automatic sell list - they negotiate manually
                if (teamId == HUMAN_TEAM_ID) continue;

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
                // Skip human team from automatic buying - they negotiate manually
                if (teamId == HUMAN_TEAM_ID) continue;

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
                humanRepository.save(human);

                // Update team finances
                buyTeam.setTransferBudget(buyTeam.getTransferBudget() - transferFee);
                sellTeam.setTransferBudget(sellTeam.getTransferBudget() + transferFee);
                teamRepository.save(buyTeam);
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
                if (teamId == HUMAN_TEAM_ID) continue;

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
                            .filter(t -> t.getId() != teamId && t.getId() != HUMAN_TEAM_ID)
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
                    int nrPlayers = 22;
                    for (int i = 0; i < nrPlayers; i++) {
                        String name = compositeNameGenerator.generateName(team.getCompetitionId());
                        Human player = new Human();
                        player.setTeamId(team.getId());
                        player.setName(name);
                        player.setTypeId(TypeNames.PLAYER_TYPE);
                        if (i < 2)
                            player.setPosition("GK");
                        else if (i < 4)
                            player.setPosition("DL");
                        else if (i < 6)
                            player.setPosition("DR");
                        else if (i < 10)
                            player.setPosition("DC");
                        else if (i < 12)
                            player.setPosition("ML");
                        else if (i < 14)
                            player.setPosition("MR");
                        else if (i < 18)
                            player.setPosition("MC");
                        else
                            player.setPosition("ST");
                        player.setAge(random.nextInt(23, 30));
                        player.setSeasonCreated(1L);
                        player.setCurrentStatus("Senior");
                        player.setMorale(100);
                        player.setFitness(100);

                        int reputation = 100;
                        if (teamFacilities != null)
                            reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                        int playerRating = random.nextInt(Math.max(10, reputation - 20), Math.max(11, reputation + 20));
                        player.setRating(playerRating);
                        player.setCurrentAbility(playerRating);
                        player.setPotentialAbility(playerRating + random.nextInt(10, 40));
                        player.setBestEverRating(playerRating);
                        player.setTransferValue(calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));

                        // Initialize contract
                        player.setContractEndSeason((int) round.getSeason() + random.nextInt(2, 6));
                        player.setWage((long) (player.getRating() * 50));
                        long transferVal = calculateTransferValue(player.getAge(), player.getPosition(), player.getRating());
                        player.setReleaseClause(random.nextInt(10) < 3 ? 0 : transferVal * 2);

                        player = humanRepository.save(player);

                        // save TeamPlayerHistoricalRelation for season 1
                        TeamPlayerHistoricalRelation teamPlayerHistoricalRelation = new TeamPlayerHistoricalRelation();
                        teamPlayerHistoricalRelation.setPlayerId(player.getId());
                        teamPlayerHistoricalRelation.setTeamId(team.getId());
                        teamPlayerHistoricalRelation.setSeasonNumber(1);
                        teamPlayerHistoricalRelation.setRating(player.getRating());
                        teamPlayerHistoricalRelationRepository.save(teamPlayerHistoricalRelation);

                        PlayerSkills playerSkills = new PlayerSkills();
                        playerSkills.setPlayerId(player.getId());
                        playerSkills.setPosition(player.getPosition());
                        competitionService.generateSkills(playerSkills, player.getRating());

                        playerSkillsRepository.save(playerSkills);
                    }

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
                    List<String> tactics = List.of("442", "433", "343", "352", "451");

                    manager.setTacticStyle(tactics.get(random.nextInt(0, tactics.size())));
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
                        scorer.setCompetitionTypeId((int) competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()));
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
            for (long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                for (Human player : players) {
                    player = _humanService.trainPlayer(player, teamFacilities, Integer.parseInt(getCurrentSeason()));
                    humanRepository.save(player);
                }
            }
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

        // European competitions - League of Champions group stage + knockout, Stars Cup knockout
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4); // League of Champions
        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5); // Stars Cup

        // LoC schedule:
        // Game round 2: Qualifying round (competition round 1) - 6 teams, 3 matches, knockout
        // Game rounds {5, 9, 14, 19, 24, 29}: Group stage matchdays 1-6 (competition rounds 2-7)
        // Game round 34: Quarter-Final (competition round 8)
        // Game round 40: Semi-Final (competition round 9)
        // Game round 46: Final (competition round 10)
        for (Long locId : locIds) {
            String competitionId = String.valueOf(locId);

            // Qualifying round at game round 2
            if (round.getRound() == 2) {
                this.getFixturesForRound(competitionId, "1");
                this.simulateRound(competitionId, "1");
            }

            // Group stage matchdays at game rounds 5, 9, 14, 19, 24, 29
            int[] groupMatchdays = {5, 9, 14, 19, 24, 29};
            for (int md = 0; md < groupMatchdays.length; md++) {
                if (round.getRound() == groupMatchdays[md]) {
                    if (md == 0) {
                        drawEuropeanGroups(locId);
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
                List<Long> qfParticipants = this.getParticipants(competitionId, "8");
                System.out.println("=== LoC QF: " + qfParticipants.size() + " participants for round 8");
                this.getFixturesForRound(competitionId, "8");
                this.simulateRound(competitionId, "8");
            }
            // Semi-Final at game round 40
            if (round.getRound() == 40) {
                List<Long> sfParticipants = this.getParticipants(competitionId, "9");
                System.out.println("=== LoC SF: " + sfParticipants.size() + " participants for round 9");
                this.getFixturesForRound(competitionId, "9");
                this.simulateRound(competitionId, "9");
            }
            // Final at game round 46
            if (round.getRound() == 46) {
                List<Long> fParticipants = this.getParticipants(competitionId, "10");
                System.out.println("=== LoC Final: " + fParticipants.size() + " participants for round 10");
                this.getFixturesForRound(competitionId, "10");
                this.simulateRound(competitionId, "10");
            }
        }

        // Stars Cup: 16 teams, 4 knockout rounds
        for (Long starsCupId : starsCupIds) {
            String competitionId = String.valueOf(starsCupId);
            int[] schedule = {8, 20, 32, 44};
            for (int r = 0; r < schedule.length; r++) {
                if (round.getRound() == schedule[r]) {
                    this.getFixturesForRound(competitionId, String.valueOf(r + 1));
                    this.simulateRound(competitionId, String.valueOf(r + 1));
                }
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
    public List<TeamCompetitionView> getTeamDetails(@PathVariable(name = "competitionId") long competitionId) {

        List<Long> teamParticipantIds = competitionTeamInfoRepository
                .findAll()
                .stream()
                .filter(competitionTeamInfo -> competitionTeamInfo.getCompetitionId() == competitionId && competitionTeamInfo.getSeasonNumber() == Long.valueOf(getCurrentSeason()))
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .toList();

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();

        for (Long teamId : teamParticipantIds) {
            TeamCompetitionDetail teamCompetitionDetail = teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
            Team team = teamRepository.findById(teamId).orElseGet(null);

            if (team == null || teamCompetitionDetail == null) {
                teamCompetitionDetail = new TeamCompetitionDetail();
                teamCompetitionDetail.setTeamId(teamId);
                teamCompetitionDetail.setCompetitionId(competitionId);
            }

            if (team != null) { // team should never be null
                TeamCompetitionView teamCompetitionView = adaptTeam(team, teamCompetitionDetail);
                teamCompetitionViews.add(teamCompetitionView);
            }
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
                        .findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competition.getId());
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

            if (season == currentSeason) {
                // Current season: use live TeamCompetitionDetail
                TeamCompetitionDetail detail = teamCompetitionDetailRepository
                        .findTeamCompetitionDetailByTeamIdAndCompetitionId(cti.getTeamId(), competitionId);
                if (detail != null) {
                    entry.put("games", detail.getGames());
                    entry.put("wins", detail.getWins());
                    entry.put("draws", detail.getDraws());
                    entry.put("loses", detail.getLoses());
                    entry.put("goalsFor", detail.getGoalsFor());
                    entry.put("goalsAgainst", detail.getGoalsAgainst());
                    entry.put("goalDifference", detail.getGoalDifference());
                    entry.put("points", detail.getPoints());
                } else {
                    entry.put("games", 0); entry.put("wins", 0); entry.put("draws", 0);
                    entry.put("loses", 0); entry.put("goalsFor", 0); entry.put("goalsAgainst", 0);
                    entry.put("goalDifference", 0); entry.put("points", 0);
                }
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
            // Stars Cup: 16 teams, 4 knockout rounds
            result.put("totalRounds", 4);
        } else if (comp.getTypeId() == 4) {
            // LoC: round 1 = qualifying, rounds 2-7 = group stage, rounds 8-10 = knockout
            result.put("totalRounds", 10);
            result.put("groupRounds", 7);
            result.put("qualifyingRounds", 1);
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
        Set<Long> cupIds = getCompetitionIdsByCompetitionType(2);
        if (cupIds.contains(competitionId)) return true;

        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5);
        if (starsCupIds.contains(competitionId)) return true;

        // LoC: round 1 is qualifying (knockout), rounds 2-7 are group stage, rounds 8-9 are knockout
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4);
        if (locIds.contains(competitionId) && (roundId == 1 || roundId >= 8)) return true;

        return false;
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

        CompetitionTeamInfoDetail matchDetail = competitionTeamInfoDetailRepository.findCompetitionTeamInfoDetailByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(competitionId, roundId, match.getTeam1Id(), match.getTeam2Id(), Long.parseLong(getCurrentSeason()));
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
            List<List<List<Long>>> schedule = roundRobin.getSchedule(participants);
            int currentRound = 1;

            boolean reverse = true;

            for (int i = 0; i < 2; i++) {

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

            System.out.println("=== getFixturesForRound knockout: comp=" + competitionId + " round=" + roundId + " participants=" + participants.size());
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

    @GetMapping("simulateRound/{competitionId}/{roundId}")
    public void simulateRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long nextRound = getNextRound(_roundId);

        Random random = new Random();
        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(_competitionId, _roundId, getCurrentSeason());

        boolean knockout = isKnockoutRound(_competitionId, _roundId);

        for (CompetitionTeamInfoMatch match : matches) {
            long teamId1 = match.getTeam1Id();
            long teamId2 = match.getTeam2Id();

            boolean isHumanMatch = (teamId1 == HUMAN_TEAM_ID || teamId2 == HUMAN_TEAM_ID);

            int teamScore1, teamScore2;
            double teamPower1, teamPower2;

            if (isHumanMatch) {
                // --- FULL SIMULATION for human team matches ---
                String tactic1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE).get(0).getTacticStyle();
                String tactic2 = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE).get(0).getTacticStyle();

                teamPower1 = getBestElevenRatingByTactic(teamId1, tactic1);
                teamPower2 = getBestElevenRatingByTactic(teamId2, tactic2);

                Optional<PersonalizedTactic> personalizedTactic1 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
                Optional<PersonalizedTactic> personalizedTactic2 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

                if (personalizedTactic1.isPresent())
                    teamPower1 = adjustTeamPowerByTacticalProperties(teamPower1, teamPower2, personalizedTactic1.get());
                if (personalizedTactic2.isPresent())
                    teamPower2 = adjustTeamPowerByTacticalProperties(teamPower2, teamPower1, personalizedTactic2.get());

                List<Integer> scores = calculateScores(teamPower1, teamPower2);
                teamScore1 = scores.get(0);
                teamScore2 = scores.get(1);

                if (knockout && teamScore1 == teamScore2) {
                    double total = teamPower1 + teamPower2;
                    double winChance = total > 0 ? (teamPower1 / total) * 0.3 + 0.35 : 0.5;
                    if (random.nextDouble() < winChance) {
                        teamScore1++;
                    } else {
                        teamScore2++;
                    }
                }

                // Full scorer tracking with weighted distribution
                getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, Long.valueOf(competitionId));
                getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, Long.valueOf(competitionId));

                // Detailed match events (goals, assists, cards, substitutions)
                generateMatchEvents(_competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                        teamId1, teamId2, teamScore1, teamScore2, tactic1, tactic2);

                // Full post-match processing for human matches
                processInjuriesForTeam(teamId1);
                processInjuriesForTeam(teamId2);

                updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2);
                updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1);

                awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);

                generateMatchReport(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                updateManagerReputationAfterMatch(teamId1, teamId2, teamScore1, teamScore2);

            } else {
                // --- FAST SIMULATION for AI vs AI matches ---
                // Matches original speed: cached rating, Poisson score, simple standings update
                teamPower1 = getSimpleTeamRating(teamId1);
                teamPower2 = getSimpleTeamRating(teamId2);

                List<Integer> scores = calculateScores(teamPower1, teamPower2);
                teamScore1 = scores.get(0);
                teamScore2 = scores.get(1);

                if (knockout && teamScore1 == teamScore2) {
                    double total = teamPower1 + teamPower2;
                    double winChance = total > 0 ? (teamPower1 / total) * 0.3 + 0.35 : 0.5;
                    if (random.nextDouble() < winChance) {
                        teamScore1++;
                    } else {
                        teamScore2++;
                    }
                }

                // Simple standings update (no morale calculation)
                updateTeamSimple(teamId1, _competitionId, teamScore1, teamScore2);
                updateTeamSimple(teamId2, _competitionId, teamScore2, teamScore1);
            }

            // Knockout progression (needed for both human and AI matches)
            if (knockout) {
                CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                competitionTeamInfo.setCompetitionId(_competitionId);
                competitionTeamInfo.setRound(nextRound);

                competitionTeamInfo.setTeamId(teamScore1 > teamScore2 ? teamId1 : teamId2);
                competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                competitionTeamInfoRepository.save(competitionTeamInfo);
            }

            // Match result record (needed for both - results page)
            CompetitionTeamInfoDetail competitionTeamInfoDetail = new CompetitionTeamInfoDetail();
            competitionTeamInfoDetail.setCompetitionId(_competitionId);
            competitionTeamInfoDetail.setRoundId(_roundId);
            competitionTeamInfoDetail.setTeam1Id(teamId1);
            competitionTeamInfoDetail.setTeam2Id(teamId2);
            competitionTeamInfoDetail.setTeamName1(teamRepository.findById(teamId1).get().getName());
            competitionTeamInfoDetail.setTeamName2(teamRepository.findById(teamId2).get().getName());
            competitionTeamInfoDetail.setScore(teamScore1 + " - " + teamScore2);
            competitionTeamInfoDetail.setSeasonNumber(Long.parseLong(getCurrentSeason()));
            competitionTeamInfoDetailRepository.save(competitionTeamInfoDetail);
        }

    }

    private double adjustTeamPowerByTacticalProperties(double teamRating, double opponentRating, PersonalizedTactic teamTactic) {

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

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference) {

        TeamCompetitionDetail team = teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
        }

        String result;
        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            result = "W";
            team.setForm(team.getForm() + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);
        } else if (scoreHome == scoreAway) {
            result = "D";
            team.setForm(team.getForm() + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            result = "L";
            team.setForm(team.getForm() + "L");
            team.setLoses(team.getLoses() + 1);
        }

        double baseMoraleChange = calculateMoraleChangeForTeamDifference(result, teamPowerDifference);
        updatePlayersMorale(teamId, baseMoraleChange, result);

        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5)
            team.setForm(team.getForm().substring(team.getForm().length() - 5));

        teamCompetitionDetailRepository.save(team);
    }

    /**
     * Fast standings update for AI vs AI matches.
     * No morale calculation, no scorer queries - just update W/D/L/GF/GA/points.
     */
    private void updateTeamSimple(long teamId, long competitionId, int scoreHome, int scoreAway) {
        TeamCompetitionDetail team = teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
        }

        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            team.setForm(team.getForm() + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);
        } else if (scoreHome == scoreAway) {
            team.setForm(team.getForm() + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            team.setForm(team.getForm() + "L");
            team.setLoses(team.getLoses() + 1);
        }
        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5)
            team.setForm(team.getForm().substring(team.getForm().length() - 5));

        teamCompetitionDetailRepository.save(team);
    }

    private void updatePlayersMorale(long teamId, double baseMoraleChange, String matchResult) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        int currentSeason = Integer.parseInt(getCurrentSeason());
        Random random = new Random();

        // Get scorer records for this team in the current season to identify who played
        List<Scorer> seasonScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, currentSeason);

        // Find the most recent match scorers (players who played in the latest match)
        Set<Long> playedInMatch = new HashSet<>();
        Map<Long, Integer> goalsInMatch = new HashMap<>();

        if (!seasonScorers.isEmpty()) {
            // The latest scorer entries belong to the match just simulated
            Scorer lastEntry = seasonScorers.get(seasonScorers.size() - 1);
            long latestOpponentId = lastEntry.getOpponentTeamId();
            long latestCompetitionId = lastEntry.getCompetitionId();
            int latestTeamScore = lastEntry.getTeamScore();
            int latestOpponentScore = lastEntry.getOpponentScore();

            for (Scorer scorer : seasonScorers) {
                if (scorer.getOpponentTeamId() == latestOpponentId
                        && scorer.getCompetitionId() == latestCompetitionId
                        && scorer.getTeamScore() == latestTeamScore
                        && scorer.getOpponentScore() == latestOpponentScore) {
                    playedInMatch.add(scorer.getPlayerId());
                    if (scorer.getGoals() > 0) {
                        goalsInMatch.put(scorer.getPlayerId(), scorer.getGoals());
                    }
                }
            }
        }

        for (Human player : allPlayers) {
            double moraleChange;

            if (playedInMatch.contains(player.getId())) {
                // Player participated in the match - full morale change from team result
                moraleChange = baseMoraleChange;

                // Goalscorer bonus: +3 to +5 extra morale
                if (goalsInMatch.containsKey(player.getId())) {
                    moraleChange += random.nextDouble(3, 6);
                }
            } else {
                // Player was benched (not in the 11 + subs who played)
                switch (matchResult) {
                    case "W":
                        moraleChange = -2; // Happy team won, frustrated they didn't play
                        break;
                    case "D":
                        moraleChange = -4; // Frustrated at draw and not playing
                        break;
                    case "L":
                        moraleChange = -3; // Feel they could have helped
                        break;
                    default:
                        moraleChange = 0;
                }
            }

            player.setMorale(player.getMorale() + moraleChange);
            player.setMorale(Math.min(player.getMorale(), 120D));
            player.setMorale(Math.max(player.getMorale(), 30D));
        }
        humanRepository.saveAll(allPlayers);
    }

    private void applyMoraleRecovery() {
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        boolean changed = false;
        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            double morale = player.getMorale();
            if (morale < 80) {
                player.setMorale(Math.min(morale + 1, 80D));
                changed = true;
            } else if (morale > 110) {
                player.setMorale(Math.max(morale - 1, 110D));
                changed = true;
            }
        }
        if (changed) {
            humanRepository.saveAll(allPlayers);
        }
    }

    private double calculateMoraleChangeForTeamDifference(String result, double teamPowerDifference) {

        Random random = new Random();

        if (result.equals("W")) {
            if (teamPowerDifference > 500) {
                return random.nextDouble(0, 2);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(1, 2);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(1, 4);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(2, 6);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(5, 10);
            } else {
                return random.nextDouble(7, 15);
            }
        } else if (result.equals("D")) {
            if (teamPowerDifference > 500) {
                return random.nextDouble(-8, -2);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(-5, 0);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(-2, 1);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(1, 3);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(2, 7);
            } else {
                return random.nextDouble(5, 10);
            }
        } else {
            if (teamPowerDifference > 500) {
                return random.nextDouble(-20, -5);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(-10, -3);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(-5, -2);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(-3, -1);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(-2, 0);
            } else {
                return random.nextDouble(-1, 0);
            }
        }
    }

    private List<Integer> calculateScores(double power1, double power2) {
        double total = power1 + power2;
        if (total == 0) return List.of(1, 1);

        double ratio1 = power1 / total; // 0.0 to 1.0
        double ratio2 = power2 / total;

        // Amplify power difference: raise ratio to power 1.5 then renormalize
        double amp1 = Math.pow(ratio1, 1.5);
        double amp2 = Math.pow(ratio2, 1.5);
        double ampTotal = amp1 + amp2;
        double adjRatio1 = amp1 / ampTotal;
        double adjRatio2 = amp2 / ampTotal;

        // Total expected goals per match: ~3.0 (distributed by power)
        // Stronger team gets bigger share of the 3.0
        double expected1 = 3.0 * adjRatio1; // e.g., 60% power -> ~1.9 goals
        double expected2 = 3.0 * adjRatio2; // e.g., 40% power -> ~1.1 goals

        Random random = new Random();
        int score1 = poissonGoals(random, expected1);
        int score2 = poissonGoals(random, expected2);

        return List.of(score1, score2);
    }

    private int poissonGoals(Random random, double expectedGoals) {
        // Poisson distribution sampling using Knuth's algorithm
        double L = Math.exp(-expectedGoals);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);
        return Math.min(k - 1, 7); // Cap at 7 goals max
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


    public long calculateTransferValue(long age, String position, double rating) {
        double baseValue = rating * 10000;

        double ageMultiplier;
        if (age <= 22) ageMultiplier = 0.7;
        else if (age <= 24) ageMultiplier = 0.9;
        else if (age <= 27) ageMultiplier = 1.0;
        else if (age <= 29) ageMultiplier = 0.85;
        else if (age <= 31) ageMultiplier = 0.6;
        else if (age <= 33) ageMultiplier = 0.35;
        else ageMultiplier = 0.15;

        return (long) (baseValue * ageMultiplier);
    }

    private double getBestElevenRating(List<Human> players) {


        double bestElevenRating = 0;

        for (Human player : players)
            bestElevenRating += player.getRating();

        return bestElevenRating;
    }

    private double getBestElevenRatingByTactic(long teamId, String tactic) {

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

                    // 4. Calculăm rating-ul (cu morale + fitness)
                    // Morale: 0-100 mapped to 0.85-1.15 (±15% impact)
                    double moraleMultiplier = 0.85 + (player.getMorale() / 100D) * 0.30;
                    double fitnessMultiplier = Math.max(0.7, player.getFitness() / 100D);
                    if (player.getPosition().equals(tacticPosition)) {
                        rating += player.getRating() * moraleMultiplier * fitnessMultiplier;
                    } else {
                        rating += player.getRating() / 2 * moraleMultiplier * fitnessMultiplier;
                    }
                }

                // add extra bonus logic here if needed

                return rating;

            } catch (Exception e) {
                e.printStackTrace();
                // Dacă parsarea eșuează, continuăm execuția spre fallback-ul de mai jos
            }
        }

        // --- FALLBACK (Dacă nu are tactică salvată sau a crăpat parsarea) ---
        // Folosește algoritmul automat pentru a determina cel mai bun 11
        List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);

        return playerViews
                .stream()
                .mapToDouble(playerView -> {
                    double moraleMul = 0.85 + (playerView.getMorale() / 100D) * 0.30;
                    double fitMul = Math.max(0.7, playerView.getFitness() / 100D);
                    return playerView.getRating() * moraleMul * fitMul;
                })
                .sum();
    }

    /**
     * Fast team rating for AI teams: sum of top 11 player ratings, no tactic parsing.
     */
    private Map<Long, Double> simpleRatingCache = new HashMap<>();

    private double getSimpleTeamRating(long teamId) {
        if (simpleRatingCache.containsKey(teamId)) return simpleRatingCache.get(teamId);

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        double rating = players.stream()
                .filter(p -> !p.isRetired())
                .mapToDouble(Human::getRating)
                .sorted()
                .skip(Math.max(0, players.size() - 11))
                .sum();
        simpleRatingCache.put(teamId, rating);
        return rating;
    }

    /**
     * Simplified scorer processing for AI vs AI matches.
     * Only creates Scorer records for goal scorers and batch-updates leaderboard.
     */
    private void getScorersForTeamSimplified(long teamId1, long teamId2, int teamScore, int opponentScore, long competitionId) {
        if (teamScore == 0) return; // No goals = nothing to track for simplified mode

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.PLAYER_TYPE);
        if (players.isEmpty()) return;

        List<Human> outfield = players.stream()
                .filter(p -> !p.isRetired() && !"GK".equals(p.getPosition()))
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .limit(10)
                .collect(java.util.stream.Collectors.toList());

        if (outfield.isEmpty()) return;

        Random random = new Random();

        // Only track players who actually scored
        Map<Long, Integer> goalsByPlayer = new HashMap<>();
        for (int g = 0; g < teamScore; g++) {
            Human scorer = outfield.get(random.nextInt(outfield.size()));
            goalsByPlayer.merge(scorer.getId(), 1, Integer::sum);
        }

        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = getCompetitionIdsByCompetitionType(1);
            cachedCupCompIds = getCompetitionIdsByCompetitionType(2);
            cachedSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
        }

        String currentSeason = getCurrentSeason();
        List<Scorer> scorersToSave = new ArrayList<>();
        List<ScorerLeaderboardEntry> leaderboardToSave = new ArrayList<>();

        // Only create records for players who scored
        for (Map.Entry<Long, Integer> entry : goalsByPlayer.entrySet()) {
            long playerId = entry.getKey();
            int goals = entry.getValue();

            Human player = players.stream().filter(p -> p.getId() == playerId).findFirst().orElse(null);
            if (player == null) continue;

            Scorer scorer = new Scorer();
            scorer.setPlayerId(playerId);
            scorer.setSeasonNumber(Integer.parseInt(currentSeason));
            scorer.setTeamId(teamId1);
            scorer.setOpponentTeamId(teamId2);
            scorer.setPosition(player.getPosition());
            scorer.setTeamScore(teamScore);
            scorer.setOpponentScore(opponentScore);
            scorer.setCompetitionId(competitionId);
            scorer.setGoals(goals);
            scorer.setSubstitute(false);
            scorersToSave.add(scorer);

            // Update leaderboard only for scorers
            Optional<ScorerLeaderboardEntry> optLeaderboard = scorerLeaderboardRepository.findByPlayerId(playerId);
            if (optLeaderboard.isPresent()) {
                ScorerLeaderboardEntry lb = optLeaderboard.get();
                lb.setGoals(lb.getGoals() + goals);
                lb.setCurrentSeasonGoals(lb.getCurrentSeasonGoals() + goals);
                if (cachedLeagueCompIds.contains(competitionId)) {
                    lb.setLeagueGoals(lb.getLeagueGoals() + goals);
                    lb.setCurrentSeasonLeagueGoals(lb.getCurrentSeasonLeagueGoals() + goals);
                } else if (cachedCupCompIds.contains(competitionId)) {
                    lb.setCupGoals(lb.getCupGoals() + goals);
                    lb.setCurrentSeasonCupGoals(lb.getCurrentSeasonCupGoals() + goals);
                } else if (cachedSecondLeagueCompIds.contains(competitionId)) {
                    lb.setSecondLeagueGoals(lb.getSecondLeagueGoals() + goals);
                    lb.setCurrentSeasonSecondLeagueGoals(lb.getCurrentSeasonSecondLeagueGoals() + goals);
                }
                leaderboardToSave.add(lb);
            }
        }

        if (!scorersToSave.isEmpty()) scorerRepository.saveAll(scorersToSave);
        if (!leaderboardToSave.isEmpty()) scorerLeaderboardRepository.saveAll(leaderboardToSave);
    }

    private void getScorersForTeam(long teamId, long opponentTeamId, int teamScore, int opponentScore, String tactic, long competitionId) {

        long competitionTypeId = competitionRepository.findTypeIdById(competitionId);
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
                } catch (Exception e) {
                    System.err.println("Distribution error (negative weights?): " + e.getMessage());
                }
            }
        }

        for (Scorer scorer: possibleScorers) {

            scorerRepository.save(scorer);

            Optional<Human> possiblePlayer = humanRepository.findById(scorer.getPlayerId());
            if (possiblePlayer.isPresent()) {
                Human player = possiblePlayer.get();

                ScorerLeaderboardEntry scorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(player.getId()).get();
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

    private void generateMatchEvents(long competitionId, int seasonNumber, int roundNumber,
                                      long teamId1, long teamId2, int teamScore1, int teamScore2,
                                      String tactic1, String tactic2) {

        Random random = new Random();
        List<MatchEvent> events = new ArrayList<>();

        boolean isHumanMatch = (teamId1 == HUMAN_TEAM_ID || teamId2 == HUMAN_TEAM_ID);

        String[] goalDescriptions = {"Tap in", "Header", "Long range shot", "Free kick", "Penalty", "Solo run", "Volley"};

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
            Human scorer = team1Outfield.get(random.nextInt(team1Outfield.size()));
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
            goalEvent.setDetails(goalDescriptions[random.nextInt(goalDescriptions.length)]);
            events.add(goalEvent);

            // 70% chance of assist
            if (random.nextDouble() < 0.7 && team1Outfield.size() > 1) {
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
            Human scorer = team2Outfield.get(random.nextInt(team2Outfield.size()));
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
            goalEvent.setDetails(goalDescriptions[random.nextInt(goalDescriptions.length)]);
            events.add(goalEvent);

            if (random.nextDouble() < 0.7 && team2Outfield.size() > 1) {
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

    @GetMapping("/getCompetitionInfo/{id}")
    public Map<String, Object> getCompetitionInfo(@PathVariable Long id) {

        Competition comp = competitionRepository.findById(id).orElse(null);
        Map<String, Object> info = new HashMap<>();
        if (comp != null) {
            info.put("typeId", comp.getTypeId());
            info.put("name", comp.getName());
        }

        return info;
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

        List<Human> humanTeamPlayers = humanRepository.findAllByTeamId(HUMAN_TEAM_ID);
        Team humanTeam = teamRepository.findById(HUMAN_TEAM_ID).orElse(null);
        if (humanTeam == null) return;

        int season = (int) round.getSeason();

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
                offer.setToTeamId(HUMAN_TEAM_ID);
                offer.setToTeamName(humanTeam.getName());
                offer.setOfferAmount(transferValue);
                offer.setAskingPrice(transferValue);
                offer.setStatus("pending");
                offer.setSeasonNumber(season);
                offer.setDirection("incoming");
                offer.setCreatedAt(System.currentTimeMillis());
                transferOfferRepository.save(offer);

                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(HUMAN_TEAM_ID);
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

    private void refreshTeamBudgets(int season) {
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();

        for (Competition comp : allComps) {
            if (comp.getTypeId() != 1 && comp.getTypeId() != 3) continue;

            long leagueBase = (comp.getTypeId() == 1) ? 1_500_000L : 400_000L;

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
                long leagueIncome = (long) (leagueBase * (numTeams + 1 - position) / (numTeams / 2.0));

                Team team = teamRepository.findById(detail.getTeamId()).orElse(null);
                if (team == null) { position++; continue; }

                // European income: coefficient points * 200,000
                Optional<ClubCoefficient> cc = clubCoefficientRepository
                        .findByTeamIdAndSeasonNumber(detail.getTeamId(), season);
                long europeanIncome = cc.map(c -> (long) (c.getPoints() * 200_000)).orElse(0L);

                // Decay unspent budget by 20%, then add new income
                long newBudget = (long) (team.getTransferBudget() * 0.8) + leagueIncome + europeanIncome;
                team.setTransferBudget(newBudget);
                team.setTotalFinances(team.getTotalFinances() + leagueIncome + europeanIncome);
                teamRepository.save(team);

                position++;
            }
        }
    }

    private void qualifyTeamsForEuropeanCompetitions() {
        long nextSeason = Long.parseLong(getCurrentSeason()) + 1;

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
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();
            standingsByLeague.put(leagueId, standings);
        }

        // LoC allocation (19 participants total, 16 group stage spots):
        // Direct to group stage (round 2) — 13 teams:
        // Rank 1: 1st-4th (4 spots), Rank 2: 1st-3rd (3), Rank 3: 1st-2nd (2),
        // Rank 4: 1st-2nd (2), Rank 5: 1st (1), Rank 6: 1st (1)
        // Qualifying round (round 1) — 6 teams:
        // Rank 1: 5th, Rank 2: 4th, Rank 3: 3rd, Rank 4: 3rd, Rank 5: 2nd, Rank 6: 2nd
        int[] directSpots = {4, 3, 2, 2, 1, 1};   // per rank, how many go direct to group stage
        int[] qualifyingPos = {4, 3, 2, 2, 1, 1};  // per rank, 0-based position for qualifying

        for (int rank = 1; rank <= Math.min(numLeagues, 6); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            if (standings.isEmpty()) continue;

            // Direct to group stage (round 2)
            int direct = directSpots[rank - 1];
            for (int i = 0; i < direct; i++) {
                if (standings.size() > i) {
                    saveLocQualifier(standings.get(i).getTeamId(), locCompetitionId, nextSeason, 2);
                }
            }

            // Qualifying round (round 1)
            int qPos = qualifyingPos[rank - 1];
            if (standings.size() > qPos) {
                saveLocQualifier(standings.get(qPos).getTeamId(), locCompetitionId, nextSeason, 1);
            }
        }

        // Stars Cup allocation (16 participants, all at round 1):
        // Rank 1: 6th, 7th, 8th (3 spots)
        // Rank 2: 5th, 6th, 7th (3 spots)
        // Rank 3: 4th, 5th (2 spots)
        // Rank 4: 4th, 5th (2 spots)
        // Rank 5: 3rd, 4th, 5th (3 spots)
        // Rank 6: 3rd, 4th, 5th (3 spots)
        int[][] starsCupPositions = {
            {5, 6, 7},  // Rank 1: 6th, 7th, 8th (0-based: 5, 6, 7)
            {4, 5, 6},  // Rank 2: 5th, 6th, 7th
            {3, 4},     // Rank 3: 4th, 5th
            {3, 4},     // Rank 4: 4th, 5th
            {2, 3, 4},  // Rank 5: 3rd, 4th, 5th
            {2, 3, 4}   // Rank 6: 3rd, 4th, 5th
        };

        for (int rank = 1; rank <= Math.min(numLeagues, 6); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            int[] positions = starsCupPositions[rank - 1];
            for (int pos : positions) {
                if (standings.size() > pos) {
                    saveStarsCupQualifier(standings.get(pos).getTeamId(), starsCupCompetitionId, nextSeason);
                }
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

    private void awardCoefficientPoints(long competitionId, long roundId, long team1Id, long team2Id, int score1, int score2) {
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4);
        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5);

        if (!locIds.contains(competitionId) && !starsCupIds.contains(competitionId)) return;

        boolean isLoC = locIds.contains(competitionId);
        int season = Integer.parseInt(getCurrentSeason());

        double winPoints;
        double drawPoints;

        if (isLoC) {
            if (roundId == 1) { winPoints = 1.0; drawPoints = 0; }           // QR (knockout)
            else if (roundId <= 7) { winPoints = 2.0; drawPoints = 1.0; }    // Group stage
            else if (roundId == 8) { winPoints = 3.0; drawPoints = 0; }      // QF
            else if (roundId == 9) { winPoints = 4.0; drawPoints = 0; }      // SF
            else { winPoints = 5.0; drawPoints = 0; }                        // Final
        } else {
            // Stars Cup - worth less
            if (roundId == 1) { winPoints = 0.5; drawPoints = 0; }           // R16
            else if (roundId == 2) { winPoints = 1.5; drawPoints = 0; }      // QF
            else if (roundId == 3) { winPoints = 2.0; drawPoints = 0; }      // SF
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

    private double getClubCoefficientRolling(long teamId, int currentSeason) {
        double total = 0;
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, s);
            if (cc.isPresent()) total += cc.get().getPoints();
        }
        return total;
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
        switch (rank) {
            case 1:
                entry.put("locSpots", 5);
                entry.put("locEntry", "4 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 3);
                break;
            case 2:
                entry.put("locSpots", 4);
                entry.put("locEntry", "3 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 3);
                break;
            case 3:
                entry.put("locSpots", 3);
                entry.put("locEntry", "2 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 2);
                break;
            case 4:
                entry.put("locSpots", 3);
                entry.put("locEntry", "2 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 2);
                break;
            case 5:
                entry.put("locSpots", 2);
                entry.put("locEntry", "1 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 3);
                break;
            case 6:
                entry.put("locSpots", 2);
                entry.put("locEntry", "1 Group Stage + 1 Qualifying");
                entry.put("starsCupSpots", 3);
                break;
            default:
                entry.put("locSpots", 0);
                entry.put("locEntry", "None");
                entry.put("starsCupSpots", 0);
                break;
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

    private void drawEuropeanGroups(long locCompetitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getRound() == 2)
                .collect(Collectors.toList());

        if (entries.size() < 4) return;

        // Sort by team reputation (seeding)
        entries.sort((a, b) -> {
            Team teamA = teamRepository.findById(a.getTeamId()).orElse(new Team());
            Team teamB = teamRepository.findById(b.getTeamId()).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        // Split into 4 groups of 4 (snake draft for balance)
        int numGroups = 4;
        int groupSize = entries.size() / numGroups;
        for (int i = 0; i < entries.size(); i++) {
            int groupNumber = (i % numGroups) + 1;
            // Snake: reverse order for even rows
            if ((i / numGroups) % 2 == 1) {
                groupNumber = numGroups - (i % numGroups);
            }
            entries.get(i).setGroupNumber(groupNumber);
            competitionTeamInfoRepository.save(entries.get(i));
        }
    }

    private void resetEuropeanStats(long competitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .toList();
        for (CompetitionTeamInfo cti : entries) {
            TeamCompetitionDetail detail = teamCompetitionDetailRepository
                    .findTeamCompetitionDetailByTeamIdAndCompetitionId(cti.getTeamId(), competitionId);
            if (detail != null) {
                detail.setGames(0);
                detail.setWins(0);
                detail.setDraws(0);
                detail.setLoses(0);
                detail.setGoalsFor(0);
                detail.setGoalsAgainst(0);
                detail.setGoalDifference(0);
                detail.setPoints(0);
                detail.setForm("");
                teamCompetitionDetailRepository.save(detail);
            }
        }
    }

    private void generateGroupStageFixtures(long locCompetitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getGroupNumber() > 0)
                .toList();

        // Generate round-robin fixtures for each group (6 matchdays for 4 teams)
        Map<Integer, List<Long>> groups = new HashMap<>();
        for (CompetitionTeamInfo cti : entries) {
            groups.computeIfAbsent(cti.getGroupNumber(), k -> new ArrayList<>()).add(cti.getTeamId());
        }

        for (Map.Entry<Integer, List<Long>> group : groups.entrySet()) {
            List<Long> teams = group.getValue();
            if (teams.size() < 2) continue;

            // RoundRobin.getSchedule() already returns both legs combined,
            // so we split it in half and use the leg loop for home/away swapping
            List<List<List<Long>>> fullSchedule = roundRobin.getSchedule(teams);
            List<List<List<Long>>> firstLegRounds = fullSchedule.subList(0, fullSchedule.size() / 2);
            int matchday = 1;

            for (int leg = 0; leg < 2; leg++) {
                for (List<List<Long>> matchdayMatches : firstLegRounds) {
                    for (List<Long> match : matchdayMatches) {
                        long home = leg == 0 ? match.get(0) : match.get(1);
                        long away = leg == 0 ? match.get(1) : match.get(0);

                        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
                        fixture.setCompetitionId(locCompetitionId);
                        fixture.setRound(matchday + 1);
                        fixture.setTeam1Id(home);
                        fixture.setTeam2Id(away);
                        fixture.setSeasonNumber(getCurrentSeason());
                        competitionTeamInfoMatchRepository.save(fixture);
                    }
                    matchday++;
                }
            }
            System.out.println("  Group " + group.getKey() + ": " + teams.size() + " teams, " + matchday + " matchdays (rounds 2-" + matchday + ")");
        }
    }

    private void qualifyFromGroupStage(long locCompetitionId) {
        long currentSeason = Long.parseLong(getCurrentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getGroupNumber() > 0)
                .toList();

        Map<Integer, List<Long>> groups = new HashMap<>();
        for (CompetitionTeamInfo cti : entries) {
            groups.computeIfAbsent(cti.getGroupNumber(), k -> new ArrayList<>()).add(cti.getTeamId());
        }

        System.out.println("=== qualifyFromGroupStage: " + groups.size() + " groups, entries=" + entries.size());

        Set<Long> alreadyQualified = new HashSet<>();
        for (Map.Entry<Integer, List<Long>> group : groups.entrySet()) {
            List<Long> teamIds = group.getValue().stream().distinct().toList();
            List<TeamCompetitionDetail> standings = teamIds.stream()
                    .map(tid -> teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(tid, locCompetitionId))
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            System.out.println("  Group " + group.getKey() + ": " + teamIds.size() + " teams, standings=" + standings.size());

            // Top 2 qualify for knockout round 8
            for (int i = 0; i < Math.min(2, standings.size()); i++) {
                long teamId = standings.get(i).getTeamId();
                if (alreadyQualified.contains(teamId)) {
                    System.out.println("  DUPLICATE team " + teamId + " skipped!");
                    continue;
                }
                alreadyQualified.add(teamId);
                CompetitionTeamInfo cti = new CompetitionTeamInfo();
                cti.setTeamId(teamId);
                cti.setCompetitionId(locCompetitionId);
                cti.setSeasonNumber(currentSeason);
                cti.setRound(8);
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Qualified: " + teamName + " (id=" + teamId + ") from group " + group.getKey());
            }
        }
        System.out.println("=== Total qualified for QF: " + alreadyQualified.size());
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

        initializeSpecialPlayers();
    }

    private void initializeCompetitions() {

        List<List<Integer>> values = new ArrayList<>(List.of(List.of(1, 1, 1), List.of(1, 2, 2), List.of(3, 1, 1),
                List.of(3, 2, 2), List.of(3, 3, 3), List.of(2, 2, 1), List.of(2, 3, 2), List.of(4, 2, 1), List.of(4, 3, 2),
                List.of(0, 1, 4), List.of(0, 2, 5),
                List.of(5, 1, 1), List.of(5, 2, 2),
                List.of(6, 1, 1), List.of(6, 2, 2)));

        List<String> names = new ArrayList<>(List.of("Gallactick Football First League", "Gallactick Football Cup",
                "Khess First League", "Khess Cup", "Khess Second League", "Dong Championship", "Dong Cup", "FootieCup League",
                "FootieCup Cup", "League of Champions", "Stars Cup",
                "Cards League", "Cards Cup", "Literature League", "Literature Cup"));

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

    private void initializeSpecialPlayers() {

        Human Kvekrpur = new Human();
        Kvekrpur.setRating(300);
        Kvekrpur.setName("Kvekrpur");
        Kvekrpur.setTeamId(14L); // Tik Tok
        Kvekrpur.setAge(20);
        Kvekrpur.setMorale(100D);
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
            team.setTransferBudget((long) teamValues.get(i).get(0) * 500L);
            team.setTotalFinances((long) teamValues.get(i).get(0) * 750L);
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
            manager.setMorale(100D);
            manager.setFitness(100D);
            manager.setRating(0);
            manager.setTacticStyle("442");
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

    private static final long HUMAN_TEAM_ID = 1L; // The user's team
    private Set<Long> injuredPlayerIdsCache = new HashSet<>();

    private void generateMatchReport(long competitionId, long roundId, long teamId1, long teamId2, int teamScore1, int teamScore2) {
        // Only generate inbox reports for the human player's team
        if (teamId1 != HUMAN_TEAM_ID && teamId2 != HUMAN_TEAM_ID) return;

        String teamName1 = teamRepository.findById(teamId1).map(Team::getName).orElse("Unknown");
        String teamName2 = teamRepository.findById(teamId2).map(Team::getName).orElse("Unknown");
        String competitionName = competitionRepository.findById(competitionId).map(Competition::getName).orElse("Unknown");
        int seasonNumber = Integer.parseInt(getCurrentSeason());
        int roundNumber = (int) roundId;

        if (teamId1 == HUMAN_TEAM_ID) {
            generateMatchReportForTeam(teamId1, teamName1, teamId2, teamName2, teamScore1, teamScore2,
                    competitionName, seasonNumber, roundNumber);
        }
        if (teamId2 == HUMAN_TEAM_ID) {
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

    private void processInjuriesForTeam(long teamId) {
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

    @GetMapping("/teamTalkStatus")
    public Map<String, Object> getTeamTalkStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("used", teamTalkUsedThisRound);
        status.put("round", round.getRound());
        return status;
    }

    @PostMapping("/teamTalk")
    public Map<String, Object> giveTeamTalk(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();

        if (teamTalkUsedThisRound) {
            response.put("success", false);
            response.put("message", "Team talk already used this round.");
            return response;
        }

        String type = body.get("type");
        if (type == null || type.isEmpty()) {
            response.put("success", false);
            response.put("message", "Talk type is required.");
            return response;
        }

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(HUMAN_TEAM_ID, TypeNames.PLAYER_TYPE);
        Random random = new Random();
        double totalMoraleChange = 0;
        int playersAffected = 0;
        List<Map<String, Object>> playerChanges = new ArrayList<>();

        for (Human player : players) {
            double change = 0;
            double currentMorale = player.getMorale();

            switch (type) {
                case "calm":
                    change = random.nextDouble(2, 6); // +2 to +5
                    break;
                case "motivated":
                    if (currentMorale > 100 && random.nextDouble() < 0.25) {
                        change = -3; // backfire for high morale players
                    } else {
                        change = random.nextDouble(5, 11); // +5 to +10
                    }
                    break;
                case "aggressive":
                    if (currentMorale > 90 && random.nextDouble() < 0.4) {
                        change = random.nextDouble(-10, -5); // backfire
                    } else {
                        change = random.nextDouble(8, 16); // +8 to +15
                    }
                    break;
                case "no_pressure":
                    change = random.nextDouble(1, 4); // +1 to +3
                    break;
                default:
                    response.put("success", false);
                    response.put("message", "Invalid talk type: " + type);
                    return response;
            }

            double newMorale = player.getMorale() + change;
            newMorale = Math.min(newMorale, 120D);
            newMorale = Math.max(newMorale, 30D);
            player.setMorale(newMorale);

            totalMoraleChange += change;
            playersAffected++;

            Map<String, Object> pc = new HashMap<>();
            pc.put("playerName", player.getName());
            pc.put("change", Math.round(change * 10.0) / 10.0);
            playerChanges.add(pc);
        }

        humanRepository.saveAll(players);
        teamTalkUsedThisRound = true;

        double avgChange = playersAffected > 0 ? totalMoraleChange / playersAffected : 0;

        response.put("success", true);
        response.put("type", type);
        response.put("averageMoraleChange", Math.round(avgChange * 10.0) / 10.0);
        response.put("playersAffected", playersAffected);
        response.put("playerChanges", playerChanges);
        response.put("message", getTeamTalkMessage(type, avgChange));

        return response;
    }

    private String getTeamTalkMessage(String type, double avgChange) {
        String changeStr = (avgChange >= 0 ? "+" : "") + String.format("%.1f", avgChange);
        switch (type) {
            case "calm":
                return "Your calm words settled the squad. Average morale change: " + changeStr;
            case "motivated":
                return "You fired up the team with a motivational speech. Average morale change: " + changeStr;
            case "aggressive":
                return "Your aggressive talk divided opinions in the dressing room. Average morale change: " + changeStr;
            case "no_pressure":
                return "You eased the pressure on the squad. Average morale change: " + changeStr;
            default:
                return "Team talk delivered. Average morale change: " + changeStr;
        }
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

            for (Competition comp : allCompetitions) {
                if (!teamCompIds.contains(comp.getId())) continue;

                SeasonObjective objective = new SeasonObjective();
                objective.setTeamId(team.getId());
                objective.setSeasonNumber(season);
                objective.setCompetitionId(comp.getId());
                objective.setCompetitionName(comp.getName());
                objective.setStatus("active");

                if (comp.getTypeId() == 1) { // League
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int numTeams = (int) allDetails.stream().filter(d -> d.getCompetitionId() == comp.getId()).count();
                    if (numTeams == 0) numTeams = 12;
                    if (reputation >= 200) {
                        objective.setTargetValue(3);
                        objective.setDescription("Finish in the top 3");
                    } else if (reputation >= 150) {
                        objective.setTargetValue(5);
                        objective.setDescription("Finish in the top 5");
                    } else if (reputation >= 100) {
                        objective.setTargetValue(numTeams / 2);
                        objective.setDescription("Finish in the top half");
                    } else {
                        objective.setTargetValue(numTeams - 2);
                        objective.setDescription("Avoid relegation");
                    }
                } else if (comp.getTypeId() == 3) { // Second League
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int numTeams = (int) allDetails.stream().filter(d -> d.getCompetitionId() == comp.getId()).count();
                    if (numTeams == 0) numTeams = 12;
                    if (reputation >= 100) {
                        objective.setTargetValue(2);
                        objective.setDescription("Win promotion (top 2)");
                    } else {
                        objective.setTargetValue(numTeams / 2);
                        objective.setDescription("Finish in the top half");
                    }
                } else if (comp.getTypeId() == 2) { // Cup
                    objective.setObjectiveType("cup_round");
                    objective.setImportance("medium");
                    if (reputation >= 200) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the cup");
                    } else if (reputation >= 150) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the cup final");
                    } else {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the cup semi-final");
                    }
                } else if (comp.getTypeId() == 4) { // LoC (European)
                    objective.setObjectiveType("european_round");
                    objective.setImportance("high");
                    if (reputation >= 200) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the League of Champions");
                    } else {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the LoC semi-final");
                    }
                } else if (comp.getTypeId() == 5) { // Stars Cup (European)
                    objective.setObjectiveType("european_round");
                    objective.setImportance("medium");
                    if (reputation >= 150) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the Stars Cup final");
                    } else {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the Stars Cup semi-final");
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

    private void recordManagerHistory(int season, List<TeamCompetitionDetail> allDetails) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<CompetitionHistory> allCompHistory = competitionHistoryRepository.findAll().stream()
                .filter(h -> h.getSeasonNumber() == season)
                .toList();

        for (Team team : allTeams) {
            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst()
                    .orElse(null);

            if (manager == null) continue;

            // Find league details for this team
            TeamCompetitionDetail leagueDetail = allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId())
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
    private void updateManagerReputationAfterMatch(long teamId1, long teamId2, int score1, int score2) {
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

    private double calculateMatchRepChange(int myGoals, int oppGoals, int myTeamRep, int oppTeamRep) {
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
        List<SeasonObjective> humanObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(HUMAN_TEAM_ID, season);
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
            managerFired = true;

            // Remove human manager from team
            Human humanManager = humanRepository.findAllByTeamIdAndTypeId(HUMAN_TEAM_ID, TypeNames.MANAGER_TYPE)
                    .stream().findFirst().orElse(null);
            if (humanManager != null) {
                // Reduce reputation on firing
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
        inbox.setTeamId(HUMAN_TEAM_ID);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(50);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);

        // Fire AI managers that performed very badly
        fireAIManagers(season);
    }

    private void fireAIManagers(int season) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);

        for (Team team : allTeams) {
            if (team.getId() == HUMAN_TEAM_ID) continue;

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
                newManager.setTacticStyle("442");
                humanRepository.save(newManager);

                System.out.println("=== AI MANAGER FIRED: " + manager.getName() + " from " + team.getName()
                        + " | Replaced by: " + newManager.getName() + " ===");
            }
        }
    }

    private void handleContractExpiries(int newSeason) {
        Random random = new Random();
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            if (player.getTeamId() == null) continue;
            if (player.getContractEndSeason() <= 0) continue; // skip players without contract data
            if (player.getContractEndSeason() > newSeason) continue; // contract not yet expired

            if (player.getTeamId() == HUMAN_TEAM_ID) {
                // Human team: send inbox notification
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(HUMAN_TEAM_ID);
                inbox.setSeasonNumber(newSeason);
                inbox.setRoundNumber(1);
                inbox.setTitle("Contract Expired");
                inbox.setContent("Contract expired for " + player.getName()
                        + ". Negotiate renewal or lose him for free.");
                inbox.setCategory("contract");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
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
     * Simulates a single matchday for a specific competition.
     * Called by GameAdvanceService when processing MATCH events from the calendar.
     *
     * @param competitionId the competition to simulate
     * @param matchday the matchday/round number within the competition
     * @param season the current season number
     */
    /**
     * Simulates a matchday: just calls simulateRound (same as old play() logic).
     */
    public void simulateMatchday(long competitionId, int matchday, int season) {
        System.out.println("=== simulateMatchday: competitionId=" + competitionId
                + ", matchday=" + matchday + ", season=" + season + " ===");

        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return;

        int typeId = (int) competition.getTypeId();
        String compIdStr = String.valueOf(competitionId);
        String roundStr = String.valueOf(matchday);

        if (typeId == 2 || typeId == 4 || typeId == 5) {
            this.getFixturesForRound(compIdStr, roundStr);
        }

        if (typeId == 4 && matchday == 2) {
            drawEuropeanGroups(competitionId);
            resetEuropeanStats(competitionId);
            generateGroupStageFixtures(competitionId);
        }

        this.simulateRound(compIdStr, roundStr);

        if (typeId == 4 && matchday == 7) {
            qualifyFromGroupStage(competitionId);
        }
    }

    /**
     * Gets the human team's match result AFTER all matches have been simulated.
     * Called once, only for the human team's competition.
     */
    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season) {
        Map<String, Object> result = new LinkedHashMap<>();
        long humanTeamId = 1L;

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
