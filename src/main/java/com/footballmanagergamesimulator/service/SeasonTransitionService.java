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
    private EuropeanCoefficientService europeanCoefficientService;
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
    private GameStateService gameStateService;
    @Lazy @Autowired
    private ScoutManagementController scoutManagementController;
    @Autowired
    private EndOfSeasonProcessor endOfSeasonProcessor;
    @Autowired
    private NewSeasonSetupProcessor newSeasonSetupProcessor;

    // ============================================================
    //  Phase 1 — end-of-season (day 340)
    // ============================================================

    /**
     * Final standings + relegation/promotion bracket setup, AI transfers, AI
     * loans, season-objectives evaluation, transfer window open. Delegates to
     * {@link EndOfSeasonProcessor} (extracted in sesiunea 6 Pass B); the
     * processor owns idempotency state + body. This method stays as the
     * public coordinator entry point so {@code GameAdvanceService} and IT
     * tests don't need to change.
     */
    public void processEndOfSeason(int season) {
        endOfSeasonProcessor.process(season);
    }

    // ============================================================
    //  Phase 2 — new-season setup (day 360, after transfer window closes)
    // ============================================================

    /**
     * Roll into season N+1: training boost, historical snapshots, loan returns,
     * standings teardown, regens, retirements, contract expiries, new fixtures,
     * cup brackets, scorer init. Delegates to {@link NewSeasonSetupProcessor}
     * (extracted in sesiunea 6 Pass C); this method stays as the public
     * coordinator entry point so {@code GameAdvanceService} and IT tests don't
     * need to change.
     */
    public void processNewSeasonSetup(int season) {
        newSeasonSetupProcessor.process(season);
    }

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L);
    }


}
