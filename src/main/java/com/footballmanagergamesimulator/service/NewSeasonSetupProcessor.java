package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.ScoutManagementController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TrainingScheduleRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * New-season setup pipeline — extracted from {@link SeasonTransitionService}
 * (sesiunea 6, §6.2 Pass C). Runs at day 360 of each in-game year after the
 * transfer window closes; rolls the world into season N+1.
 *
 * <p>Pipeline (order matters):
 * <ol>
 *   <li>Apply end-of-season training rating boost.</li>
 *   <li>Snapshot historical values (CompetitionHistory + TeamPlayerHistoricalRelation).</li>
 *   <li>Return loaned players (with buy obligations).</li>
 *   <li>Reset competition data (TCD, fixtures) and re-seed for new season.</li>
 *   <li>Reward overachievers + advance Round (season+1, round=1).</li>
 *   <li>Age players, retire, generate regens, update statuses.</li>
 *   <li>Handle contract expiries (via {@link EndOfSeasonProcessor}).</li>
 *   <li>Generate new fixtures + initialise scorers + regenerate cup brackets.</li>
 * </ol>
 *
 * <p>Idempotency: the round.season guard at the top skips the pipeline if it
 * already ran for the given season.
 *
 * <p>Public helpers: {@link #regenerateAllCupBrackets} is also called from
 * {@code GameInitializationService} for the cold-start path (no separate
 * end-of-season precursor).
 */
@Service
public class NewSeasonSetupProcessor {

    @Autowired private RoundRepository roundRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired private TrainingScheduleRepository trainingScheduleRepository;
    @Autowired private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private UserContext userContext;
    @Autowired private HumanService humanService;
    @Autowired private FixtureSchedulingService fixtureSchedulingService;
    @Autowired private TeamPostMatchService teamPostMatchService;
    @Autowired private CupBracketService cupBracketService;
    @Autowired private FinanceService financeService;
    @Lazy @Autowired private GameStateService gameStateService;
    @Lazy @Autowired private ScoutManagementController scoutManagementController;
    @Lazy @Autowired private SeasonObjectiveService seasonObjectiveService;
    @Lazy @Autowired private EndOfSeasonProcessor endOfSeasonProcessor;
    @Lazy @Autowired private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private SuperCupService superCupService;
    @Autowired private SponsorshipService sponsorshipService;
    @Autowired private MinimumSquadService minimumSquadService;
    @Autowired private NewSeasonPlayerReadinessService newSeasonPlayerReadinessService;
    @Autowired private ScorerLeaderboardSyncService scorerLeaderboardSyncService;
    @Lazy @Autowired private AdminTransferService adminTransferService;
    @Autowired private CompetitionHistorySnapshotService competitionHistorySnapshotService;

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L);
    }

    // ============================================================
    //  Main entry — full new-season setup pipeline
    // ============================================================

    @Transactional
    public synchronized void process(int season) {
        Round cachedRound = gameStateService.getRound();
        Round round = roundRepository.findById(cachedRound.getId()).orElseThrow();
        // Guard: if round.season already moved past this season, skip (prevents double transition)
        if (round.getSeason() > season) {
            System.out.println("=== processNewSeasonSetup: season " + season + " already transitioned (current=" + round.getSeason() + "), skipping ===");
            return;
        }
        long oldSeason = round.getSeason();
        int newSeason = Math.toIntExact(oldSeason + 1);
        System.out.println("=== processNewSeasonSetup: transitioning from season " + season + " ===");

        // Note: refreshTeamBudgets is called in processEndOfSeason before AI transfers
        List<Long> teamIds = teamRepository.findAll().stream().map(Team::getId).collect(Collectors.toList());
        applyTrainingEffect(teamIds);

        Set<Long> competitions = competitionRepository.findAll()
                .stream()
                .mapToLong(Competition::getId)
                .boxed()
                .collect(Collectors.toSet());
        // Load every TeamCompetitionDetail once and group by competition so the
        // per-competition snapshot below is a map lookup, not a findAll() per loop.
        Map<Long, List<TeamCompetitionDetail>> detailsByCompetition = teamCompetitionDetailRepository.findAll()
                .stream()
                .collect(Collectors.groupingBy(tcd -> (long) tcd.getCompetitionId()));
        snapshotHistoricalValues(competitions, oldSeason, detailsByCompetition);
        saveAllPlayerTeamHistoricalRelations(oldSeason);

        // Return loaned players (handles buy obligations and salary adjustments)
        processLoanReturns((int) oldSeason);

        // Execute Admin movements scheduled for this exact season boundary only
        // after older loans have returned and before contract expiries are applied.
        adminTransferService.executeScheduledForSeason(newSeason);

        resetCompetitionData();
        superCupService.prepareSeason(newSeason);
        removeCompetitionData((long) newSeason);
        addImprovementToOverachievers();

        humanService.addOneYearToAge();
        humanService.retirePlayers();
        // Wipe AI tactics for the new season but preserve human-managed teams'
        // saved formation/mentality/tempo — a blanket deleteAll() would lose them.
        clearAiPersonalizedTactics();

        // Collect regens across ALL teams, then flush via saveAll once per repository.
        // currentSeason is hoisted out of the per-team loop (was a findById(1L) per call).
        long regenSeason = newSeason;
        List<Human> newRegens = new ArrayList<>();
        Map<Long, TeamFacilities> facilitiesByTeam = teamFacilitiesRepository.findAll().stream()
                .collect(Collectors.toMap(TeamFacilities::getTeamId, facility -> facility));
        for (Long teamId : teamIds) {
            TeamFacilities teamFacilities = facilitiesByTeam.get(teamId);
            if (teamFacilities != null)
                humanService.collectRegens(teamFacilities, teamId, regenSeason, newRegens);
        }
        newRegens = (List<Human>) humanRepository.saveAll(newRegens); // assign IDs

        List<PlayerSkills> newRegenSkills = new ArrayList<>();
        List<TeamPlayerHistoricalRelation> newRegenRelations = new ArrayList<>();
        humanService.buildRegenSkillsAndRelations(newRegens, regenSeason, newRegenSkills, newRegenRelations);
        humanRepository.saveAll(newRegens); // re-save with recomputed ratings
        playerSkillsRepository.saveAll(newRegenSkills);
        teamPlayerHistoricalRelationRepository.saveAll(newRegenRelations);

        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Random seasonResetRandom = new Random();
        for (Human human : allPlayers) {
            long seasonCreated = human.getSeasonCreated();
            if (newSeason - seasonCreated <= 2 && seasonCreated != 1L)
                human.setCurrentStatus("Junior");
            else if (newSeason - seasonCreated <= 6 && seasonCreated != 1L)
                human.setCurrentStatus("Intermediate");
            else
                human.setCurrentStatus("Senior");
            human.setTransferValue(TransferValueCalculator.calculate(human.getAge(), human.getPosition(), human.getRating()));
            human.setSeasonMatchesPlayed(0);
            human.setConsecutiveBenched(0);
            if (human.isWantsTransfer() && seasonResetRandom.nextDouble() < 0.5) {
                human.setWantsTransfer(false);
                human.setMorale(Math.min(100, human.getMorale() + 10));
            }
        }
        humanRepository.saveAll(allPlayers);

        // Clear derby cache for new season
        teamPostMatchService.clearDerbyCache();

        endOfSeasonProcessor.handleContractExpiries(newSeason);
        scoutManagementController.processExpiredContracts(newSeason);
        // Contracts and retirements are now final for the boundary. Promote
        // academy players only at this point, so every club starts the season
        // with at least 18 permanent first-team players.
        minimumSquadService.ensureMinimumSquads(newSeason);
        int resetPlayers = newSeasonPlayerReadinessService.resetActiveTeamPlayers();
        System.out.println("=== New-season readiness reset to 80 morale / 80 fitness for "
                + resetPlayers + " active team player(s) ===");
        int expiredSponsorships = sponsorshipService.expireContractsBeforeSeason(newSeason);
        if (expiredSponsorships > 0) {
            System.out.println("=== Expired " + expiredSponsorships
                    + " sponsorship contract(s)/offer(s) before season " + newSeason + " ===");
        }
        seasonObjectiveService.generateSeasonObjectives(newSeason);

        // Generate league fixtures for new season
        Set<Long> newLeagueCompIds = competitionRepository.findIdsByTypeId(1);
        Set<Long> newSecondLeagueCompIds = competitionRepository.findIdsByTypeId(3);
        newLeagueCompIds.addAll(newSecondLeagueCompIds);
        for (Long competitionId : newLeagueCompIds)
            fixtureSchedulingService.getFixturesForRound(String.valueOf(competitionId), "1", newSeason);

        // Scorer rows are real match appearances, never season-start placeholders.
        // First create/backfill entries for regens and academy promotions, then
        // reset the auxiliary leaderboard in one query + one batch save.
        // Reload because the minimum-squad service may just have promoted new
        // academy players after the earlier allPlayers snapshot.
        scorerLeaderboardSyncService.synchronizeAllPlayers();
        List<Long> playerIds = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)
                .stream().map(Human::getId).toList();
        List<ScorerLeaderboardEntry> leaderboardUpdates =
                scorerLeaderboardRepository.findAllByPlayerIdIn(playerIds);
        leaderboardUpdates.forEach(NewSeasonSetupProcessor::resetCurrentSeasonStats);
        scorerLeaderboardRepository.saveAll(leaderboardUpdates);

        // Reset end-of-season idempotency guard so next season can process again
        endOfSeasonProcessor.reset();

        // Rebuild cup brackets for the new season — based on last season's standings.
        // This wipes whatever cup CompetitionTeamInfo/Match rows the legacy per-league
        // loop wrote earlier and replaces them with a proper full bracket.
        regenerateAllCupBrackets(newSeason);

        // Ageing, retirements, overachiever rewards and the pre-season training
        // boost all recomputed player ratings — drop every cached AI base rating so
        // the new season starts from current squad strength.
        matchSimulationOrchestrator.invalidateAllRatingCaches();

        // Publish the new season only after every setup write succeeded. The
        // in-memory Round is updated after commit, keeping concurrent reads on
        // the fully valid previous season during this transaction.
        round.setRound(1);
        round.setSeason(newSeason);
        roundRepository.save(round);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gameStateService.publishRoundState(1, newSeason);
            }
        });

        System.out.println("=== NEW SEASON " + newSeason + " STARTED ===");
    }

    private void snapshotHistoricalValues(
            Set<Long> competitionIds,
            long season,
            Map<Long, List<TeamCompetitionDetail>> detailsByCompetition) {
        if (!competitionHistoryRepository.findAllBySeasonNumber(season).isEmpty()) return;

        List<CompetitionHistory> snapshots = new ArrayList<>();
        for (Long competitionId : competitionIds) {
            List<TeamCompetitionDetail> standings = new ArrayList<>(
                    detailsByCompetition.getOrDefault(competitionId, List.of()));
            standings.sort(this::compareStandings);
            for (int i = 0; i < standings.size(); i++) {
                snapshots.add(adaptCompetitionHistory(standings.get(i), season, i + 1L));
            }
        }
        competitionHistorySnapshotService.capture(snapshots, season);
        competitionHistoryRepository.saveAll(snapshots);
    }

    private int compareStandings(TeamCompetitionDetail left, TeamCompetitionDetail right) {
        if (left.getPoints() != right.getPoints()) return Integer.compare(right.getPoints(), left.getPoints());
        if (left.getGoalDifference() != right.getGoalDifference())
            return Integer.compare(right.getGoalDifference(), left.getGoalDifference());
        return Integer.compare(right.getGoalsFor(), left.getGoalsFor());
    }

    // ============================================================
    //  Personalized-tactic reset (preserve human teams)
    // ============================================================

    private void clearAiPersonalizedTactics() {
        Set<Long> humanTeamIds = new HashSet<>(userContext.getAllHumanTeamIds());
        if (humanTeamIds.isEmpty()) {
            personalizedTacticRepository.deleteAll();
            return;
        }
        List<PersonalizedTactic> toDelete = personalizedTacticRepository.findAll().stream()
                .filter(pt -> !humanTeamIds.contains(pt.getTeamId()))
                .collect(Collectors.toList());
        personalizedTacticRepository.deleteAll(toDelete);
    }

    // ============================================================
    //  Loan returns (buy obligations + salary adjustments)
    // ============================================================

    /**
     * Return all loaned players to their parent clubs at end of season.
     * Processes buy obligations automatically.
     */
    public void processLoanReturns(int season) {
        List<Loan> activeLoans = loanRepository.findAllByStatus("active");

        for (Loan loan : activeLoans) {
            // Legacy saves have endSeason=0 and retain the old one-season behaviour.
            // Multi-season loans stay at the loan club until their final season ends.
            if (loan.getEndSeason() > 0 && loan.getEndSeason() > season) continue;

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

                long parentWageShare = player.getWage() * loan.getParentWageContribution() / 100L;
                if (parentWageShare > 0) {
                    loanTeam = teamRepository.findById(loanTeam.getId()).orElse(loanTeam);
                    parentTeam = teamRepository.findById(parentTeam.getId()).orElse(parentTeam);
                    loanTeam.setSalaryBudget(loanTeam.getSalaryBudget() + parentWageShare);
                    parentTeam.setSalaryBudget(Math.max(0, parentTeam.getSalaryBudget() - parentWageShare));
                    teamRepository.save(loanTeam);
                    teamRepository.save(parentTeam);
                }

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
            long loanTeamWageShare = playerWage * (100L - loan.getParentWageContribution()) / 100L;
            player.setTeamId(loan.getParentTeamId());
            player.setSeasonMatchesPlayed(0);
            humanRepository.save(player);

            // Adjust salary budgets
            if (loanTeam != null) {
                loanTeam.setSalaryBudget(Math.max(0, loanTeam.getSalaryBudget() - loanTeamWageShare));
                teamRepository.save(loanTeam);
            }
            if (parentTeam != null) {
                parentTeam.setSalaryBudget(parentTeam.getSalaryBudget() + loanTeamWageShare);
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
    //  Cup-bracket regeneration (also called externally from
    //  GameInitializationService at cold start).
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
    //  Scorer-leaderboard season reset (static — also called from bootstrap)
    // ============================================================

    public static ScorerLeaderboardEntry resetCurrentSeasonStats(Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry) {
        return resetCurrentSeasonStats(optionalScorerLeaderboardEntry.orElseThrow());
    }

    public static ScorerLeaderboardEntry resetCurrentSeasonStats(ScorerLeaderboardEntry scorerLeaderboardEntry) {
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

        // Preload every player once into a map so the per-entry reward is a map
        // lookup instead of a findById (N+1); collect mutated rows for one saveAll each.
        Map<Long, Human> playersById = humanRepository.findAll().stream()
                .collect(Collectors.toMap(Human::getId, h -> h, (a, b) -> a));
        List<Human> playersToSave = new ArrayList<>();
        List<ScorerLeaderboardEntry> entriesToSave = new ArrayList<>();

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

            Human player = playersById.get(entry.getPlayerId());
            if (player != null) {
                System.out.println("Player " + player.getName() + " from team " + entry.getTeamName()
                        + " had rating increased from " + player.getRating() + " to "
                        + (player.getRating() + ratingIncrease) + " because of ratio of " + ratio);
                player.setRating(player.getRating() + ratingIncrease);
                playersToSave.add(player);

                entry.setCurrentRating(player.getRating());
                if (entry.getCurrentRating() > entry.getBestEverRating()) {
                    entry.setBestEverRating(entry.getCurrentRating());
                    entry.setSeasonOfBestEverRating((int) season);
                }
                entriesToSave.add(entry);
            }
        }
        humanRepository.saveAll(playersToSave);
        scorerLeaderboardRepository.saveAll(entriesToSave);
    }

    // ============================================================
    //  Historical snapshots
    // ============================================================

    public void saveAllPlayerTeamHistoricalRelations(long seasonNumber) {
        Set<Long> teamIds = new HashSet<>();
        for (TeamCompetitionDetail tcd : teamCompetitionDetailRepository.findAll()) {
            teamIds.add(tcd.getTeamId());
        }
        List<TeamPlayerHistoricalRelation> relations = new ArrayList<>();
        List<Human> allTeamPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        for (Human player : allTeamPlayers) {
            if (player.getTeamId() == null || !teamIds.contains(player.getTeamId())) continue;
            TeamPlayerHistoricalRelation rel = new TeamPlayerHistoricalRelation();
            rel.setPlayerId(player.getId());
            rel.setTeamId(player.getTeamId());
            rel.setSeasonNumber(seasonNumber + 1);
            rel.setRating(player.getRating());
            relations.add(rel);
        }
        teamPlayerHistoricalRelationRepository.saveAll(relations);
    }

    /**
     * Backward-compatible entry: loads the competition's details itself. Kept so
     * external callers and tests keep working; the per-season pipeline uses the
     * overload below with a preloaded list to avoid a findAll() per competition.
     */
    public void saveHistoricalValues(Long competitionId, Long seasonNumber) {
        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll()
                .stream()
                .filter(tcd -> tcd.getCompetitionId() == competitionId)
                .collect(Collectors.toList());
        saveHistoricalValues(competitionId, seasonNumber, teams);
    }

    public void saveHistoricalValues(Long competitionId, Long seasonNumber, List<TeamCompetitionDetail> preloadedDetails) {
        List<TeamCompetitionDetail> teams = preloadedDetails.stream()
                .filter(tcd -> tcd.getCompetitionId() == competitionId)
                .collect(Collectors.toList());

        Collections.sort(teams, (a, b) -> {
            if (a.getPoints() != b.getPoints()) return a.getPoints() > b.getPoints() ? -1 : 1;
            if (a.getGoalDifference() != b.getGoalDifference()) return a.getGoalDifference() > b.getGoalDifference() ? -1 : 1;
            return a.getGoalsFor() > b.getGoalsFor() ? -1 : 1;
        });

        List<CompetitionHistory> snapshots = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            TeamCompetitionDetail team = teams.get(i);
            if (team.getCompetitionId() != competitionId) continue;
            snapshots.add(adaptCompetitionHistory(team, seasonNumber, 1 + i));
        }
        competitionHistorySnapshotService.capture(snapshots, seasonNumber);
        competitionHistoryRepository.saveAll(snapshots);
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
        List<TeamCompetitionDetail> newDetails = new ArrayList<>();
        for (CompetitionTeamInfo team : ctis) {
            TeamCompetitionDetail newTcd = new TeamCompetitionDetail();
            newTcd.setTeamId(team.getTeamId());
            newTcd.setCompetitionId(team.getCompetitionId());
            newTcd.setForm("");
            newDetails.add(newTcd);
        }
        teamCompetitionDetailRepository.saveAll(newDetails);
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

        Set<Long> eligibleTeamIds = new HashSet<>(teamIds);
        List<Human> playersToSave = new ArrayList<>();
        for (Human player : humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)) {
            Long teamId = player.getTeamId();
            if (teamId == null || !eligibleTeamIds.contains(teamId)) continue;
            double avgIntensity = teamAvgIntensity.getOrDefault(teamId, 0.0);
            if (avgIntensity == 0) continue;
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
        humanRepository.saveAll(playersToSave);
    }
}
