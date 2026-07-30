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
import com.footballmanagergamesimulator.transfermarket.MatchingPass;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.SquadDepthChart;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *       setup, European qualifier generation, squad attrition, AI transfers,
 *       AI loans, season objective evaluation, transfer window open.
 *       Synchronised + idempotent per season via internal guard flags.</li>
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
 * <p><b>Squad attrition runs here, not in {@code processNewSeasonSetup}.</b> Loan
 * returns, training, ageing, retirements and contract expiries all used to fire
 * after the window had closed, so the AI market planned against squads that were
 * about to change underneath it — and, because no contract had expired yet, there
 * were never any free agents for it to sign. They now run immediately before the
 * market, in that order (training reads age, so it stays ahead of the birthday).
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
    @Autowired private LeagueStrengthService leagueStrengthService;
    @Autowired private com.footballmanagergamesimulator.config.LeaguePrizePoolConfig leaguePrizePoolConfig;
    @Autowired private TransferMarketService transferMarketService;
    @Autowired private SeasonObjectiveService seasonObjectiveService;
    @Autowired private FinanceService financeService;
    @Autowired @Lazy private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private SuperCupService superCupService;
    @Autowired private TransferOfferLifecycleService transferOfferLifecycleService;
    @Autowired private CompetitionHistorySnapshotService competitionHistorySnapshotService;
    @Autowired private ScorerLeaderboardSyncService scorerLeaderboardSyncService;
    @Autowired private CoachPermissionService coachPermissionService;
    @Autowired private HumanService humanService;
    @Autowired private NewSeasonPlayerReadinessService newSeasonPlayerReadinessService;
    @Autowired private MinimumSquadService minimumSquadService;
    /** Lazy: NewSeasonSetupProcessor already depends on this class. Squad attrition
     *  (loan returns + training) lives there but must run before the AI market. */
    @Autowired @Lazy private NewSeasonSetupProcessor newSeasonSetupProcessor;
    @Autowired private com.footballmanagergamesimulator.config.MatchEngineConfig matchEngineConfig;

    /**
     * The squads the last transfer window actually planned against — after training,
     * ageing, retirements and contract expiries, before a single transfer.
     *
     * <p>That instant is not observable from outside {@link #process(int)}, and it is
     * the only correct baseline for "what did the market change": a snapshot taken
     * before {@code process} also contains a season of attrition, which swamps the
     * transfers. Kept as a read-only diagnostic; nothing in the pipeline reads it.
     */
    private volatile Map<Long, SquadDepthChart> lastPreMarketDepthCharts = Map.of();

    /** @see #lastPreMarketDepthCharts */
    public Map<Long, SquadDepthChart> lastPreMarketDepthCharts() {
        return lastPreMarketDepthCharts;
    }

    /**
     * Where a club's transfer budget came from, split into the three phases of
     * {@link #refreshTeamBudgets(int)}.
     *
     * <p>The headline number is not this season's earnings: it is last season's
     * leftovers plus a slice of this season's income, and the two are easy to
     * mistake for each other. A club that finished 14th can carry a bigger budget
     * than the club that finished 13th simply by having banked more — from an
     * owner, from selling well, or from not spending. Splitting the phases makes
     * that visible instead of surprising.
     *
     * @param opening    budget carried in from previous seasons, before anything is added
     * @param prizeMoney gross league prize awarded for the final position
     * @param tvIncome   gross broadcast income for the final position
     * @param european   gross European income from last season's club coefficient
     * @param afterPrizes budget once prizes are credited and the 15% carry-over decay applies
     * @param ownerInjection budget added by the owner
     * @param extraFunding   budget added by nation/strategy top-ups
     * @param closing    final budget the transfer market gets to spend
     */
    public record BudgetBreakdown(long opening, long prizeMoney, long tvIncome, long european,
                                  long afterPrizes, long ownerInjection, long extraFunding,
                                  long closing) {}

    private volatile Map<Long, BudgetBreakdown> lastBudgetBreakdowns = Map.of();

    /** @see BudgetBreakdown */
    public Map<Long, BudgetBreakdown> lastBudgetBreakdowns() {
        return lastBudgetBreakdowns;
    }

    /** What each club set out to buy in the last window, keyed by team. Read-only
     *  diagnostic: it answers "why did my club sign that" without re-deriving it. */
    private volatile Map<Long, BuyPlanTransferView> lastBuyPlans = Map.of();

    /**
     * The squads the buy plans were actually drawn against — the pre-market chart with
     * that club's own sale list already removed. Distinct from
     * {@link #lastPreMarketDepthCharts}, which is the baseline for "what changed":
     * a position can be a weak link in one and not the other, precisely because the
     * club decided to sell somebody. Anything auditing a buying decision has to use
     * this one, or it judges the decision against an input it never had.
     */
    private volatile Map<Long, SquadDepthChart> lastPlanningDepthCharts = Map.of();

    /** @see #lastPlanningDepthCharts */
    public Map<Long, SquadDepthChart> lastPlanningDepthCharts() {
        return lastPlanningDepthCharts;
    }

    /** @see #lastBuyPlans */
    public Map<Long, BuyPlanTransferView> lastBuyPlans() {
        return lastBuyPlans;
    }

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
                if (league.isLeague()) {
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

            // ---- Squad attrition, applied BEFORE the market ------------------
            // These all used to run in processNewSeasonSetup, i.e. after the window
            // had closed. The market therefore planned against squads that were
            // about to change underneath it: it valued players at last season's age,
            // traded 34-year-olds with a coin-flip chance of retiring days later,
            // counted retiring players as XI cover for holes they were about to
            // leave, and — worst — ran before any contract expired, so the free
            // agent pool it could sign from was always empty.
            //
            // Order is load-bearing: training reads age, so it must stay ahead of
            // the birthday; retirement reads the new age; expiries release the
            // players the market then gets to sign.
            newSeasonSetupProcessor.processLoanReturns(currentSeasonInt);
            newSeasonSetupProcessor.applyTrainingEffect(teamIds);
            humanService.addOneYearToAge();
            humanService.retirePlayers();
            handleContractExpiries(currentSeasonInt + 1);
            // Condition is restored before the market, not after it. Fitness is at its
            // season-end floor here — every squad exhausted — and the XI is picked on
            // current condition, so the market would otherwise plan against elevens
            // chosen by who happened to be least tired.
            newSeasonPlayerReadinessService.resetActiveTeamPlayers();
            // Backfill anyone left short of a fieldable squad before the market opens.
            // Three seasons of retirements and expiries can take a club under eleven
            // players, and the safety net ran only in processNewSeasonSetup — after the
            // window. The market was planning against squads that could not field a
            // side, and clubs shopped as though positions were empty by choice.
            minimumSquadService.ensureMinimumSquads(currentSeasonInt + 1);
            // Squads changed wholesale, so every cached XI must go. Per team, NOT
            // invalidateAllRatingCaches(): the blanket call also drops the manager's
            // derived tactic vector, which is a 900-combination search per club. The
            // squad changed; the manager's tactical preference did not. Clearing it
            // here forced 106 cold tactic derivations and made the window take over
            // two minutes, against 79ms for the actual matching. The full clear still
            // happens at the real season boundary in processNewSeasonSetup.
            for (Long teamId : teamIds) {
                matchSimulationOrchestrator.invalidateSquadCaches(teamId);
            }

            // ---- AI transfer market -----------------------------------------
            List<Transfer> transfers = runAiTransferMarket(
                    teamIds, humanTeamIds, teamsById, currentSeasonInt, currentSeason);

            // The window has just redistributed the squads the strength table is
            // built from, so the ladder is redrawn here and printed. It is not
            // stored: refreshTeamBudgets recomputes it from live squads when it
            // pays out, which is a full season later. That gap is deliberate — a
            // division that buys its way up is paid for it next season, not this
            // one, which keeps money -> better squads -> more money from running
            // away with itself inside a single window.
            logLeagueStrengthLadder(currentSeasonInt);

            // AI Loan Logic
            Random loanRandom = new Random();
            // Keep findAll() here: potentialTeams ordering feeds the seeded RNG pick,
            // so the deterministic id-ordered list must be preserved.
            List<Team> allTeams = teamRepository.findAll();
            // One query for every squad instead of one per club. Read AFTER the market,
            // not reusing the pre-market snapshot: transfers have just moved players, and
            // loaning out somebody who was sold minutes ago would be a real bug.
            List<Long> loanTeamIds = teamIds.stream().filter(id -> !humanTeamIds.contains(id)).toList();
            Map<Long, List<Human>> squadsForLoans = humanRepository
                    .findAllByTeamIdInAndTypeIdAndRetiredFalse(loanTeamIds, TypeNames.PLAYER_TYPE)
                    .stream()
                    .filter(player -> player.getTeamId() != null)
                    .collect(Collectors.groupingBy(Human::getTeamId));
            List<Human> loanedPlayers = new ArrayList<>();
            List<Loan> newLoans = new ArrayList<>();
            for (Long teamId : teamIds) {
                if (humanTeamIds.contains(teamId)) continue;
                List<Human> players = squadsForLoans.getOrDefault(teamId, List.of());
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
                    loanedPlayers.add(loanPlayer);
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
                    newLoans.add(loan);
                }
            }
            if (!loanedPlayers.isEmpty()) humanRepository.saveAll(loanedPlayers);
            if (!newLoans.isEmpty()) loanRepository.saveAll(newLoans);

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

    // ============================================================
    //  AI transfer market
    // ============================================================

    /**
     * One transfer window, run twice.
     *
     * <p>Every club is first reduced to a {@link SquadDepthChart} — the XI the match
     * engine would actually field, everybody else, and the effective strength of the
     * incumbent at each position. Strategies then list players out of those two sets
     * and shop for the positions dragging their XI down hardest. A signing is agreed
     * when it beats the incumbent (the player starts) or when the buying club is far
     * enough above him that a bench seat is still a step up.
     *
     * <p>Supply includes free agents at zero fee. They were previously created after
     * this window closed and never signed by anyone, so released players simply
     * accumulated for the life of the save.
     *
     * <p>The second pass re-offers everything unsold on softer terms and resets the
     * slot allowance of clubs that did no business, so a club is not locked out of a
     * whole season because its first-choice targets went elsewhere.
     */
    /** How many times the window re-reads the squads and lets clubs trade again. */
    private static final int MARKET_ROUNDS = 3;

    private List<Transfer> runAiTransferMarket(List<Long> teamIds,
                                               Set<Long> humanTeamIds,
                                               Map<Long, Team> teamsById,
                                               int seasonInt,
                                               long season) {
        Map<String, Integer> minimumPositions = tacticService.getMinimumPositionNeeded();
        Map<String, Integer> maximumPositions = tacticService.getMaximumPositionAllowed();
        Map<String, Integer> funnel = new LinkedHashMap<>();

        List<Long> aiTeamIds = teamIds.stream()
                .filter(id -> !humanTeamIds.contains(id))
                .sorted()
                .toList();

        Map<Long, SquadDepthChart> depthCharts = new LinkedHashMap<>();
        List<PlayerTransferView> unsoldFreeAgents = List.of();
        List<Transfer> transfers = new ArrayList<>();
        Set<Long> clubsThatBought = new HashSet<>();
        Set<Long> movedThisWindow = new HashSet<>();
        Map<Long, Long> budgetLeft = new HashMap<>();
        // How many players each club may still part with THIS WINDOW. The strategy
        // decides an appetite per call, which was a window-level cap while the market
        // ran once; across rounds it became a cap per round, and a club could sell
        // three to five players three times over and dismantle itself.
        Map<Long, Integer> sellAllowance = new HashMap<>();
        long chartMs = 0;
        long matchMs = 0;

        // Clubs whose squad has changed since their chart was built. Round one is
        // everybody; after that only whoever actually traded, because rebuilding a
        // chart costs a squad load plus a formation pass and most clubs stand still.
        Set<Long> stale = new LinkedHashSet<>(aiTeamIds);

        for (int round = 0; round < MARKET_ROUNDS; round++) {
            boolean lastRound = round == MARKET_ROUNDS - 1;
            // The final round is the clearance sale: softer bars, discounted fees, so
            // a club that missed its targets is not locked out for a whole season.
            MatchingPass pass = lastRound ? MatchingPass.CLEARANCE : MatchingPass.PRIMARY;

            // 1. Refresh the squads that moved. A club that strengthened its worst
            //    position now reads a higher average, so the next weakest link shows
            //    up — which is the point of running more than one round.
            long tCharts = System.currentTimeMillis();
            if (!stale.isEmpty()) {
                List<Long> toRefresh = List.copyOf(stale);
                toRefresh.forEach(matchSimulationOrchestrator::invalidateSquadCaches);
                matchSimulationOrchestrator.warmFormationSquads(toRefresh);
                Map<Long, List<Human>> squadsByTeam = humanRepository
                        .findAllByTeamIdInAndTypeIdAndRetiredFalse(toRefresh, TypeNames.PLAYER_TYPE)
                        .stream()
                        .filter(player -> player.getTeamId() != null)
                        .collect(Collectors.groupingBy(Human::getTeamId));
                for (Long teamId : toRefresh) {
                    Team team = teamsById.get(teamId);
                    if (team == null) continue;
                    depthCharts.put(teamId, buildDepthChart(team, squadsByTeam.getOrDefault(teamId, List.of())));
                }
                stale.clear();
            }
            chartMs += System.currentTimeMillis() - tCharts;
            if (round == 0) lastPreMarketDepthCharts = Map.copyOf(depthCharts);

            // 2. Supply. Anyone who has already moved is off the market for the rest of
            //    the window: without this, the club that just signed him is free to list
            //    him again next round and he becomes a two-hop transit passenger.
            List<PlayerTransferView> market = new ArrayList<>();
            Map<Long, Set<Long>> listedByTeam = new HashMap<>();
            for (Long teamId : aiTeamIds) {
                Team team = teamsById.get(teamId);
                SquadDepthChart chart = depthCharts.get(teamId);
                if (team == null || chart == null) continue;
                List<PlayerTransferView> wanted = compositeTransferStrategy
                        .playersToSell(team, chart, minimumPositions).stream()
                        .filter(view -> !movedThisWindow.contains(view.getPlayerId()))
                        .toList();
                int allowance = sellAllowance.computeIfAbsent(teamId, id -> wanted.size());
                List<PlayerTransferView> listed = wanted.size() > allowance
                        ? wanted.subList(0, allowance) : wanted;
                market.addAll(listed);
                listedByTeam.put(teamId, listed.stream()
                        .map(PlayerTransferView::getPlayerId)
                        .collect(Collectors.toSet()));
            }
            if (round == 0) {
                funnel.put("listed_by_clubs", market.size());
                unsoldFreeAgents = freeAgentListings();
                funnel.put("free_agents_available", unsoldFreeAgents.size());
            }
            unsoldFreeAgents = unsoldFreeAgents.stream()
                    .filter(view -> !movedThisWindow.contains(view.getPlayerId()))
                    .collect(Collectors.toList());
            market.addAll(unsoldFreeAgents);

            // 3. Demand. Slots reset each round — a club that filled its striker slot
            //    has a rebuilt chart and will simply not ask for one again — but the
            //    money does not: budgetLeft carries what earlier rounds already spent.
            Map<Long, BuyPlanTransferView> plans = new LinkedHashMap<>();
            Map<Long, SquadDepthChart> planningCharts = new LinkedHashMap<>();
            Map<Long, Set<String>> openSlots = new HashMap<>();
            int criticalSlotsWanted = 0;
            for (Long teamId : aiTeamIds) {
                Team team = teamsById.get(teamId);
                SquadDepthChart chart = depthCharts.get(teamId);
                if (team == null || chart == null) continue;
                // Shop against the squad this club will have once its own sale list is
                // gone, not the one it still has. Otherwise a club that just listed its
                // only good striker cannot see the hole it is creating.
                SquadDepthChart afterSales = chart.afterSales(listedByTeam.getOrDefault(teamId, Set.of()));
                planningCharts.put(teamId, afterSales);
                BuyPlanTransferView plan = compositeTransferStrategy.playersToBuy(team, afterSales, maximumPositions);
                if (plan == null || plan.getPositions() == null || plan.getPositions().isEmpty()) continue;

                // Listing a player is not selling him: of 425 listings a window, some 80
                // find a buyer. So the sale list may decide what a club treats as urgent
                // — it is about to have a hole there — but it must NOT decide how good a
                // signing has to be. Judged against the post-sale squad, a club drops its
                // bar as though three midfielders had already left, signs accordingly,
                // sells none of them, and the new man cannot get into the eleven.
                // Priority from the projection, quality from the squad it actually has.
                for (TransferPlayer slot : plan.getPositions()) {
                    slot.setIncumbentRating(chart.incumbentRating(slot.getPosition()));
                }
                plans.put(teamId, plan);
                openSlots.put(teamId, plan.getPositions().stream()
                        .map(TransferPlayer::getPosition)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
                criticalSlotsWanted += (int) plan.getPositions().stream().filter(TransferPlayer::isCritical).count();
                budgetLeft.computeIfAbsent(teamId, id -> {
                    Team fresh = teamRepository.findById(id).orElse(team);
                    return Math.min(fresh.getTransferBudget(), plan.getSpendingCap());
                });
                // Human clubs are offered for once, not once per round.
                if (round == 0) transferMarketService.generateAiOffersForHumanPlayers(team, plan);
            }
            if (round == 0) {
                funnel.put("clubs_shopping", plans.size());
                lastBuyPlans = Map.copyOf(plans);
                lastPlanningDepthCharts = Map.copyOf(planningCharts);
            }
            funnel.merge("critical_slots_wanted", criticalSlotsWanted, Integer::sum);

            // 4. Match and settle.
            long tMatch = System.currentTimeMillis();
            List<Transfer> settled = matchAndSettle(market, plans, openSlots, budgetLeft,
                    clubsThatBought, pass, seasonInt, season, funnel);
            matchMs += System.currentTimeMillis() - tMatch;
            transfers.addAll(settled);

            funnel.merge("round_" + (round + 1) + "_transfers", settled.size(), Integer::sum);
            if (settled.isEmpty()) break; // nothing moved; another round cannot help

            for (Transfer transfer : settled) {
                movedThisWindow.add(transfer.getPlayerId());
                stale.add(transfer.getBuyTeamId());
                if (transfer.getSellTeamId() != 0L) {
                    stale.add(transfer.getSellTeamId());
                    sellAllowance.merge(transfer.getSellTeamId(), -1, (left, spent) -> Math.max(0, left + spent));
                }
            }
        }

        funnel.put("ms_depth_charts", (int) chartMs);
        funnel.put("ms_matching", (int) matchMs);
        funnel.put("transfers_completed", transfers.size());
        funnel.put("clubs_that_bought", clubsThatBought.size());
        System.out.println("=== AI transfer market funnel (season " + seasonInt + "): " + funnel + " ===");
        return transfers;
    }

    /** The club's squad and its match-engine XI, folded into one decision object. */
    private SquadDepthChart buildDepthChart(Team team, List<Human> squad) {
        return SquadDepthChart.build(
                team.getId(),
                squad,
                matchSimulationOrchestrator.startersFor(team.getId()),
                (natural, used) -> matchEngineConfig.getPlayerValue().familiarity(natural, used));
    }

    /**
     * Players with no club: contracts that just expired, plus anyone released in
     * earlier seasons and never picked up. Offered at zero fee — the only supply a
     * club with an empty budget can act on.
     */
    private List<PlayerTransferView> freeAgentListings() {
        return humanRepository.findAllByTypeIdAndRetiredFalseAndTeamIdIsNull(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> player.getPosition() != null)
                .filter(player -> !player.isWillNeverLeave())
                .map(player -> new PlayerTransferView(
                        player.getId(),
                        0L, // no selling club
                        player.getRating(),
                        TacticService.getBasePosition(player.getPosition()),
                        player.getPosition(),
                        player.getAge(),
                        player.isWillNeverLeave(),
                        false))
                .collect(Collectors.toList());
    }

    /**
     * Deterministic greedy assignment: the best available player picks first, and
     * takes the strongest club that both wants him and can still act.
     *
     * <p>Replaces a random shuffle over interested buyers, which made the outcome
     * un-seedable and let one wealthy club sign far more players than the plan it
     * had drawn up.
     *
     * <p><b>Slots are per position.</b> A club that wants a centre-back and a
     * striker cannot spend both allowances on strikers just because strikers came
     * up first — the striker slot closes after one signing and the centre-back slot
     * stays open. Weak links go further: while a club has an unfilled critical slot
     * it will not sign for any comfortable position at all in the primary pass, so
     * fixing the hole cannot be crowded out by a shinier opportunity. Clearance
     * drops that restriction, because by then something is better than nothing.
     */
    private List<Transfer> matchAndSettle(List<PlayerTransferView> market,
                                          Map<Long, BuyPlanTransferView> plans,
                                          Map<Long, Set<String>> openSlots,
                                          Map<Long, Long> budgetLeft,
                                          Set<Long> clubsThatBought,
                                          MatchingPass pass,
                                          int seasonInt,
                                          long season,
                                          Map<String, Integer> funnel) {
        List<PlayerTransferView> ordered = market.stream()
                .sorted(Comparator.comparingDouble(PlayerTransferView::getRating).reversed()
                        .thenComparingLong(PlayerTransferView::getPlayerId))
                .toList();

        List<Transfer> settled = new ArrayList<>();
        List<PlayerTransferView> sold = new ArrayList<>();
        int blockedByBudget = 0;
        int noEligibleBuyer = 0;
        int criticalFilled = 0;
        int starterSignings = 0;
        int depthSignings = 0;

        for (PlayerTransferView candidate : ordered) {
            long baseFee = candidate.isFreeAgent() ? 0L : TransferValueCalculator.calculate(
                    candidate.getAge(), candidate.getPosition(), candidate.getRating());
            long fee = pass.applyDiscount(baseFee);

            Long bestBuyerId = null;
            TransferPlayer bestSlot = null;
            boolean bestWasStarter = false;
            double bestStrength = Double.NEGATIVE_INFINITY;
            boolean sawEligibleButBroke = false;

            for (Map.Entry<Long, BuyPlanTransferView> entry : plans.entrySet()) {
                long buyerId = entry.getKey();
                BuyPlanTransferView plan = entry.getValue();
                Set<String> open = openSlots.getOrDefault(buyerId, Set.of());
                if (open.isEmpty()) continue;

                boolean hasOpenCritical = plan.getPositions().stream()
                        .anyMatch(slot -> slot.isCritical() && open.contains(slot.getPosition()));

                TransferPlayer matched = null;
                boolean matchedAsStarter = false;
                for (TransferPlayer slot : plan.getPositions()) {
                    if (!open.contains(slot.getPosition())) continue;
                    // Weak links first: no discretionary shopping while a hole is open.
                    if (pass == MatchingPass.PRIMARY && hasOpenCritical && !slot.isCritical()) continue;
                    if (transferMarketService.canBeTransfered(candidate, plan, slot, pass)) {
                        matched = slot;
                        matchedAsStarter = transferMarketService.lastMatchWasStarter();
                        break;
                    }
                }
                if (matched == null) continue;
                if (budgetLeft.getOrDefault(buyerId, 0L) < fee) {
                    sawEligibleButBroke = true;
                    continue;
                }
                // The player takes the strongest club that will have him.
                if (plan.getXiAverage() > bestStrength) {
                    bestStrength = plan.getXiAverage();
                    bestBuyerId = buyerId;
                    bestSlot = matched;
                    bestWasStarter = matchedAsStarter;
                }
            }

            if (bestBuyerId == null) {
                if (sawEligibleButBroke) blockedByBudget++;
                else noEligibleBuyer++;
                continue;
            }

            Transfer transfer = settleTransfer(candidate, bestBuyerId, fee, seasonInt, season);
            if (transfer == null) continue;

            settled.add(transfer);
            sold.add(candidate);
            openSlots.get(bestBuyerId).remove(bestSlot.getPosition());
            budgetLeft.merge(bestBuyerId, -fee, Long::sum);
            clubsThatBought.add(bestBuyerId);
            if (bestSlot.isCritical()) criticalFilled++;
            if (bestWasStarter) starterSignings++; else depthSignings++;
        }

        // No market.removeAll(sold) here: the caller rebuilds the supply list from
        // scratch every round and filters it through movedThisWindow, so pruning a list
        // that is about to be discarded proved only misleading — it read as if removal
        // from this list were what stopped a player moving twice.
        String suffix = "_" + pass.name().toLowerCase();
        funnel.merge("signed_as_starter", starterSignings, Integer::sum);
        funnel.merge("signed_as_depth", depthSignings, Integer::sum);
        funnel.merge("critical_slots_filled" + suffix, criticalFilled, Integer::sum);
        funnel.merge("unsold_no_eligible_buyer" + suffix, noEligibleBuyer, Integer::sum);
        funnel.merge("unsold_budget_blocked" + suffix, blockedByBudget, Integer::sum);
        return settled;
    }

    /** Moves the player, settles the money and records the transfer row. */
    private Transfer settleTransfer(PlayerTransferView candidate,
                                    long buyTeamId,
                                    long fee,
                                    int seasonInt,
                                    long season) {
        Human human = humanRepository.findById(candidate.getPlayerId()).orElse(null);
        if (human == null || human.isWillNeverLeave() || human.isRetired()) return null;

        // The view records who owned him when the market was built; the entity records
        // who owns him now. Today they always agree, because the caller rebuilds the
        // supply list every round and filters out anyone already moved — but that makes
        // the guarantee live entirely in the caller. Checking it here means this method
        // cannot be made to record a transfer out of a club that no longer holds the
        // player, however it is called.
        Long currentOwner = human.getTeamId();
        if (candidate.isFreeAgent()) {
            if (currentOwner != null) return null; // signed by somebody since he was listed
        } else if (currentOwner == null || currentOwner != candidate.getTeamId()) {
            return null; // moved, released or retired since the market was built
        }

        Team buyTeam = teamRepository.findById(buyTeamId).orElse(null);
        if (buyTeam == null) return null;
        if (currentOwner != null && currentOwner == buyTeam.getId()) return null; // already there

        // A free agent has no selling club: nobody is paid and nothing is invalidated
        // on the other side. The old code went straight to findById(...).get() and
        // would have thrown the moment free agents entered the market.
        Team sellTeam = candidate.isFreeAgent()
                ? null
                : teamRepository.findById(candidate.getTeamId()).orElse(null);
        if (!candidate.isFreeAgent() && sellTeam == null) return null;

        human.setTeamId(buyTeam.getId());
        human.setSeasonMatchesPlayed(0);
        human.setConsecutiveBenched(0);
        // Signing a player means agreeing terms with him. Without this the AI market
        // moved players and left their old contract untouched, which broke three ways:
        // a free agent released by a human club carries contractEndSeason 0 and the
        // expiry guard skips anything <= 0, so he could never leave again; one released
        // by an AI club carries a season already past, so he walked out for nothing the
        // very next year; and a player bought for a fee with a year left did the same.
        // Length follows the convention already used for AI renewals in
        // handleContractExpiries (2-4 seasons), derived from the player id rather than
        // an RNG so the window stays reproducible.
        human.setContractEndSeason(seasonInt + 1 + (int) Math.floorMod(human.getId(), 3L) + 1);
        human.setWage(WageService.baseWage(human.getRating()));
        humanRepository.save(human);

        matchSimulationOrchestrator.invalidateSquadCaches(buyTeam.getId());
        if (sellTeam != null) matchSimulationOrchestrator.invalidateSquadCaches(sellTeam.getId());

        if (fee > 0) {
            financeService.recordExpense(buyTeam.getId(), seasonInt, 0,
                    "TRANSFER_BUY", "Bought " + human.getName(), fee);
            buyTeam = teamRepository.findById(buyTeam.getId()).orElse(buyTeam);
            buyTeam.setTransferBudget(Math.max(0, buyTeam.getTransferBudget() - fee));
            teamRepository.save(buyTeam);

            if (sellTeam != null) {
                financeService.recordTransaction(sellTeam.getId(), seasonInt, 0,
                        "TRANSFER_SALE", "Sold " + human.getName(), fee);
                sellTeam = teamRepository.findById(sellTeam.getId()).orElse(sellTeam);
                teamRepository.save(sellTeam);
            }
        }

        Transfer transfer = new Transfer();
        transfer.setPlayerId(human.getId());
        transfer.setPlayerName(human.getName());
        transfer.setPlayerTransferValue(fee);
        transfer.setSellTeamId(sellTeam == null ? 0L : sellTeam.getId());
        transfer.setSellTeamName(sellTeam == null ? "Free agent" : sellTeam.getName());
        transfer.setBuyTeamId(buyTeam.getId());
        transfer.setBuyTeamName(buyTeam.getName());
        transfer.setRating(human.getRating());
        transfer.setSeasonNumber(season);
        transfer.setPlayerAge(human.getAge());
        transferRepository.save(transfer);
        return transfer;
    }

    public void handleContractExpiries(int newSeason) {
        Random random = new Random();
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Set<Long> humanTeamIds = new HashSet<>(userContext.getAllHumanTeamIds());

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            if (player.getTeamId() == null) continue;
            if (player.getContractEndSeason() <= 0) continue;
            if (player.getContractEndSeason() > newSeason) continue;

            // A one-club player's contract never expires. It used to end his career
            // instead: the club lost him outright the season his paper ran out, which
            // made "will never leave" a shorter career rather than a longer stay at
            // one club. He now re-signs unconditionally, on the same terms the AI
            // renewal uses, and leaves only through age in retirePlayers().
            if (player.isWillNeverLeave()) {
                player.setContractEndSeason(newSeason + random.nextInt(2, 5));
                player.setWage(WageService.baseWage(player.getRating()));
                humanRepository.save(player);
                if (humanTeamIds.contains(player.getTeamId())) {
                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(player.getTeamId());
                    inbox.setSeasonNumber(newSeason);
                    inbox.setRoundNumber(1);
                    inbox.setTitle("One-Club Player - Contract Renewed");
                    inbox.setContent(player.getName() + " has signed on again without negotiation. "
                            + "He will finish his career at the club.");
                    inbox.setCategory("contract");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);
                }
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
                if (coachPermissionService.canNegotiateContracts(player.getTeamId()) && random.nextBoolean()) {
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

    /** Prints the redrawn strength ladder and the pool each division has just earned. */
    private void logLeagueStrengthLadder(int season) {
        System.out.println("=== league strength after the transfer window (season " + season + ") ===");
        for (PrizeShare share : prizePoolsByCompetition(season)) {
            System.out.printf("  #%d  %-28s  XI avg %6.1f  pool %.0fM%n",
                    share.rank(), share.competitionName(), share.strength(), share.pool() / 1_000_000.0);
        }
    }

    /** What each division earned, and why — the numbers behind {@link #prizePoolsByCompetition}. */
    private record PrizeShare(long competitionId, String competitionName, int tier,
                              int rank, double strength, long pool) {}

    /**
     * Shares the game's prize pot between divisions by strength.
     *
     * <p>{@link LeagueStrengthService} interleaves first and second divisions in one
     * table, which is right for normalising a Golden Boot but wrong for prize money,
     * so each competition type competes for its own pot and its ranks restart at 1.
     *
     * <p>Within a pot, weight is {@code strength^exponent} times a small rank bonus,
     * normalised so the pot is paid out exactly. Divisions of equal quality therefore
     * earn equal money — the thing a per-rank ladder could never express.
     */
    private List<PrizeShare> prizePoolsByCompetition(int season) {
        Map<Long, Long> nationByCompetition = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Competition::getNationId, (left, right) -> left));

        List<LeagueStrengthService.LeagueStrengthEntry> topFlights = new ArrayList<>();
        List<LeagueStrengthService.LeagueStrengthEntry> lowerTiers = new ArrayList<>();
        // The table arrives sorted by average best-eleven rating, strongest first.
        for (LeagueStrengthService.LeagueStrengthEntry entry
                : leagueStrengthService.calculate(season).ranking()) {
            (entry.tier() > 1 ? lowerTiers : topFlights).add(entry);
        }

        double[] weights = new double[topFlights.size()];
        double weightTotal = 0;
        for (int index = 0; index < topFlights.size(); index++) {
            // A division with no rated players would otherwise take 0^k = 0 and
            // silently drop out of the split; floor it so it still gets a share.
            double strength = Math.max(topFlights.get(index).averageTopElevenRating(), 1D);
            weights[index] = Math.pow(strength, leaguePrizePoolConfig.getStrengthExponent())
                    * leaguePrizePoolConfig.rankBonus(index + 1);
            weightTotal += weights[index];
        }
        if (weightTotal <= 0) return List.of();

        List<PrizeShare> shares = new ArrayList<>();
        Map<Long, Long> topFlightPoolByNation = new HashMap<>();
        for (int index = 0; index < topFlights.size(); index++) {
            LeagueStrengthService.LeagueStrengthEntry entry = topFlights.get(index);
            long pool = (long) (leaguePrizePoolConfig.getTotalPool() * (weights[index] / weightTotal));
            shares.add(new PrizeShare(entry.competitionId(), entry.competitionName(),
                    entry.tier(), index + 1, entry.averageTopElevenRating(), pool));
            Long nation = nationByCompetition.get(entry.competitionId());
            if (nation != null) topFlightPoolByNation.put(nation, pool);
        }

        // A second tier is paid a slice of ITS OWN country's top flight, not a share of
        // a pot reserved for second tiers. Only one second division exists today, so a
        // shared pot would have handed that single division the whole reserve — 500M,
        // more than four of the seven top flights earn. Anchoring to the parent league
        // keeps a reserve division proportionate to the country it belongs to, and stays
        // correct however many second tiers are added later.
        int lowerRank = 0;
        for (LeagueStrengthService.LeagueStrengthEntry entry : lowerTiers) {
            lowerRank++;
            Long nation = nationByCompetition.get(entry.competitionId());
            long parentPool = nation == null ? 0L : topFlightPoolByNation.getOrDefault(nation, 0L);
            shares.add(new PrizeShare(entry.competitionId(), entry.competitionName(),
                    entry.tier(), lowerRank, entry.averageTopElevenRating(),
                    (long) (parentPool * leaguePrizePoolConfig.getSecondLeagueFraction())));
        }
        return shares;
    }

    public void refreshTeamBudgets(int season) {
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        Set<Long> processedTeamIds = new HashSet<>();

        // Opening balances, before a single payment lands. Everything credited below
        // is measured against these so the report can separate "earned this season"
        // from "had it already".
        Map<Long, Long> openingBudgets = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getTransferBudget));
        Map<Long, long[]> grossIncome = new HashMap<>();   // [prize, tv, european]

        List<Long> sortedLeagueIds = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
        Map<Long, Integer> leagueTierMap = new HashMap<>();
        for (int i = 0; i < sortedLeagueIds.size(); i++) {
            int tier;
            if (i < 2) tier = 1;
            else if (i < 4) tier = 2;
            else tier = 3;
            leagueTierMap.put(sortedLeagueIds.get(i), tier);
        }

        // Prize pools follow championship STRENGTH — the average best eleven across
        // the division — recomputed from live squads every time this runs, so a
        // transfer window that lifts a division shows up in its next payout. The
        // coefficient ladder above still drives TV tiers and European places.
        Map<Long, Long> prizePoolByCompetition = prizePoolsByCompetition(season).stream()
                .collect(Collectors.toMap(PrizeShare::competitionId, PrizeShare::pool));

        // 1. League prize money + TV income
        for (Competition comp : allComps) {
            if (!comp.isLeague()) continue;

            int tier = 3;
            if (comp.isBelowTopFlight()) {
                long nationId = comp.getNationId();
                for (Competition firstLeague : allComps) {
                    if (firstLeague.isTopFlight() && firstLeague.getNationId() == nationId) {
                        tier = leagueTierMap.getOrDefault(firstLeague.getId(), 3);
                        break;
                    }
                }
            } else {
                tier = leagueTierMap.getOrDefault(comp.getId(), 3);
            }

            long prizePool = prizePoolByCompetition.getOrDefault(comp.getId(), 0L);

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

            // The pool is shared out, not multiplied out: weights are normalised so
            // the division pays exactly its pool however many clubs are in it. The
            // old code multiplied a per-club base by the same ladder, so a division
            // that gained clubs silently gained prize money too.
            double spread = leaguePrizePoolConfig.getPositionSpread();
            double weightTotal = 0;
            for (int slot = 1; slot <= numTeams; slot++) {
                weightTotal += 1.0 - (spread * (slot - 1.0) / Math.max(numTeams - 1, 1));
            }

            int position = 1;
            for (TeamCompetitionDetail detail : standings) {
                double positionFactor = 1.0 - (spread * (position - 1.0) / Math.max(numTeams - 1, 1));
                long leagueIncome = (long) (prizePool * (positionFactor / weightTotal));

                Team team = teamRepository.findById(detail.getTeamId()).orElse(null);
                if (team == null) { position++; continue; }

                Optional<ClubCoefficient> cc = clubCoefficientRepository
                        .findByTeamIdAndSeasonNumber(detail.getTeamId(), season);
                long europeanIncome = cc.map(c -> (long) (c.getPoints() * 2_000_000L)).orElse(0L);

                int tvTier = comp.isBelowTopFlight() ? tier + 1 : tier;
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

                grossIncome.put(detail.getTeamId(),
                        new long[]{leagueIncome, tvIncome, europeanIncome});

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
                if (firstLeague.isTopFlight() && firstLeague.getNationId() == nationId) {
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

        // Snapshot between phases rather than trying to predict what each payment
        // adds: income reaches the transfer budget through a board-confidence share
        // and is then subject to the carry-over decay, so a difference of balances
        // is the only figure guaranteed to match what the market actually gets.
        Map<Long, Long> afterPrizeBudgets = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getTransferBudget));

        // 3. Owner injections
        for (Team team : teamRepository.findAll()) {
            financeService.processOwnerInjection(team.getId(), season);
        }

        Map<Long, Long> afterOwnerBudgets = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getTransferBudget));

        // 4. Extra transfer funding — see NATION_TRANSFER_FUNDING / STRATEGY_TRANSFER_FUNDING.
        applyExtraTransferFunding(season);
        // 5. European prizes are awarded per-match via awardEuropeanMatchPrizeMoney().

        Map<Long, BudgetBreakdown> breakdowns = new HashMap<>();
        for (Team team : teamRepository.findAll()) {
            long[] gross = grossIncome.getOrDefault(team.getId(), new long[]{0, 0, 0});
            long opening = openingBudgets.getOrDefault(team.getId(), 0L);
            long afterPrizes = afterPrizeBudgets.getOrDefault(team.getId(), opening);
            long afterOwner = afterOwnerBudgets.getOrDefault(team.getId(), afterPrizes);
            breakdowns.put(team.getId(), new BudgetBreakdown(
                    opening, gross[0], gross[1], gross[2],
                    afterPrizes, afterOwner - afterPrizes,
                    team.getTransferBudget() - afterOwner, team.getTransferBudget()));
        }
        lastBudgetBreakdowns = Map.copyOf(breakdowns);
    }

    /**
     * Extra transfer money for the clubs of specific nations, keyed by nation id.
     *
     * <p>Not a balance tweak but a fix for a structural mismatch: fees follow
     * {@code rating^3}, so a 20% better player costs 73% more, while the club that
     * wants him does not have 73% more money. Mid-table sides were left listing
     * players priced at three or four times any plausible buyer's budget — a whole
     * strategy (Academy, which lists its best and therefore its most expensive
     * players) transacted nothing at all, and most of the market's unsold listings
     * failed on the fee rather than on any rule.
     *
     * <p>Nation ids come from {@code BootstrapService.initializeCompetitions}:
     * 1 = Gallactick Football, 7 = Eleven.
     */
    private static final Map<Long, Long> NATION_TRANSFER_FUNDING = Map.of(
            1L, 100_000_000L,   // Gallactick Football
            7L, 60_000_000L);   // Eleven

    /**
     * Extra transfer money by transfer strategy, keyed by strategy id.
     *
     * <p>A club that only ever sells its reserves has nothing valuable to cash in,
     * so it cannot fund itself the way a sell-high side does — its income is prize
     * money and gate receipts alone. Without a top-up the most ambitious profile in
     * the game is also the poorest, and the strategy never gets to express itself.
     *
     * <p>Ids from {@link com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil}.
     */
    private static final Map<Long, Long> STRATEGY_TRANSFER_FUNDING = Map.of(
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, 500_000_000L);

    /**
     * Credits the funding above to every club of those nations, second leagues
     * included.
     *
     * <p>Booked through {@link FinanceService#recordTransaction} so the ledger and
     * club treasury stay correct, then topped up so the whole sum is actually
     * spendable: {@code recordTransaction} only routes a board-confidence share
     * (20-80%) of income into the transfer budget, and this money exists precisely
     * to be spent on transfers.
     */
    private void applyExtraTransferFunding(int season) {
        if (NATION_TRANSFER_FUNDING.isEmpty() && STRATEGY_TRANSFER_FUNDING.isEmpty()) return;

        Map<Long, Long> fundingByCompetition = new HashMap<>();
        for (Competition competition : competitionRepository.findAll()) {
            if (!competition.isLeague()) continue;
            Long amount = NATION_TRANSFER_FUNDING.get(competition.getNationId());
            if (amount != null) fundingByCompetition.put(competition.getId(), amount);
        }

        int funded = 0;
        for (Team team : teamRepository.findAll()) {
            // The two are cumulative: where a club plays and how it does business are
            // independent reasons to be funded.
            long amount = fundingByCompetition.getOrDefault(team.getCompetitionId(), 0L)
                    + (team.getStrategy() == null
                        ? 0L : STRATEGY_TRANSFER_FUNDING.getOrDefault(team.getStrategy(), 0L));
            if (amount <= 0) continue;

            long budgetBefore = team.getTransferBudget();
            financeService.recordTransaction(team.getId(), season, 340, "TRANSFER_FUNDING",
                    "Transfer funding", amount);

            Team fresh = teamRepository.findById(team.getId()).orElse(null);
            if (fresh == null) continue;
            long routedToBudget = fresh.getTransferBudget() - budgetBefore;
            if (routedToBudget < amount) {
                fresh.setTransferBudget(fresh.getTransferBudget() + (amount - routedToBudget));
                teamRepository.save(fresh);
            }
            funded++;
        }
        System.out.println("=== extra transfer funding applied to " + funded + " club(s) ===");
    }
}
