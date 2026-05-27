package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.ScoutManagementController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * End-of-season + new-season-setup orchestration extracted from
 * {@link CompetitionController}. Owns {@code processEndOfSeason} +
 * {@code processNewSeasonSetup} and the dedup flags that guard them.
 *
 * <p>Cross-cutting helpers still living on the controller
 * (transfer-market support, training application, historical snapshots,
 * cup-bracket regeneration, etc.) are reached through a
 * {@link Lazy @Lazy} controller back-reference. The previous slice's
 * pattern (orchestrator + European service) — keep the controller as a
 * thin REST/edge layer and let the service own the workflow.
 */
@Service
public class SeasonTransitionService {

    @Autowired
    private UserContext userContext;

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private CompetitionRepository competitionRepository;
    @Autowired
    private SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired
    private ManagerHistoryRepository managerHistoryRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private GameCalendarRepository gameCalendarRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired
    private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    private InjuryRepository injuryRepository;
    @Autowired
    private ScorerRepository scorerRepository;
    @Autowired
    private ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private FinanceService financeService;
    @Autowired
    private StaffService staffService;
    @Autowired
    private HumanService humanService;
    @Autowired
    private CompositeTransferStrategy compositeTransferStrategy;
    @Autowired
    private TeamPostMatchService teamPostMatchService;
    @Autowired
    private EuropeanCompetitionService europeanCompetitionService;
    @Autowired
    private CupBracketService cupBracketService;
    @Autowired
    private FixtureSchedulingService fixtureSchedulingService;
    @Autowired
    private TacticService tacticService;
    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired
    private TrainingScheduleRepository trainingScheduleRepository;
    @Autowired
    private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompositeNameGenerator compositeNameGenerator;
    @Autowired
    private TransferMarketService transferMarketService;
    @Lazy @Autowired
    private SeasonObjectiveService seasonObjectiveService;

    @Lazy @Autowired
    private CompetitionController controllerRef;
    @Lazy @Autowired
    private ScoutManagementController scoutManagementController;

    /** Dedup flags moved over from {@link CompetitionController} so the
     *  service owns its own re-entry protection. */
    private boolean endOfSeasonProcessed = false;
    private int endOfSeasonProcessedForSeason = -1;
    private boolean seasonTransitionInProgress = false;

    // ============================================================
    //  Phase 1 — end-of-season (day 340)
    // ============================================================

    /**
     * Final standings + relegation/promotion bracket setup, AI transfers, AI
     * loans, season-objectives evaluation, transfer window open. Called from
     * {@code GameAdvanceService} via a controller delegate; safe to call
     * multiple times per season (idempotent via {@link #endOfSeasonProcessed}).
     */
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

            List<Long> teamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());

            // Final standings, relegation/promotion
            Set<Long> leagueCompetitionIds = competitionRepository.findIdsByTypeId(1);
            Set<Long> secondLeagueCompetitionIds = competitionRepository.findIdsByTypeId(3);
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
            long currentSeason = currentSeason();
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

                    competitionTeamInfo.setSeasonNumber(currentSeason + 1);
                    competitionTeamInfo.setRound(1L);
                    competitionTeamInfo.setTeamId(teamCompetitionDetail.getTeamId());
                    competitionTeamInfoRepository.save(competitionTeamInfo);

                    Long cupId = leagueToCupMap.get(id);
                    if (cupId != null) {
                        CompetitionTeamInfo competitionTeamInfoCup = new CompetitionTeamInfo();
                        competitionTeamInfoCup.setCompetitionId(cupId);
                        competitionTeamInfoCup.setSeasonNumber(currentSeason + 1);
                        competitionTeamInfoCup.setRound(index <= numByes ? 2L : 1L);
                        competitionTeamInfoCup.setTeamId(teamCompetitionDetail.getTeamId());
                        competitionTeamInfoRepository.save(competitionTeamInfoCup);
                    }
                    index++;
                }
            }

            europeanCompetitionService.qualifyTeamsForEuropeanCompetitions();

            int currentSeasonInt = (int) currentSeason;
            // Refresh team budgets BEFORE AI transfers so teams have money to spend
            refreshTeamBudgets(currentSeasonInt);

            // AI transfer market
            List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
            for (Long teamId : teamIds) {
                if (userContext.isHumanTeam(teamId)) continue;
                Team team = teamRepository.findById(teamId).orElse(new Team());
                playersForTransferMarket.addAll(compositeTransferStrategy.playersToSell(team, humanRepository, tacticService.getMinimumPositionNeeded()));
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
                BuyPlanTransferView buyPlanTransferView = compositeTransferStrategy.playersToBuy(team, humanRepository, tacticService.getMaximumPositionAllowed());
                if (buyPlanTransferView == null) continue;

                for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {
                    List<PlayerTransferView> playersInMarket = transferMarket.get(clubPlan.getPosition());
                    if (playersInMarket == null) continue;
                    for (PlayerTransferView player : playersInMarket) {
                        if (transferMarketService.canBeTransfered(player, buyPlanTransferView, clubPlan)) {
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
                transferMarketService.generateAiOffersForHumanPlayers(team, buyPlanTransferView);
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

                long transferFee = TransferValueCalculator.calculate(playerTransferView.getAge(), playerTransferView.getPosition(), playerTransferView.getRating());
                if (transferFee > buyTeam.getTransferBudget()) continue;

                Human human = humanRepository.findById(playerTransferView.getPlayerId()).get();
                human.setTeamId(buyTeam.getId());
                human.setSeasonMatchesPlayed(0);
                human.setConsecutiveBenched(0);
                humanRepository.save(human);

                // Record transfer as financial transaction
                financeService.recordExpense(buyTeam.getId(), currentSeasonInt, 0,
                        "TRANSFER_BUY", "Bought " + human.getName(), transferFee);
                buyTeam = teamRepository.findById(buyTeam.getId()).orElse(buyTeam);
                buyTeam.setTransferBudget(buyTeam.getTransferBudget() - transferFee);
                teamRepository.save(buyTeam);

                financeService.recordTransaction(sellTeam.getId(), currentSeasonInt, 0,
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
                transfer.setSeasonNumber(currentSeason);
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
                    Loan loan = new Loan();
                    loan.setPlayerId(loanPlayer.getId());
                    loan.setPlayerName(loanPlayer.getName());
                    loan.setParentTeamId(parentTeam.getId());
                    loan.setParentTeamName(parentTeam.getName());
                    loan.setLoanTeamId(loanTeam.getId());
                    loan.setLoanTeamName(loanTeam.getName());
                    loan.setSeasonNumber(currentSeasonInt);
                    loan.setStatus("active");
                    loan.setLoanFee(loanFee);
                    loanRepository.save(loan);
                }
            }

            seasonObjectiveService.evaluateSeasonObjectives(currentSeasonInt);

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
                    inbox.setSeasonNumber(currentSeasonInt);
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
            transferMarketService.setOpen(true);

            endOfSeasonProcessed = true;
            endOfSeasonProcessedForSeason = season;
            System.out.println("=== END OF SEASON " + season + " PROCESSED. " + transfers.size() + " AI transfers. Transfer window open. ===");
        } finally {
            seasonTransitionInProgress = false;
        }
    }

    // ============================================================
    //  Phase 2 — new-season setup (day 360, after transfer window closes)
    // ============================================================

    @Transactional
    public synchronized void processNewSeasonSetup(int season) {
        // Mutate the controller's cached Round so its getCurrentSeason() helper
        // (used downstream by getFixturesForRound) sees the new season. Using a
        // freshly loaded entity would leave the controller's cache stale, and
        // fixture generation would write rows under the OLD season number.
        Round round = controllerRef.getRoundCache();
        // Guard: if round.season already moved past this season, skip (prevents double transition)
        if (round.getSeason() > season) {
            System.out.println("=== processNewSeasonSetup: season " + season + " already transitioned (current=" + round.getSeason() + "), skipping ===");
            return;
        }
        System.out.println("=== processNewSeasonSetup: transitioning from season " + season + " ===");

        List<Long> teamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());

        // Note: refreshTeamBudgets is called in processEndOfSeason before AI transfers

        List<Long> allTeamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());
        applyTrainingEffect(allTeamIds);

        Set<Long> competitions = competitionRepository.findAll()
                .stream()
                .mapToLong(Competition::getId)
                .boxed()
                .collect(Collectors.toSet());
        for (Long competitionId : competitions)
            saveHistoricalValues(competitionId, round.getSeason());
        saveAllPlayerTeamHistoricalRelations(round.getSeason());

        // Return loaned players (handles buy obligations and salary adjustments)
        processLoanReturns((int) round.getSeason());

        resetCompetitionData();
        removeCompetitionData(round.getSeason() + 1);
        addImprovementToOverachievers();

        round.setRound(1);
        round.setSeason(round.getSeason() + 1);
        roundRepository.save(round);

        humanService.addOneYearToAge();
        humanService.retirePlayers();
        personalizedTacticRepository.deleteAll();

        for (Long teamId : teamIds) {
            TeamFacilities teamFacilities = teamFacilitiesRepository.findByTeamId(teamId);
            if (teamFacilities != null)
                humanService.addRegens(teamFacilities, teamId);
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
                human.setTransferValue(TransferValueCalculator.calculate(human.getAge(), human.getPosition(), human.getRating()));
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
        seasonObjectiveService.generateSeasonObjectives((int) round.getSeason());

        // Generate league fixtures for new season
        Set<Long> newLeagueCompIds = competitionRepository.findIdsByTypeId(1);
        Set<Long> newSecondLeagueCompIds = competitionRepository.findIdsByTypeId(3);
        newLeagueCompIds.addAll(newSecondLeagueCompIds);
        for (Long competitionId : newLeagueCompIds)
            fixtureSchedulingService.getFixturesForRound(String.valueOf(competitionId), "1");

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

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L);
    }

    // ==================== LOAN RETURNS ====================

    /**
     * Return all loaned players to their parent clubs at end of season.
     * Processes buy obligations automatically.
     */
    public void processLoanReturns(int season) {
        List<Loan> activeLoans = loanRepository.findAllByStatus("active");

        for (Loan loan : activeLoans) {
            Human player = humanRepository.findById(loan.getPlayerId()).orElse(null);
            if (player == null) {
                loan.setStatus("completed");
                loanRepository.save(loan);
                continue;
            }

            Team loanTeam = teamRepository.findById(loan.getLoanTeamId()).orElse(null);
            Team parentTeam = teamRepository.findById(loan.getParentTeamId()).orElse(null);

            // Check for buy obligation
            if (loan.isBuyObligatory() && loan.getBuyOptionFee() > 0 && loanTeam != null && parentTeam != null) {
                // Obligatory buy: execute permanent transfer
                long fee = loan.getBuyOptionFee();

                player.setTeamId(loan.getLoanTeamId());
                player.setSeasonMatchesPlayed(0);
                player.setConsecutiveBenched(0);
                humanRepository.save(player);

                financeService.recordExpense(loanTeam.getId(), season, 0,
                        "TRANSFER_BUY", "Obligation to buy exercised: " + player.getName(), fee);
                loanTeam = teamRepository.findById(loanTeam.getId()).orElse(loanTeam);
                loanTeam.setTransferBudget(loanTeam.getTransferBudget() - fee);
                teamRepository.save(loanTeam);

                financeService.recordTransaction(parentTeam.getId(), season, 0,
                        "TRANSFER_SALE", "Obligation to buy exercised: " + player.getName(), fee);

                loan.setStatus("buy_obligated");
                loanRepository.save(loan);

                // Notify human teams
                if (userContext.isHumanTeam(loan.getLoanTeamId())) {
                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(loan.getLoanTeamId());
                    inbox.setSeasonNumber(season);
                    inbox.setRoundNumber(0);
                    inbox.setTitle("Buy Obligation Completed");
                    inbox.setContent(player.getName() + " has been permanently signed from " +
                            parentTeam.getName() + " for " + fee + " (obligation to buy).");
                    inbox.setCategory("transfer");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);
                }

                continue;
            }

            // Normal loan return: player goes back to parent club
            long playerWage = player.getWage();
            player.setTeamId(loan.getParentTeamId());
            player.setSeasonMatchesPlayed(0);
            humanRepository.save(player);

            // Adjust salary budgets
            if (loanTeam != null) {
                loanTeam.setSalaryBudget(loanTeam.getSalaryBudget() - playerWage);
                teamRepository.save(loanTeam);
            }
            if (parentTeam != null) {
                parentTeam.setSalaryBudget(parentTeam.getSalaryBudget() + playerWage);
                teamRepository.save(parentTeam);
            }

            loan.setStatus("completed");
            loanRepository.save(loan);

            // Notify human team if their player returned
            if (parentTeam != null && userContext.isHumanTeam(parentTeam.getId())) {
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(parentTeam.getId());
                inbox.setSeasonNumber(season);
                inbox.setRoundNumber(0);
                inbox.setTitle("Loan Return: " + player.getName());
                inbox.setContent(player.getName() + " has returned from loan at " +
                        (loanTeam != null ? loanTeam.getName() : "Unknown") + ".");
                inbox.setCategory("transfer");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }
    }

    // ============================================================
    //  Cup-bracket regeneration (called at season start for each
    //  national cup; replaces old controller-side implementation).
    // ============================================================

    public void regenerateAllCupBrackets(int season) {
        // Resolve cup IDs locally — calling competitionRepository.findIdsByTypeId
        // here would re-enter the controller during its @PostConstruct (initializeRound
        // is the upstream caller at first-boot) and trip Spring's circular-reference guard.
        Set<Long> cupIds = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 2)
                .map(Competition::getId)
                .collect(Collectors.toSet());
        for (Long cupId : cupIds) {
            cupBracketService.generateBracket(cupId, season);
            // generateSeasonCalendar() runs before the bracket exists, so the match rows
            // we just created still have day=0. Sync them with the already-existing
            // MATCH_CUP CalendarEvent rows so they appear on the right day in schedules.
            fixtureSchedulingService.syncCalendarDaysOntoExistingMatches(cupId, season);
        }
    }

    // ============================================================
    //  Scorer-leaderboard season reset (static — called both from
    //  bootstrap initializeRound and from new-season setup below).
    // ============================================================

    public static ScorerLeaderboardEntry resetCurrentSeasonStats(Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry) {
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

    // ============================================================
    //  End-of-season player-rating reward for overachieving scorers.
    // ============================================================

    public void addImprovementToOverachievers() {
        long season = currentSeason();
        System.out.println("Starting rating adjusting for season " + season);
        Random random = new Random();
        List<ScorerLeaderboardEntry> allEntries = scorerLeaderboardRepository.findAll();

        for (ScorerLeaderboardEntry entry : allEntries) {
            if (entry.getCurrentSeasonGames() < 20) continue; // play at least half of the games

            double ratio = 0D;
            if (entry.getCurrentSeasonLeagueGames() > 0 && entry.getCurrentSeasonLeagueGoals() > 0) {
                ratio = (double) entry.getCurrentSeasonLeagueGoals() / entry.getCurrentSeasonLeagueGames();
            } else if (entry.getCurrentSeasonSecondLeagueGames() > 0 && entry.getCurrentSeasonSecondLeagueGoals() > 0) {
                ratio = (double) entry.getCurrentSeasonSecondLeagueGoals() / entry.getCurrentSeasonSecondLeagueGames();
            }

            int ratingIncrease;
            if (ratio >= 1.0D) ratingIncrease = random.nextInt(15, 20);
            else if (ratio >= 0.75) ratingIncrease = random.nextInt(10, 15);
            else if (ratio >= 0.5) ratingIncrease = random.nextInt(7, 10);
            else if (ratio >= 0.4) ratingIncrease = random.nextInt(3, 7);
            else continue;
            if (entry.getLeagueGoals() < entry.getSecondLeagueGoals()) {
                ratingIncrease /= 2; // overachieving in second league is less impressive
            }

            Optional<Human> human = humanRepository.findById(entry.getPlayerId());
            if (human.isPresent()) {
                Human player = human.get();
                System.out.println("Player " + player.getName() + " from team " + entry.getTeamName()
                        + " had rating increased from " + player.getRating() + " to "
                        + (player.getRating() + ratingIncrease) + " because of ratio of " + ratio);
                player.setRating(player.getRating() + ratingIncrease);
                humanRepository.save(player);

                entry.setCurrentRating(player.getRating());
                if (entry.getCurrentRating() > entry.getBestEverRating()) {
                    entry.setBestEverRating(entry.getCurrentRating());
                    entry.setSeasonOfBestEverRating((int) season);
                }
                scorerLeaderboardRepository.save(entry);
            }
        }
    }

    // ============================================================
    //  Historical snapshots (run at season-end before standings reset).
    // ============================================================

    public void saveAllPlayerTeamHistoricalRelations(long seasonNumber) {
        Set<Long> teamIds = new HashSet<>();
        for (TeamCompetitionDetail tcd : teamCompetitionDetailRepository.findAll()) {
            teamIds.add(tcd.getTeamId());
        }
        for (Long teamId : teamIds) {
            List<Human> allTeamPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player : allTeamPlayers) {
                TeamPlayerHistoricalRelation rel = new TeamPlayerHistoricalRelation();
                rel.setPlayerId(player.getId());
                rel.setTeamId(teamId);
                rel.setSeasonNumber(seasonNumber + 1);
                rel.setRating(player.getRating());
                teamPlayerHistoricalRelationRepository.save(rel);
            }
        }
    }

    public void saveHistoricalValues(Long competitionId, Long seasonNumber) {
        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll()
                .stream()
                .filter(tcd -> tcd.getCompetitionId() == competitionId)
                .collect(Collectors.toList());

        Collections.sort(teams, (a, b) -> {
            if (a.getPoints() != b.getPoints()) return a.getPoints() > b.getPoints() ? -1 : 1;
            if (a.getGoalDifference() != b.getGoalDifference()) return a.getGoalDifference() > b.getGoalDifference() ? -1 : 1;
            return a.getGoalsFor() > b.getGoalsFor() ? -1 : 1;
        });

        for (int i = 0; i < teams.size(); i++) {
            TeamCompetitionDetail team = teams.get(i);
            if (team.getCompetitionId() != competitionId) continue;
            competitionHistoryRepository.save(adaptCompetitionHistory(team, seasonNumber, 1 + i));
        }
    }

    private CompetitionHistory adaptCompetitionHistory(TeamCompetitionDetail team, Long seasonNumber, long position) {
        CompetitionHistory ch = new CompetitionHistory();
        Competition competition = competitionRepository.findById(team.getCompetitionId()).get();
        ch.setSeasonNumber(seasonNumber);
        ch.setLastPosition(position);
        ch.setCompetitionTypeId(competition.getTypeId());
        ch.setCompetitionName(competition.getName());
        ch.setCompetitionId(team.getCompetitionId());
        ch.setTeamId(team.getTeamId());
        ch.setGames(team.getGames());
        ch.setWins(team.getWins());
        ch.setDraws(team.getDraws());
        ch.setLoses(team.getLoses());
        ch.setGoalsFor(team.getGoalsFor());
        ch.setGoalsAgainst(team.getGoalsAgainst());
        ch.setGoalDifference(team.getGoalDifference());
        ch.setPoints(team.getPoints());
        ch.setForm(team.getForm());
        return ch;
    }

    // ============================================================
    //  Standings teardown / re-seed for the new season.
    // ============================================================

    public void resetCompetitionData() {
        // Keep CompetitionTeamInfoDetail (match results) for historical viewing.
        // Only delete CompetitionTeamInfoMatch (fixtures) — they're regenerated each season.
        competitionTeamInfoMatchRepository.deleteAll();
    }

    public void removeCompetitionData(Long seasonNumber) {
        teamCompetitionDetailRepository.deleteAll();
        List<CompetitionTeamInfo> ctis = competitionTeamInfoRepository.findAllBySeasonNumber(seasonNumber);
        for (CompetitionTeamInfo team : ctis) {
            TeamCompetitionDetail newTcd = new TeamCompetitionDetail();
            newTcd.setTeamId(team.getTeamId());
            newTcd.setCompetitionId(team.getCompetitionId());
            newTcd.setForm("");
            teamCompetitionDetailRepository.save(newTcd);
        }
    }

    // ============================================================
    //  Season-end training rating boost.
    // ============================================================

    public void applyTrainingEffect(List<Long> teamIds) {
        long season = currentSeason();
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

                double baseBoost = 0.5 + (avgIntensity / 100.0) * 1.5;
                double ageModifier;
                if (player.getAge() < 23) ageModifier = 1.5;
                else if (player.getAge() > 30) ageModifier = 0.5;
                else ageModifier = 1.0;

                double newRating = player.getRating() + baseBoost * ageModifier;
                if (player.getPotentialAbility() > 0 && newRating > player.getPotentialAbility()) {
                    newRating = player.getPotentialAbility();
                }
                player.setRating(newRating);
                if (newRating > player.getBestEverRating()) {
                    player.setBestEverRating(newRating);
                    player.setSeasonOfBestEverRating((int) season);
                }
                playersToSave.add(player);
            }
        }
        humanRepository.saveAll(playersToSave);
    }

    // ============================================================
    //  Contract expiry processing (run at season transition).
    //  Also reachable from GameAdvanceService.handleContractExpiries via delegate.
    // ============================================================

    public void handleContractExpiries(int newSeason) {
        Random random = new Random();
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            if (player.getTeamId() == null) continue;
            if (player.getContractEndSeason() <= 0) continue;
            if (player.getContractEndSeason() > newSeason) continue;

            if (userContext.isHumanTeam(player.getTeamId())) {
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

                Team team = teamRepository.findById(player.getTeamId()).orElse(null);
                if (team != null) {
                    team.setSalaryBudget(Math.max(0, team.getSalaryBudget() - player.getWage()));
                    teamRepository.save(team);
                }
                player.setTeamId(null);
                player.setContractEndSeason(0);
                humanRepository.save(player);
            } else {
                // AI team: 50% auto-renew, 50% free agent
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

    // ============================================================
    //  Season-end team budget refresh (prize money + TV + European
    //  income + owner injection).
    // ============================================================

    public void refreshTeamBudgets(int season) {
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        Set<Long> processedTeamIds = new HashSet<>();

        List<Long> sortedLeagueIds = europeanCompetitionService.getLeagueIdsSortedByCoefficient();
        Map<Long, Integer> leagueTierMap = new HashMap<>();
        for (int i = 0; i < sortedLeagueIds.size(); i++) {
            int tier;
            if (i < 2) tier = 1;
            else if (i < 4) tier = 2;
            else tier = 3;
            leagueTierMap.put(sortedLeagueIds.get(i), tier);
        }

        // 1. League prize money + TV income
        for (Competition comp : allComps) {
            if (comp.getTypeId() != 1 && comp.getTypeId() != 3) continue;

            int tier = 3;
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

                Optional<ClubCoefficient> cc = clubCoefficientRepository
                        .findByTeamIdAndSeasonNumber(detail.getTeamId(), season);
                long europeanIncome = cc.map(c -> (long) (c.getPoints() * 2_000_000L)).orElse(0L);

                int tvTier = (comp.getTypeId() == 3) ? tier + 1 : tier;
                long tvIncome = financeService.calculateTvIncome(position, numTeams, Math.min(tvTier, 3));

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

                team = teamRepository.findById(detail.getTeamId()).orElse(null);
                if (team != null) {
                    team.setTransferBudget((long) (team.getTransferBudget() * 0.85));
                    teamRepository.save(team);
                }

                financeService.updateBoardConfidence(detail.getTeamId(), position, numTeams);
                processedTeamIds.add(detail.getTeamId());
                position++;
            }
        }

        // 2. Cup prize money
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

        // 3. Owner injections
        for (Team team : teamRepository.findAll()) {
            financeService.processOwnerInjection(team.getId(), season);
        }
        // 4. European prizes are awarded per-match via awardEuropeanMatchPrizeMoney().
    }

    // ============================================================
    //  End-of-season manager records + firing decisions.
    //  Triggered by SeasonObjectiveService.evaluateSeasonObjectives.
    // ============================================================

    public void recordManagerHistory(int season, List<TeamCompetitionDetail> allDetails) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<CompetitionHistory> allCompHistory = competitionHistoryRepository.findAll().stream()
                .filter(h -> h.getSeasonNumber() == season)
                .toList();

        Set<Long> leagueCompetitionIds = allCompetitions.stream()
                .filter(c -> c.getTypeId() == 1 || c.getTypeId() == 3)
                .map(Competition::getId)
                .collect(Collectors.toSet());

        for (Team team : allTeams) {
            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst().orElse(null);
            if (manager == null) continue;

            TeamCompetitionDetail leagueDetail = allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId() && leagueCompetitionIds.contains(d.getCompetitionId()))
                    .findFirst().orElse(null);
            if (leagueDetail == null) continue;

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

            List<String> trophies = new ArrayList<>();
            if (leaguePosition == 1) {
                Competition comp = allCompetitions.stream().filter(c -> c.getId() == competitionId).findFirst().orElse(null);
                if (comp != null) trophies.add(comp.getName());
            }
            for (CompetitionHistory ch : allCompHistory) {
                if (ch.getTeamId() == team.getId() && ch.getLastPosition() == 1) {
                    Competition comp = allCompetitions.stream()
                            .filter(c -> c.getId() == ch.getCompetitionId() && (c.getTypeId() == 2 || c.getTypeId() == 4 || c.getTypeId() == 5))
                            .findFirst().orElse(null);
                    if (comp != null) trophies.add(comp.getName());
                }
            }

            boolean promoted = false, relegated = false;
            Competition leagueComp = allCompetitions.stream().filter(c -> c.getId() == competitionId).findFirst().orElse(null);
            if (leagueComp != null) {
                int numTeams = leagueStandings.size();
                if (leagueComp.getTypeId() == 3 && leaguePosition <= 2) promoted = true;
                if (leagueComp.getTypeId() == 1 && leaguePosition >= numTeams - 1) relegated = true;
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

            double repChange = 0;
            if (leaguePosition == 1) repChange += 100;
            else if (leaguePosition == 2) repChange += 40;
            else if (leaguePosition == 3) repChange += 20;

            int numTeams = leagueStandings.size();
            if (leaguePosition == numTeams) repChange -= 50;
            else if (leaguePosition >= numTeams - 2) repChange -= 25;

            for (String trophy : trophies) {
                String lower = trophy.toLowerCase();
                if (lower.contains("champions") || lower.contains("loc") || lower.contains("league of champions")) repChange += 500;
                else if (lower.contains("stars")) repChange += 200;
                else if (lower.contains("cup")) repChange += 75;
            }
            if (promoted) repChange += 50;
            if (relegated) repChange -= 200;

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

    public void checkManagerFiring(int season) {
        for (long humanTeamId : userContext.getAllHumanTeamIds()) {
            checkManagerFiringForTeam(humanTeamId, season);
        }
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
                firingScore += weight * Math.min(obj.getActualValue() - obj.getTargetValue(), 5);
            } else {
                firingScore += weight * Math.min(obj.getTargetValue() - obj.getActualValue(), 3);
            }
        }

        String title, content;
        String category = "board";

        if (firingScore >= 12) {
            title = "You Have Been Sacked!";
            content = "The board has lost all confidence in your ability to manage this club. "
                    + "You have been relieved of your duties with immediate effect. "
                    + "Check the available jobs to find a new position.";
            List<User> usersWithTeam = userRepository.findAllByTeamId(humanTeamId);
            for (User user : usersWithTeam) {
                user.setFired(true);
                user.setLastTeamId(humanTeamId);
                user.setTeamId(null);
                userRepository.save(user);
            }
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
}
