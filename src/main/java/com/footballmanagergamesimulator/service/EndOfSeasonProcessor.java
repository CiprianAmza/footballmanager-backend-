package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * End-of-season pipeline — extracted from {@link SeasonTransitionService}
 * (sesiunea 6, §6.2 Pass B). Owns the heavy work that fires at day 340 of
 * each in-game year:
 * <ul>
 *   <li>{@link #process(int)} — final standings + relegation/promotion bracket
 *       setup, European qualifier generation, AI transfers, AI loans, season
 *       objective evaluation, transfer window open. Synchronised + idempotent
 *       per season via internal guard flags.</li>
 *   <li>{@link #refreshTeamBudgets(int)} — league prize money + TV income +
 *       European prize injection + owner top-ups; called before AI transfers
 *       so teams have budget to spend.</li>
 *   <li>{@link #handleContractExpiries(int)} — players whose contracts have
 *       expired leave the club (human teams notified via inbox + budget
 *       updated; AI teams 50/50 auto-renew or free agent). Public because
 *       {@code GameAdvanceService} also fires it directly on the contract
 *       expiry calendar event.</li>
 *   <li>{@link #reset()} — clears the idempotency flags; called from
 *       {@code processNewSeasonSetup} so the next season can run again.</li>
 * </ul>
 *
 * <p>Coordination: {@link SeasonTransitionService} keeps a thin
 * {@code processEndOfSeason} delegate so existing callers
 * ({@code GameAdvanceService}, IT tests) continue to work without churn.
 */
@Service
public class EndOfSeasonProcessor {

    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private UserContext userContext;
    @Autowired private CompositeTransferStrategy compositeTransferStrategy;
    @Autowired private TacticService tacticService;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;
    @Autowired private TransferMarketService transferMarketService;
    @Autowired private SeasonObjectiveService seasonObjectiveService;
    @Autowired private FinanceService financeService;
    @Autowired @Lazy private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private SuperCupService superCupService;
    @Autowired private TransferOfferLifecycleService transferOfferLifecycleService;
    @Autowired private CompetitionHistorySnapshotService competitionHistorySnapshotService;
    @Autowired private ScorerLeaderboardSyncService scorerLeaderboardSyncService;

    /** Dedup flags — owned by the processor so re-entry protection lives next
     *  to the body that needs it. {@link #reset()} clears them at new-season setup. */
    private boolean endOfSeasonProcessed = false;
    private int endOfSeasonProcessedForSeason = -1;
    private boolean inProgress = false;

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L);
    }

    /** Clears the idempotency guard so a fresh season can process again. */
    public void reset() {
        endOfSeasonProcessed = false;
    }

    // ============================================================
    //  Main entry — full end-of-season pipeline
    // ============================================================

    @Transactional
    public synchronized void process(int season) {
        if (inProgress) {
            System.out.println("=== processEndOfSeason: season " + season + " ALREADY IN PROGRESS, skipping ===");
            return;
        }
        if (endOfSeasonProcessed && endOfSeasonProcessedForSeason == season) {
            System.out.println("=== processEndOfSeason: season " + season + " ALREADY PROCESSED, skipping ===");
            return;
        }
        inProgress = true;
        try {
            System.out.println("=== processEndOfSeason: season " + season + " ===");
            superCupService.ensureCompetitions();

            List<Long> teamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());
            // User membership is stable for this pipeline. Resolve it once:
            // calling isHumanTeam() inside the transfer/loan loops executes a
            // repository query each time and, in this large transaction, makes
            // Hibernate auto-flush thousands of managed entities repeatedly.
            Set<Long> humanTeamIds = new HashSet<>(userContext.getAllHumanTeamIds());

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
            List<TeamCompetitionDetail> newTcds = new ArrayList<>();
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
                        newTcds.add(tcd);
                    }
                }
            }
            teamCompetitionDetailRepository.saveAll(newTcds);

            List<TeamCompetitionDetail> teamCompetitionDetails = teamCompetitionDetailRepository.findAll();
            snapshotFinalStandings(allComps, teamCompetitionDetails, currentSeason);
            // Pre-resolve every team once so the standings comparator's reputation
            // tiebreaker is a map lookup instead of a per-comparison findById (N+1).
            Map<Long, Team> teamsById = teamRepository.findAll().stream()
                    .collect(Collectors.toMap(Team::getId, t -> t, (a, b) -> a));
            Map<Long, Integer> reputationById = new HashMap<>();
            for (Map.Entry<Long, Team> e : teamsById.entrySet())
                reputationById.put(e.getKey(), e.getValue().getReputation());
            List<CompetitionTeamInfo> newStandingsCtis = new ArrayList<>();
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
                            int repA = reputationById.getOrDefault(o1.getTeamId(), 0);
                            int repB = reputationById.getOrDefault(o2.getTeamId(), 0);
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
                    newStandingsCtis.add(competitionTeamInfo);

                    Long cupId = leagueToCupMap.get(id);
                    if (cupId != null) {
                        CompetitionTeamInfo competitionTeamInfoCup = new CompetitionTeamInfo();
                        competitionTeamInfoCup.setCompetitionId(cupId);
                        competitionTeamInfoCup.setSeasonNumber(currentSeason + 1);
                        competitionTeamInfoCup.setRound(index <= numByes ? 2L : 1L);
                        competitionTeamInfoCup.setTeamId(teamCompetitionDetail.getTeamId());
                        newStandingsCtis.add(competitionTeamInfoCup);
                    }
                    index++;
                }
            }
            competitionTeamInfoRepository.saveAll(newStandingsCtis);

            europeanCompetitionService.qualifyTeamsForEuropeanCompetitions();

            int currentSeasonInt = (int) currentSeason;
            // Refresh team budgets BEFORE AI transfers so teams have money to spend
            refreshTeamBudgets(currentSeasonInt);

            // AI transfer market
            List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
            for (Long teamId : teamIds) {
                if (humanTeamIds.contains(teamId)) continue;
                // Read-only lookup — reuse the preloaded map; preserve the new Team() fallback.
                Team team = teamsById.getOrDefault(teamId, new Team());
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
                if (humanTeamIds.contains(teamId)) continue;
                // Read-only lookup — reuse the preloaded map; preserve the new Team() fallback.
                Team team = teamsById.getOrDefault(teamId, new Team());
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
                if (human.isWillNeverLeave()) continue;
                human.setTeamId(buyTeam.getId());
                human.setSeasonMatchesPlayed(0);
                human.setConsecutiveBenched(0);
                humanRepository.save(human);

                // Both squads changed — refresh their cached AI base ratings.
                matchSimulationOrchestrator.invalidateRatingCache(buyTeam.getId());
                matchSimulationOrchestrator.invalidateRatingCache(sellTeam.getId());

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
            // Keep findAll() here: potentialTeams ordering feeds the seeded RNG pick,
            // so the deterministic id-ordered list must be preserved.
            List<Team> allTeams = teamRepository.findAll();
            for (Long teamId : teamIds) {
                if (humanTeamIds.contains(teamId)) continue;
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                if (players.size() <= 18) continue;
                double avgRating = players.stream().mapToDouble(Human::getRating).average().orElse(0);
                List<Human> loanCandidates = players.stream()
                        .filter(p -> p.getAge() <= 22 && p.getRating() < avgRating
                                && !p.isRetired() && !p.isWillNeverLeave())
                        .collect(Collectors.toList());
                Collections.shuffle(loanCandidates);
                int loansToMake = Math.min(loanCandidates.size(), 2);
                for (int i = 0; i < loansToMake; i++) {
                    Human loanPlayer = loanCandidates.get(i);
                    List<Team> potentialTeams = allTeams.stream()
                            .filter(t -> t.getId() != teamId && !humanTeamIds.contains(t.getId()))
                            .collect(Collectors.toList());
                    if (potentialTeams.isEmpty()) continue;
                    Team loanTeam = potentialTeams.get(loanRandom.nextInt(potentialTeams.size()));
                    // Read-only (id + name only) — reuse preloaded map; preserve null-skip semantics.
                    Team parentTeam = teamsById.get(teamId);
                    if (parentTeam == null) continue;
                    long loanFee = (long) (loanPlayer.getTransferValue() * 0.05);
                    loanPlayer.setTeamId(loanTeam.getId());
                    humanRepository.save(loanPlayer);
                    // Parent loses the player, loan team gains it — refresh both caches.
                    matchSimulationOrchestrator.invalidateRatingCache(parentTeam.getId());
                    matchSimulationOrchestrator.invalidateRatingCache(loanTeam.getId());
                    Loan loan = new Loan();
                    loan.setPlayerId(loanPlayer.getId());
                    loan.setPlayerName(loanPlayer.getName());
                    loan.setParentTeamId(parentTeam.getId());
                    loan.setParentTeamName(parentTeam.getName());
                    loan.setLoanTeamId(loanTeam.getId());
                    loan.setLoanTeamName(loanTeam.getName());
                    int loanSeason = currentSeasonInt + 1;
                    loan.setSeasonNumber(loanSeason);
                    loan.setStartSeason(loanSeason);
                    loan.setEndSeason(loanSeason);
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
                for (long htId : humanTeamIds) {
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
            inProgress = false;
        }
    }

    /**
     * Persist the completed table while it is still intact. The season-summary
     * screen is available before the user starts the next season, so deferring
     * this snapshot to new-season setup left it empty at the exact moment it was
     * needed. The guard also makes end-of-season retries idempotent.
     */
    private void snapshotFinalStandings(
            List<Competition> competitions,
            List<TeamCompetitionDetail> details,
            long season) {
        if (!competitionHistoryRepository.findAllBySeasonNumber(season).isEmpty()) return;

        Map<Long, Competition> competitionsById = competitions.stream()
                .collect(Collectors.toMap(Competition::getId, competition -> competition));
        Map<Long, List<TeamCompetitionDetail>> detailsByCompetition = details.stream()
                .collect(Collectors.groupingBy(detail -> (long) detail.getCompetitionId()));
        List<CompetitionHistory> history = new ArrayList<>();

        for (Map.Entry<Long, List<TeamCompetitionDetail>> entry : detailsByCompetition.entrySet()) {
            Competition competition = competitionsById.get(entry.getKey());
            if (competition == null) continue;
            List<TeamCompetitionDetail> standings = new ArrayList<>(entry.getValue());
            if (competition.getTypeId() == 2 || competition.getTypeId() == 4
                    || competition.getTypeId() == 5 || competition.getTypeId() == 6) {
                sortCupFinish(standings, competition.getId(), season);
            } else {
                standings.sort((left, right) -> {
                    if (left.getPoints() != right.getPoints())
                        return Integer.compare(right.getPoints(), left.getPoints());
                    if (left.getGoalDifference() != right.getGoalDifference())
                        return Integer.compare(right.getGoalDifference(), left.getGoalDifference());
                    return Integer.compare(right.getGoalsFor(), left.getGoalsFor());
                });
            }

            for (int index = 0; index < standings.size(); index++) {
                TeamCompetitionDetail detail = standings.get(index);
                CompetitionHistory snapshot = new CompetitionHistory();
                snapshot.setTeamId(detail.getTeamId());
                snapshot.setCompetitionId(detail.getCompetitionId());
                snapshot.setSeasonNumber(season);
                snapshot.setCompetitionTypeId(competition.getTypeId());
                snapshot.setCompetitionName(competition.getName());
                snapshot.setGames(detail.getGames());
                snapshot.setWins(detail.getWins());
                snapshot.setDraws(detail.getDraws());
                snapshot.setLoses(detail.getLoses());
                snapshot.setGoalsFor(detail.getGoalsFor());
                snapshot.setGoalsAgainst(detail.getGoalsAgainst());
                snapshot.setGoalDifference(detail.getGoalDifference());
                snapshot.setPoints(detail.getPoints());
                snapshot.setForm(detail.getForm());
                snapshot.setLastPosition(index + 1L);
                history.add(snapshot);
            }
        }
        competitionHistorySnapshotService.capture(history, season);
        competitionHistoryRepository.saveAll(history);
    }

    private void sortCupFinish(
            List<TeamCompetitionDetail> standings,
            long competitionId,
            long season) {
        List<CompetitionTeamInfoDetail> matches =
                competitionTeamInfoDetailRepository.findAllByCompetitionIdAndSeasonNumber(
                        competitionId, season);
        if (matches.isEmpty()) {
            standings.sort((left, right) -> Integer.compare(right.getPoints(), left.getPoints()));
            return;
        }

        long finalRound = matches.stream()
                .mapToLong(CompetitionTeamInfoDetail::getRoundId)
                .max().orElse(0);
        CompetitionTeamInfoDetail finalDecision = matches.stream()
                .filter(match -> match.getRoundId() == finalRound && match.getWinnerTeamId() != null)
                .findFirst().orElse(null);
        Long winnerId = finalDecision != null ? finalDecision.getWinnerTeamId() : null;
        Long runnerUpId = null;
        if (finalDecision != null) {
            runnerUpId = finalDecision.getTeam1Id() == winnerId
                    ? finalDecision.getTeam2Id() : finalDecision.getTeam1Id();
        }
        final Long finalWinnerId = winnerId;
        final Long finalRunnerUpId = runnerUpId;

        Map<Long, Long> roundReached = new HashMap<>();
        for (CompetitionTeamInfoDetail match : matches) {
            roundReached.merge(match.getTeam1Id(), match.getRoundId(), Math::max);
            roundReached.merge(match.getTeam2Id(), match.getRoundId(), Math::max);
        }
        standings.sort((left, right) -> {
            int leftRank = Objects.equals(left.getTeamId(), finalWinnerId) ? 0
                    : Objects.equals(left.getTeamId(), finalRunnerUpId) ? 1 : 2;
            int rightRank = Objects.equals(right.getTeamId(), finalWinnerId) ? 0
                    : Objects.equals(right.getTeamId(), finalRunnerUpId) ? 1 : 2;
            if (leftRank != rightRank) return Integer.compare(leftRank, rightRank);
            return Long.compare(
                    roundReached.getOrDefault(right.getTeamId(), 0L),
                    roundReached.getOrDefault(left.getTeamId(), 0L));
        });
    }

    // ============================================================
    //  Contract expiries — players whose contracts ended this season leave
    //  (human teams via inbox + budget update; AI teams 50/50 auto-renew)
    // ============================================================

    public void handleContractExpiries(int newSeason) {
        Random random = new Random();
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Set<Long> humanTeamIds = new HashSet<>(userContext.getAllHumanTeamIds());

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            if (player.getTeamId() == null) continue;
            if (player.getContractEndSeason() <= 0) continue;
            if (player.getContractEndSeason() > newSeason) continue;

            if (player.isWillNeverLeave()) {
                Long finalTeamId = player.getTeamId();
                Team team = finalTeamId == null ? null : teamRepository.findById(finalTeamId).orElse(null);
                if (team != null) {
                    team.setSalaryBudget(Math.max(0, team.getSalaryBudget() - player.getWage()));
                    teamRepository.save(team);
                }
                if (finalTeamId != null && humanTeamIds.contains(finalTeamId)) {
                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(finalTeamId);
                    inbox.setSeasonNumber(newSeason);
                    inbox.setRoundNumber(1);
                    inbox.setTitle("Player Retired - Contract Completed");
                    inbox.setContent(player.getName() + " has retired after completing their final contract with the club.");
                    inbox.setCategory("contract");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);
                }
                player.setTeamId(null);
                player.setRetired(true);
                player.setCurrentStatus("Retired");
                player.setContractEndSeason(0);
                player.setPreContractTeamId(0);
                player.setWantsTransfer(false);
                humanRepository.save(player);
                scorerLeaderboardSyncService.trackNewPlayer(player);
                transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
                continue;
            }

            if (humanTeamIds.contains(player.getTeamId())) {
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
                transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
            } else {
                // AI team: 50% auto-renew, 50% free agent
                if (random.nextBoolean()) {
                    player.setContractEndSeason(newSeason + random.nextInt(2, 5));
                    player.setWage(WageService.baseWage(player.getRating()));
                    humanRepository.save(player);
                } else {
                    player.setTeamId(null);
                    humanRepository.save(player);
                    transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
                }
            }
        }
    }

    // ============================================================
    //  Team budget refresh — league prize + TV + European + owner
    // ============================================================

    public void refreshTeamBudgets(int season) {
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        Set<Long> processedTeamIds = new HashSet<>();

        List<Long> sortedLeagueIds = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
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
}
