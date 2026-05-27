package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.ScoutManagementController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.user.UserContext;
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
            Set<Long> leagueCompetitionIds = controllerRef.getCompetitionIdsByCompetitionType(1);
            Set<Long> secondLeagueCompetitionIds = controllerRef.getCompetitionIdsByCompetitionType(3);
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
            controllerRef.refreshTeamBudgets(currentSeasonInt);

            // AI transfer market
            List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
            for (Long teamId : teamIds) {
                if (userContext.isHumanTeam(teamId)) continue;
                Team team = teamRepository.findById(teamId).orElse(new Team());
                playersForTransferMarket.addAll(compositeTransferStrategy.playersToSell(team, humanRepository, controllerRef.getMinimumPositionNeeded()));
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
                BuyPlanTransferView buyPlanTransferView = compositeTransferStrategy.playersToBuy(team, humanRepository, controllerRef.getMaximumPositionAllowed());
                if (buyPlanTransferView == null) continue;

                for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {
                    List<PlayerTransferView> playersInMarket = transferMarket.get(clubPlan.getPosition());
                    if (playersInMarket == null) continue;
                    for (PlayerTransferView player : playersInMarket) {
                        if (controllerRef.canBeTransfered(player, buyPlanTransferView, clubPlan)) {
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
                controllerRef.generateAiOffersForHumanPlayers(team, buyPlanTransferView);
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

            controllerRef.evaluateSeasonObjectives(currentSeasonInt);

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
            controllerRef.setTransferWindowOpen(true);

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
        controllerRef.applyTrainingEffect(allTeamIds);

        Set<Long> competitions = competitionRepository.findAll()
                .stream()
                .mapToLong(Competition::getId)
                .boxed()
                .collect(Collectors.toSet());
        for (Long competitionId : competitions)
            controllerRef.saveHistoricalValues(competitionId, round.getSeason());
        controllerRef.saveAllPlayerTeamHistoricalRelations(round.getSeason());

        // Return loaned players (handles buy obligations and salary adjustments)
        processLoanReturns((int) round.getSeason());

        controllerRef.resetCompetitionData();
        controllerRef.removeCompetitionData(round.getSeason() + 1);
        controllerRef.addImprovementToOverachievers();

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

        controllerRef.handleContractExpiries((int) round.getSeason());
        scoutManagementController.processExpiredContracts((int) round.getSeason());
        controllerRef.generateSeasonObjectives((int) round.getSeason());

        // Generate league fixtures for new season
        Set<Long> newLeagueCompIds = controllerRef.getCompetitionIdsByCompetitionType(1);
        Set<Long> newSecondLeagueCompIds = controllerRef.getCompetitionIdsByCompetitionType(3);
        newLeagueCompIds.addAll(newSecondLeagueCompIds);
        for (Long competitionId : newLeagueCompIds)
            controllerRef.getFixturesForRound(String.valueOf(competitionId), "1");

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
                    leaderboardUpdates.add(CompetitionController.resetCurrentSeasonStats(optionalScorerLeaderboardEntry));
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
        controllerRef.regenerateAllCupBrackets((int) round.getSeason());

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
}
