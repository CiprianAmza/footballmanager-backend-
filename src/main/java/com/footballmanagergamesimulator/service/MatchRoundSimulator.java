package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LineupAdapter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Single-competition single-round simulation extracted from
 * {@link MatchSimulationOrchestrator}. Owns {@code simulateRound} plus its
 * batched AI helpers and the per-round caches they share.
 *
 * <p>Public cache accessors ({@link #roundPlayers}, {@link #roundInjuredIds},
 * {@link #roundTeamName}, {@link #roundTeam}) and {@link #processInjuriesForTeam}
 * stay public because {@link LineupRatingService} and {@link MatchdayCoordinator}
 * call into them outside the simulateRound transaction.
 */
@Service
public class MatchRoundSimulator {

    @Autowired private HumanRepository humanRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private SuspensionRepository suspensionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired private RoundRepository roundRepository;

    @Autowired private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired @Lazy private FinanceService financeService;
    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private com.footballmanagergamesimulator.matchplan.MatchPlanService matchPlanService;
    @Autowired private LineupAdapter lineupAdapter;
    @Autowired private MatchStatsService matchStatsService;
    @Autowired private CompetitionService competitionService;
    @Autowired private TeamPostMatchService teamPostMatchService;
    @Autowired private LineupRatingService lineupRatingService;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;
    @Autowired private CupBracketService cupBracketService;
    @Autowired private TacticController tacticController;
    @Autowired private TacticService tacticService;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerRoleService playerRoleService;
    @Autowired private PlayerInstructionService playerInstructionService;
    @Autowired private InjuryTimelineService injuryTimelineService;
    @Autowired private CoachPermissionService coachPermissionService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TacticalScoreService tacticalScoreService;
    @Autowired private ManagerTacticService managerTacticService;
    @Autowired private PlayerMatchStatService playerMatchStatService;
    @Autowired private UserContext userContext;
    @Autowired private UserRepository userRepository;
    @Autowired private GameStateService gameStateService;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired(required = false) private GameplayFeatureConfig gameplayFeatures;
    @Autowired private com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver tieResolver;

    /**
     * One RNG per simulation thread. A shared {@link Random} is internally safe,
     * but interleaving calls from several competitions makes their outcomes depend
     * on scheduler timing. Keeping the stream local also avoids a global contention
     * point in the hot path.
     */
    private final ThreadLocal<Random> threadRandom = ThreadLocal.withInitial(Random::new);

    /**
     * Test-only seam: swap the RNG for determinism / fuzz tests so the same
     * seed produces the same simulateRound output. Public so fuzz tests in
     * {@code integration/fuzz/} (different package) can call it — production
     * code MUST NOT use this method.
     */
    public void setRandomForTesting(Random random) {
        threadRandom.set(Objects.requireNonNull(random));
    }

    private Human resolveCurrentManager(long teamId, List<Human> managers) {
        if (managers.isEmpty()) return null;
        Long linkedManagerId = userRepository.findAllByTeamId(teamId).stream()
                .map(User::getManagerId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (linkedManagerId == null) return managers.get(0);
        return managers.stream()
                .filter(manager -> manager.getId() == linkedManagerId)
                .findFirst()
                .orElse(managers.get(0));
    }

    // ===== AI rating caches (lifetime: app, mirrors original controller behavior). =====
    private final Map<Long, Double> simpleRatingCache = new ConcurrentHashMap<>();
    private final Map<Long, String> managerTacticCache = new ConcurrentHashMap<>();
    private final Map<Long, List<PlayerView>> bestElevenCache = new ConcurrentHashMap<>();
    private final Map<Long, List<TacticController.StarterSlot>> starterSlotsCache = new ConcurrentHashMap<>();
    private final Map<Long, List<PlayerView>> substitutionsCache = new ConcurrentHashMap<>();
    // Two-axis tactical-model caches (only populated when tactical-model.enabled): a team's coached
    // attack/defense profile and the tactic its manager picked for the season.
    private final Map<Long, TacticalScoreService.TeamProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<Long, TacticalScoreService.TacticVector> tacticVectorCache = new ConcurrentHashMap<>();
    // XI value share in wide positions, for the AI's squad-shape width identity (see teamTacticVector).
    private final Map<Long, Double> wideShareCache = new ConcurrentHashMap<>();
    // Formation ranking used to rebuild the full UI-oriented PlayerView squad for every candidate
    // shape. Keep a compact, batch-loaded evaluation squad and the resulting values instead.
    private final Map<Long, FormationEvaluationSquad> formationSquadCache = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Double>> formationValueCache = new ConcurrentHashMap<>();

    // ===== Per-round state (populated at start of simulateRound, cleared at end). =====
    // MatchRoundSimulator is a singleton, therefore every worker needs its own view
    // of the current competition and its managed entities.
    private record RoundContext(
            Map<Long, List<Human>> playersByTeam,
            Map<Long, List<Human>> managersByTeam,
            Map<Long, Set<Long>> injuredIdsByTeam,
            Map<Long, Set<Long>> suspendedIdsByTeam,
            Map<Long, Team> teamsById,
            Map<Long, TeamCompetitionDetail> competitionDetailsByTeam,
            Map<Long, PersonalizedTactic> tacticsByTeam,
            Map<Long, ScorerLeaderboardEntry> leaderboardByPlayer,
            Competition competition,
            String competitionName,
            long competitionTypeId) {}

    private final ThreadLocal<RoundContext> roundContext = new ThreadLocal<>();

    // ============================================================
    //  Public entry point
    // ============================================================

    @Transactional
    public void simulateRound(String competitionId, String roundId) {
        simulateRound(competitionId, roundId, null);
    }

    /**
     * @param onlyLeg when non-null, simulate only the matches of this leg number
     *        (1 = first leg, 2 = second leg). Lets a two-leg tie be played across
     *        separate calendar days: leg 1 records its score without propagating,
     *        leg 2 aggregates with the persisted leg 1 and decides the tie. When
     *        null, the whole round is simulated in one pass (both legs back-to-back).
     */
    @Transactional
    public void simulateRound(String competitionId, String roundId, Integer onlyLeg) {

        long _t0 = System.nanoTime();

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long nextRound = _roundId + 1;

        // Field-level RNG so determinism IT holds across simulateRound.
        Random random = threadRandom.get();
        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(_competitionId, _roundId, getCurrentSeason());
        if (onlyLeg != null) {
            matches = matches.stream().filter(m -> m.getLegNumber() == onlyLeg).collect(Collectors.toList());
        }

        boolean knockout = europeanCompetitionService.isKnockoutRound(_competitionId, _roundId);

        // Two-leg knockout: ensure leg 1 is simulated before leg 2 so the second
        // leg can aggregate with the first. firstLegScores maps tieId → leg-1
        // [team1Score, team2Score] (team1 of leg 1 = the side that hosted leg 1).
        if (knockout) {
            matches = new ArrayList<>(matches);
            matches.sort(java.util.Comparator.comparingInt(CompetitionTeamInfoMatch::getLegNumber));
        }
        Map<Long, int[]> firstLegScores = new HashMap<>();

        // Batch collections for AI matches - saved once at end of round
        List<Injury> batchedInjuries = new ArrayList<>();
        List<Human> batchedInjuredPlayers = new ArrayList<>();
        List<Human> batchedManagers = new ArrayList<>();
        List<Scorer> batchedScorers = new ArrayList<>();
        List<LineupRatingService.AiLineupInput> batchedLineups = new ArrayList<>();
        List<PlayerMatchStatService.TeamMatchInput> batchedPlayerStats = new ArrayList<>();
        List<FinanceService.MatchDayIncomeInput> batchedMatchdayIncome = new ArrayList<>();
        // Canonical AI fixtures whose plan is resolved+persisted this round; marked COMMITTED
        // (immutable) only after ALL batched match effects are persisted at round end.
        List<String> canonicalAiFixtureKeys = new ArrayList<>();

        // Pre-cache competition type IDs (avoids repeated findAll per match)
        Set<Long> cachedLeagueCompIds = gameStateService.getLeagueCompetitionIdsCached();
        Set<Long> cachedCupCompIds = gameStateService.getCupCompetitionIdsCached();
        // cached second-league set kept warm even if unused below — keeps controller's
        // legacy lazy-initialization sequence intact.
        gameStateService.getSecondLeagueCompetitionIdsCached();

        // ===== Per-round pre-load: one IN-query per entity type instead of
        // re-querying inside every helper for every match. =====
        Set<Long> participatingTeamIds = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matches) {
            participatingTeamIds.add(m.getTeam1Id());
            participatingTeamIds.add(m.getTeam2Id());
        }

        Map<Long, List<Human>> playersByTeam;
        Map<Long, List<Human>> managersByTeam;
        Map<Long, Set<Long>> injuredIdsByTeam;
        Map<Long, Set<Long>> suspendedIdsByTeam;
        Map<Long, Team> teamsById;
        Map<Long, TeamCompetitionDetail> competitionDetailsByTeam;
        Map<Long, PersonalizedTactic> tacticsByTeam;
        Map<Long, ScorerLeaderboardEntry> leaderboardByPlayer;
        if (!participatingTeamIds.isEmpty()) {
            playersByTeam = humanRepository
                    .findAllByTeamIdInAndTypeId(participatingTeamIds, TypeNames.PLAYER_TYPE)
                    .stream()
                    .collect(Collectors.groupingBy(Human::getTeamId));
            managersByTeam = humanRepository
                    .findAllByTeamIdInAndTypeId(participatingTeamIds, TypeNames.MANAGER_TYPE)
                    .stream()
                    .collect(Collectors.groupingBy(Human::getTeamId));
            injuredIdsByTeam = availabilityDisabled() ? Map.of() : injuryRepository
                    .findAllByTeamIdInAndDaysRemainingGreaterThan(participatingTeamIds, 0)
                    .stream()
                    .collect(Collectors.groupingBy(
                            Injury::getTeamId,
                            Collectors.mapping(Injury::getPlayerId, Collectors.toSet())));
            suspendedIdsByTeam = availabilityDisabled() ? Map.of() : suspensionRepository
                    .findAllByCompetitionIdAndTeamIdInAndActive(
                            _competitionId, participatingTeamIds, true)
                    .stream()
                    .collect(Collectors.groupingBy(
                            com.footballmanagergamesimulator.model.Suspension::getTeamId,
                            Collectors.mapping(
                                    com.footballmanagergamesimulator.model.Suspension::getPlayerId,
                                    Collectors.toSet())));
            teamsById = teamRepository.findAllById(participatingTeamIds).stream()
                    .collect(Collectors.toMap(Team::getId, t -> t));
            competitionDetailsByTeam = teamCompetitionDetailRepository
                    .findAllByCompetitionIdAndTeamIdIn(_competitionId, participatingTeamIds)
                    .stream()
                    .collect(Collectors.toMap(
                            TeamCompetitionDetail::getTeamId, detail -> detail,
                            (left, right) -> left));
            tacticsByTeam = personalizedTacticRepository.findAllByTeamIdIn(participatingTeamIds)
                    .stream()
                    .collect(Collectors.toMap(
                            PersonalizedTactic::getTeamId, tactic -> tactic,
                            (left, right) -> left));
            List<Long> roundPlayerIds = playersByTeam.values().stream()
                    .flatMap(List::stream)
                    .map(Human::getId)
                    .toList();
            leaderboardByPlayer = roundPlayerIds.isEmpty() ? Map.of()
                    : scorerLeaderboardRepository.findAllByPlayerIdIn(roundPlayerIds)
                            .stream()
                            .collect(Collectors.toMap(
                                    ScorerLeaderboardEntry::getPlayerId, entry -> entry,
                                    (left, right) -> left));
        } else {
            playersByTeam = Map.of();
            managersByTeam = Map.of();
            injuredIdsByTeam = Map.of();
            suspendedIdsByTeam = Map.of();
            teamsById = Map.of();
            competitionDetailsByTeam = Map.of();
            tacticsByTeam = Map.of();
            leaderboardByPlayer = Map.of();
        }

        Competition competition = competitionRepository.findById(_competitionId).orElse(null);
        String competitionName = competition != null ? competition.getName() : "";
        long competitionTypeId = competition != null ? competition.getTypeId() : 0L;
        // Availability changes every matchday. Preserve the expensive squad/skill
        // cache, but always rebuild the XI, bench and match power.
        participatingTeamIds.forEach(this::invalidateMatchdayLineupCaches);

        roundContext.set(new RoundContext(playersByTeam, managersByTeam, injuredIdsByTeam,
                suspendedIdsByTeam,
                teamsById, competitionDetailsByTeam,
                tacticsByTeam, leaderboardByPlayer,
                competition, competitionName, competitionTypeId));

        long _tPreloadDone = System.nanoTime();

        // ===== Per-operation aggregate timers (ms) =====
        long tGetRating = 0, tScorers = 0, tInjuries = 0, tMorale = 0,
             tCoeff = 0, tMgrRep = 0, tMatchStats = 0, tDetail = 0, tFinance = 0,
             tHumanFull = 0;
        int humanMatches = 0, aiMatches = 0;

        try {

        for (CompetitionTeamInfoMatch match : matches) {
            long teamId1 = match.getTeam1Id();
            long teamId2 = match.getTeam2Id();

            // Defensive skip: a cup bracket slot still holding a 0 placeholder means
            // its prelim feeder never resolved (data from before the propagation fix,
            // or a propagation bug). Don't try to simulate against a non-existent team.
            if (teamId1 <= 0 || teamId2 <= 0) {
                System.out.println("  [skip] match round=" + _roundId + " idx=" + match.getMatchIndex()
                        + " teams=" + teamId1 + " vs " + teamId2 + " — placeholder unresolved");
                continue;
            }

            boolean isHumanMatch = userContext.isHumanTeam(teamId1) || userContext.isHumanTeam(teamId2);

            int teamScore1, teamScore2;
            double teamPower1, teamPower2;
            boolean interactiveMatch = false;
            KnockoutMatchResolution knockoutResolution = null;

            if (isHumanMatch) {
                humanMatches++;
                long _tsHuman = System.nanoTime();
                // --- FULL SIMULATION for human team matches ---
                // Defensive lookup: a team could temporarily be without a manager
                // (e.g. moments after a resign / job-offer transfer if some path
                // forgot to spawn a replacement). Default to "442" rather than
                // crashing the whole round on .get(0).
                String tactic1 = getManagerTacticCached(teamId1);
                String tactic2 = getManagerTacticCached(teamId2);

                teamPower1 = lineupRatingService.getBestElevenRatingByTactic(teamId1, tactic1);
                teamPower2 = lineupRatingService.getBestElevenRatingByTactic(teamId2, tactic2);

                // When the two-axis model is the engine, use its scalar total (attack+defense of the
                // team-talk-scaled coached profile) so the live/interactive match and its deferred
                // post-match work stay consistent with the instant two-axis scoreline.
                if (engineConfig.getTacticalModel().isEnabled()) {
                    teamPower1 = twoAxisScalarPower(teamId1);
                    teamPower2 = twoAxisScalarPower(teamId2);
                }

                Optional<PersonalizedTactic> personalizedTactic1 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
                Optional<PersonalizedTactic> personalizedTactic2 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

                // The old additive tactical adjustment is the SCALAR engine's tactic lever; the
                // two-axis model handles tactics itself (via the tactic vector), so skip it when enabled.
                if (!engineConfig.getTacticalModel().isEnabled()) {
                    if (personalizedTactic1.isPresent())
                        teamPower1 = lineupRatingService.adjustTeamPowerByTacticalProperties(teamPower1, teamPower2, personalizedTactic1.get());
                    if (personalizedTactic2.isPresent())
                        teamPower2 = lineupRatingService.adjustTeamPowerByTacticalProperties(teamPower2, teamPower1, personalizedTactic2.get());
                }

                // Admin override — if a score has been forced for this match, skip the
                // live engine entirely and use the instant path with the forced score.
                int[] adminScore = teamPostMatchService.consumePredeterminedScore(_competitionId, (int) _roundId, teamId1, teamId2);

                // Check if any human manager has viewFullMatch enabled
                boolean useFullMatchEngine = false;
                long humanTeamIdForMatch = userContext.isHumanTeam(teamId1) ? teamId1 : teamId2;
                List<Human> humanManagers = humanRepository
                        .findAllByTeamIdAndTypeId(humanTeamIdForMatch, TypeNames.MANAGER_TYPE);
                Human humanManager = resolveCurrentManager(humanTeamIdForMatch, humanManagers);
                if (humanManager != null && humanManager.isViewFullMatch()
                        && !humanManager.isAlwaysContinue() && adminScore == null) {
                    useFullMatchEngine = true;
                }

                if (useFullMatchEngine) {
                    // --- INTERACTIVE LIVE MATCH (Faza 3 Sesiunea 4) ---
                    // Create the session but DO NOT advance. Frontend polls
                    // /advance to drive the engine minute-by-minute, with the
                    // user able to make manual substitutions that the engine
                    // actually respects. /commit triggers all post-match work
                    // (scorers, stats, injuries, standings, suspensions, news,
                    // post-match PC) once the user finishes the playback.
                    interactiveMatch = true;
                    boolean generateGoalAnims = humanManager != null && humanManager.isWatchGoalHighlights();
                    // Two-axis engine: derive the live chances from the attack-vs-defense matchup
                    // (same profiles + vectors the instant path uses), and stash them so /commit
                    // resolves any knockout extra time on the same model.
                    TacticalScoreService.Matchup liveMatchup = null;
                    TacticalScoreService.TeamProfile liveP1 = null, liveP2 = null;
                    TacticalScoreService.TacticVector liveT1 = null, liveT2 = null;
                    // Engine unification: when the two-axis model is on, predetermine the
                    // scoreline with the SAME instant engine the AI/instant path uses, on
                    // the SAME profiles + tactic vectors. The live narration is then pinned
                    // to this result (forced/capped goal minutes) so "it's the same game":
                    // watching live yields exactly the score the instant path would have.
                    int targetHomeGoals = -1, targetAwayGoals = -1;
                    if (engineConfig.getTacticalModel().isEnabled()) {
                        liveP1 = scaleProfile(teamTacticalProfile(teamId1), teamTalkFactor(teamId1));
                        liveP2 = scaleProfile(teamTacticalProfile(teamId2), teamTalkFactor(teamId2));
                        liveT1 = teamTacticVector(teamId1, liveP1, personalizedTactic1.orElse(null));
                        liveT2 = teamTacticVector(teamId2, liveP2, personalizedTactic2.orElse(null));
                        liveMatchup = tacticalScoreService.matchup(liveP1, liveT1, liveP2, liveT2);
                        // Seed off the match identity so the pinned score is reproducible
                        // for a given (competition, round, fixture) without coupling to the
                        // session's own RNG (which drives the narration timeline).
                        Random scoreRng = new Random(
                                _competitionId * 1_000_003L + _roundId * 31L + teamId1 * 17L + teamId2);
                        List<Integer> pinned = tacticalScoreService.score(liveP1, liveT1, liveP2, liveT2, scoreRng);
                        targetHomeGoals = pinned.get(0);
                        targetAwayGoals = pinned.get(1);
                    }
                    // Canonical knockout (blocker #3): resolve the ET/shootout split BEFORE
                    // kickoff so the whole result and all goal minutes are fixed up front — the
                    // user watches (and can substitute during) minutes 91-120, and shootout kicks
                    // stay separate from goals. Only when the plan is on and a pinned 90' score
                    // exists; the flag-off interactive path still defers the tiebreak to /commit.
                    int etHome = -1, etAway = -1, shootoutHome = -1, shootoutAway = -1;
                    if (knockout && matchPlanService.isEnabled() && targetHomeGoals >= 0) {
                        KnockoutMatchResolution pre = resolveKnockoutMatch(
                                match, teamId1, teamPower1, teamId2, teamPower2,
                                targetHomeGoals, targetAwayGoals, firstLegScores);
                        etHome = pre.et1() == null ? -1 : pre.et1();
                        etAway = pre.et2() == null ? -1 : pre.et2();
                        shootoutHome = pre.penalty1() == null ? -1 : pre.penalty1();
                        shootoutAway = pre.penalty2() == null ? -1 : pre.penalty2();
                    }
                    LiveMatchSession liveSession = liveMatchSimulationService.createInteractiveSession(
                            teamId1, teamId2, teamPower1, teamPower2,
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                            generateGoalAnims, liveMatchup, targetHomeGoals, targetAwayGoals,
                            match.getId(), tactic1, tactic2,
                            etHome, etAway, shootoutHome, shootoutAway);
                    if (liveMatchup != null) {
                        liveSession.setDeferredTwoAxis(liveP1, liveT1, liveP2, liveT2);
                    }
                    // Placeholder score — /commit overwrites with the real one
                    // after the user finishes the live playback.
                    teamScore1 = 0;
                    teamScore2 = 0;

                } else {
                    // --- INSTANT SIMULATION (original behavior) ---
                    if (adminScore != null) {
                        teamScore1 = adminScore[0];
                        teamScore2 = adminScore[1];
                    } else if (engineConfig.getTacticalModel().isEnabled()) {
                        // Two-axis model: the human's chosen PersonalizedTactic (if any) drives its
                        // tactic vector; the opponent (AI) uses its manager's skill-picked tactic.
                        // Replace teamPower with the two-axis total for consistent stats/morale/events.
                        TwoAxisResult r = twoAxisScores(
                                teamId1, personalizedTactic1.orElse(null),
                                teamId2, personalizedTactic2.orElse(null));
                        teamScore1 = r.score1();
                        teamScore2 = r.score2();
                        teamPower1 = r.power1();
                        teamPower2 = r.power2();
                    } else {
                        // All match scoring goes through the config-driven, tuned engine
                        // (MatchSimulationService). Per-player morale + fitness are already
                        // baked into teamPower (LineupRatingService → PlayerValueService); here
                        // we apply only the team-level modifiers — team talk + home advantage
                        // (team1 is the home side). Knockout ties are broken later (extra time /
                        // penalties, aggregate for two-leg).
                        List<Integer> scores = matchSimulationService.calculateScores(
                                matchSimulationService.effectiveTeamPower(teamPower1, teamTalkFactor(teamId1), true),
                                matchSimulationService.effectiveTeamPower(teamPower2, teamTalkFactor(teamId2), false));
                        teamScore1 = scores.get(0);
                        teamScore2 = scores.get(1);
                    }

                    // The regular-time (90') score, captured before resolveKnockoutMatch
                    // folds in any extra-time goals — this, not the ET-inclusive score,
                    // is what the canonical plan stores as score90.
                    int score90Home = teamScore1;
                    int score90Away = teamScore2;
                    com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit planSplit =
                            com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit
                                    .regularOnly(score90Home, score90Away);

                    if (knockout) {
                        knockoutResolution = resolveKnockoutMatch(
                                match, teamId1, teamPower1, teamId2, teamPower2,
                                teamScore1, teamScore2, firstLegScores);
                        teamScore1 = knockoutResolution.score1();
                        teamScore2 = knockoutResolution.score2();

                        // The resolution carries ET goals + shootout per team, already
                        // oriented to home/away; the split just separates them for the plan.
                        planSplit = com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit.knockout(
                                score90Home, score90Away,
                                knockoutResolution.et1(), knockoutResolution.et2(),
                                knockoutResolution.penalty1(), knockoutResolution.penalty2());
                    }

                    // Canonical pipeline (flag on): build the single MatchPlan event
                    // timeline first, then project both the display faze and the Scorer
                    // leaderboard from it so they can never disagree. Flag off: the legacy
                    // independent RNG distributions run (scorers, then synthetic faze).
                    int _season = Integer.parseInt(getCurrentSeason());
                    if (matchPlanService.isEnabled()) {
                        String fixtureKey = com.footballmanagergamesimulator.matchplan.MatchPlanService
                                .competitionFixtureKey(match.getId());
                        List<MatchEvent> canonicalEvents = matchPlanService.buildAndPersist(
                                fixtureKey, _competitionId, _season, (int) _roundId,
                                teamId1, teamId2, tactic1, tactic2,
                                planSplit.score90Home(), planSplit.score90Away(),
                                planSplit.etHome(), planSplit.etAway(),
                                planSplit.shootoutHome(), planSplit.shootoutAway());
                        lineupRatingService.getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId, (int) _roundId, tallyForTeam(canonicalEvents, teamId1));
                        lineupRatingService.getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId, (int) _roundId, tallyForTeam(canonicalEvents, teamId2));
                    } else {
                        lineupRatingService.getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId, (int) _roundId);
                        lineupRatingService.getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId, (int) _roundId);
                        matchSimulationService.generateMatchEvents(_competitionId, _season, (int) _roundId,
                                teamId1, teamId2, teamScore1, teamScore2, tactic1, tactic2);
                    }
                    // Per-player lineup ratings for the match statistics view
                    lineupRatingService.persistPlayerRatings(_competitionId, _season, (int) _roundId, teamId1, tactic1);
                    lineupRatingService.persistPlayerRatings(_competitionId, _season, (int) _roundId, teamId2, tactic2);

                    // Generate and persist match stats
                    matchStatsService.generateAndSaveMatchStats(
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                            teamId1, teamId2, teamScore1, teamScore2, teamPower1, teamPower2,
                            personalizedTactic1.orElse(null), personalizedTactic2.orElse(null));

                    // Result, Scorer and stats are now persisted in this same (simulateRound)
                    // transaction — mark the plan COMMITTED so it becomes immutable and can
                    // never be regenerated.
                    if (matchPlanService.isEnabled()) {
                        matchPlanService.markCommitted(
                                com.footballmanagergamesimulator.matchplan.MatchPlanService
                                        .competitionFixtureKey(match.getId()));
                    }
                }

                // Full post-match processing for human matches (same for both
                // non-interactive paths). Interactive matches defer ALL of this
                // to /commit so the user's manual subs change the real result.
                if (!interactiveMatch) {
                    this.processInjuriesForTeam(teamId1);
                    this.processInjuriesForTeam(teamId2);

                    teamPostMatchService.updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, teamId2);
                    teamPostMatchService.updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, teamId1);

                    if (competitionTypeId == 4 || competitionTypeId == 5) {
                        europeanCoefficientService.awardCoefficientPoints(
                                _competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                    }

                    teamPostMatchService.generateMatchReport(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                    teamPostMatchService.updateManagerReputationAfterMatch(teamId1, teamId2, teamScore1, teamScore2);
                } else {
                    // Stash team powers + tactics on the session so /commit can
                    // run the same post-match work without re-deriving them.
                    String liveKey = LiveMatchSimulationService.buildKey(
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId, teamId1, teamId2);
                    LiveMatchSession s = liveMatchSimulationService.getSession(liveKey);
                    if (s != null) {
                        s.setDeferredContext(teamPower1, teamPower2, tactic1, tactic2,
                                personalizedTactic1.orElse(null), personalizedTactic2.orElse(null),
                                knockout, match.getLegNumber(), match.getTieId(), match.getMatchIndex());
                        // Persist the same context (canonical only) so a cold-recovered session
                        // can /commit after a restart. No-op when the flag is off.
                        liveMatchSimulationService.saveLiveCommitContext(
                                _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                                teamId1, teamId2, match.getId(), tactic1, tactic2, teamPower1, teamPower2,
                                knockout, match.getLegNumber(), match.getTieId(), match.getMatchIndex());
                    }
                }

                tHumanFull += System.nanoTime() - _tsHuman;
            } else {
                aiMatches++;
                // --- OPTIMIZED SIMULATION for AI vs AI matches ---
                long _ts = System.nanoTime();
                teamPower1 = getSimpleTeamRating(teamId1);
                teamPower2 = getSimpleTeamRating(teamId2);
                tGetRating += System.nanoTime() - _ts;

                // Admin override — use forced score if set, otherwise compute normally
                int[] adminScoreAi = teamPostMatchService.consumePredeterminedScore(_competitionId, (int) _roundId, teamId1, teamId2);
                if (adminScoreAi != null) {
                    teamScore1 = adminScoreAi[0];
                    teamScore2 = adminScoreAi[1];
                } else if (engineConfig.getTacticalModel().isEnabled()) {
                    // Two-axis model: attack/defense + coaching + each manager's skill-picked tactic.
                    // Replace the scalar teamPower with the two-axis total so stats/morale/knockout stay consistent.
                    TwoAxisResult r = twoAxisScores(teamId1, null, teamId2, null);
                    teamScore1 = r.score1();
                    teamScore2 = r.score2();
                    teamPower1 = r.power1();
                    teamPower2 = r.power2();
                } else {
                    // All scoring via the tuned, config-driven engine (same as the tests).
                    // Per-player morale + fitness are already inside teamPower (PlayerValueService);
                    // only team talk + home advantage are applied here (team1 = home side).
                    List<Integer> scores = matchSimulationService.calculateScores(
                            matchSimulationService.effectiveTeamPower(teamPower1, teamTalkFactor(teamId1), true),
                            matchSimulationService.effectiveTeamPower(teamPower2, teamTalkFactor(teamId2), false),
                            random);
                    teamScore1 = scores.get(0);
                    teamScore2 = scores.get(1);
                }

                // The regular-time (90') score, captured before resolveKnockoutMatch folds in
                // any extra-time goals — this, not the ET-inclusive score, is the plan's score90.
                int score90Home = teamScore1;
                int score90Away = teamScore2;
                com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit planSplit =
                        com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit
                                .regularOnly(score90Home, score90Away);

                if (knockout) {
                    knockoutResolution = resolveKnockoutMatch(
                            match, teamId1, teamPower1, teamId2, teamPower2,
                            teamScore1, teamScore2, firstLegScores);
                    teamScore1 = knockoutResolution.score1();
                    teamScore2 = knockoutResolution.score2();

                    // ET goals + shootout per team, already oriented to home/away; the split
                    // just separates them for the plan (shootout kicks are not goal slots).
                    planSplit = com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit.knockout(
                            score90Home, score90Away,
                            knockoutResolution.et1(), knockoutResolution.et2(),
                            knockoutResolution.penalty1(), knockoutResolution.penalty2());
                }

                String aiTactic1 = getManagerTacticCached(teamId1);
                String aiTactic2 = getManagerTacticCached(teamId2);

                _ts = System.nanoTime();
                // Canonical pipeline (flag ON): build ONE MatchPlan for the fixture, resolve its
                // slots through InstantMatchExecutor + ContributionResolver, persist the canonical
                // MatchEvent timeline, and project the Scorer rows from those events (no separate
                // RNG for scorer/assist/participation). Flag OFF: the legacy simplified path.
                List<Scorer> team1Scorers;
                List<Scorer> team2Scorers;
                if (matchPlanService.isEnabled()) {
                    String fixtureKey = com.footballmanagergamesimulator.matchplan.MatchPlanService
                            .competitionFixtureKey(match.getId());
                    if (matchPlanService.isPlanCommitted(fixtureKey)) {
                        // Durable idempotency: a committed plan means this fixture's canonical
                        // artifacts + Scorer rows already exist — never resolve or project again.
                        team1Scorers = List.of();
                        team2Scorers = List.of();
                    } else {
                        int _season = Integer.parseInt(getCurrentSeason());
                        long seed = com.footballmanagergamesimulator.matchplan.MatchPlanService
                                .seedFor(fixtureKey, _competitionId, _season, (int) _roundId, teamId1, teamId2);
                        Lineup homeLineup = buildAiCanonicalLineup(teamId1, seed);
                        Lineup awayLineup = buildAiCanonicalLineup(teamId2, seed);
                        List<MatchEvent> canonicalEvents = matchPlanService.buildAndPersistLive(
                                fixtureKey, _competitionId, _season, (int) _roundId,
                                teamId1, teamId2, homeLineup, awayLineup,
                                planSplit.score90Home(), planSplit.score90Away(),
                                planSplit.etHome(), planSplit.etAway(),
                                planSplit.shootoutHome(), planSplit.shootoutAway());
                        team1Scorers = getScorersForTeamCanonical(
                                teamId1, teamId2, teamScore1, teamScore2, _competitionId, (int) _roundId,
                                homeLineup, tallyForTeam(canonicalEvents, teamId1));
                        team2Scorers = getScorersForTeamCanonical(
                                teamId2, teamId1, teamScore2, teamScore1, _competitionId, (int) _roundId,
                                awayLineup, tallyForTeam(canonicalEvents, teamId2));
                        canonicalAiFixtureKeys.add(fixtureKey);
                    }
                } else {
                    team1Scorers = getScorersForTeamSimplified(
                            teamId1, teamId2, teamScore1, teamScore2, _competitionId, (int) _roundId);
                    team2Scorers = getScorersForTeamSimplified(
                            teamId2, teamId1, teamScore2, teamScore1, _competitionId, (int) _roundId);
                }
                batchedScorers.addAll(team1Scorers);
                batchedScorers.addAll(team2Scorers);
                batchedLineups.add(new LineupRatingService.AiLineupInput(
                        teamId1, aiTactic1, starterSlotsCache.get(teamId1),
                        substitutionsCache.get(teamId1), team1Scorers));
                batchedLineups.add(new LineupRatingService.AiLineupInput(
                        teamId2, aiTactic2, starterSlotsCache.get(teamId2),
                        substitutionsCache.get(teamId2), team2Scorers));
                RoundContext activeContext = roundContext.get();
                PersonalizedTactic team1Tactic = activeContext != null
                        ? activeContext.tacticsByTeam().get(teamId1) : null;
                PersonalizedTactic team2Tactic = activeContext != null
                        ? activeContext.tacticsByTeam().get(teamId2) : null;
                batchedPlayerStats.add(new PlayerMatchStatService.TeamMatchInput(
                        teamId1, bestElevenCache.getOrDefault(teamId1, List.of()), team1Tactic));
                batchedPlayerStats.add(new PlayerMatchStatService.TeamMatchInput(
                        teamId2, bestElevenCache.getOrDefault(teamId2, List.of()), team2Tactic));
                tScorers += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                processInjuriesForTeamBatched(teamId1, random, batchedInjuries, batchedInjuredPlayers);
                processInjuriesForTeamBatched(teamId2, random, batchedInjuries, batchedInjuredPlayers);
                tInjuries += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                updateTeamWithSimpleMorale(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, random);
                updateTeamWithSimpleMorale(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, random);
                tMorale += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                if (competitionTypeId == 4 || competitionTypeId == 5) {
                    europeanCoefficientService.awardCoefficientPoints(
                            _competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                }
                tCoeff += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                batchUpdateManagerReputation(teamId1, teamId2, teamScore1, teamScore2, batchedManagers);
                tMgrRep += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                matchStatsService.generateAndSaveMatchStats(
                        _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                        teamId1, teamId2, teamScore1, teamScore2, teamPower1, teamPower2,
                        null, null);
                tMatchStats += System.nanoTime() - _ts;
            }

            // Interactive matches defer detail-record + KO progression too —
            // /commit creates them with the real final score once the user has
            // played the match through the live modal.
            String _liveKeyCheck = LiveMatchSimulationService.buildKey(
                    _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId, teamId1, teamId2);
            var _interactiveSession = liveMatchSimulationService.getSession(_liveKeyCheck);
            boolean _isInteractivePending = _interactiveSession != null && !_interactiveSession.isCommitted() && !_interactiveSession.isFinished();

            // Knockout progression (needed for both human and AI matches).
            // Single-leg: decide via extra time then penalties if level.
            // Two-leg: leg 1 is recorded but does NOT advance anyone; leg 2 aggregates
            // both legs and decides the tie (extra time + penalties on level aggregate).
            String koScoreSuffix = knockoutResolution == null ? "" : knockoutResolution.scoreSuffix();
            Long resultWinnerId = knockoutResolution == null
                    ? (teamScore1 == teamScore2 ? null : (teamScore1 > teamScore2 ? teamId1 : teamId2))
                    : knockoutResolution.winnerTeamId();
            String resultDecidedBy = knockoutResolution == null
                    ? (resultWinnerId == null ? null : "NORMAL")
                    : knockoutResolution.decidedBy();
            Integer penaltyTeam1Score = knockoutResolution == null ? null : knockoutResolution.penalty1();
            Integer penaltyTeam2Score = knockoutResolution == null ? null : knockoutResolution.penalty2();
            Integer aggregateTeam1Score = knockoutResolution == null ? null : knockoutResolution.aggregate1();
            Integer aggregateTeam2Score = knockoutResolution == null ? null : knockoutResolution.aggregate2();

            if (knockout && !_isInteractivePending && resultWinnerId != null) {
                // National cup: propagate the winner into the pre-created bracket slot.
                // For LoC/Stars Cup we keep the legacy CompetitionTeamInfo flow below.
                if (cachedCupCompIds != null && cachedCupCompIds.contains(_competitionId)
                        && match.getMatchIndex() > 0) {
                    cupBracketService.propagateWinner(
                            _competitionId, Integer.parseInt(getCurrentSeason()),
                            _roundId, match.getMatchIndex(), resultWinnerId);
                } else {
                    CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                    competitionTeamInfo.setCompetitionId(_competitionId);
                    competitionTeamInfo.setRound(nextRound);
                    competitionTeamInfo.setTeamId(resultWinnerId);
                    competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                    competitionTeamInfoRepository.save(competitionTeamInfo);
                }
            }

            // Persist the score on the match row so a later second leg (different
            // calendar day / simulateRound call) can aggregate with this result.
            if (!_isInteractivePending) {
                match.setTeam1Score(teamScore1);
                match.setTeam2Score(teamScore2);
                competitionTeamInfoMatchRepository.save(match);
            }

            // Match result record (needed for both - results page)
            if (!_isInteractivePending) {
                long _tsDetail = System.nanoTime();
                CompetitionTeamInfoDetail competitionTeamInfoDetail = new CompetitionTeamInfoDetail();
                competitionTeamInfoDetail.setCompetitionId(_competitionId);
                competitionTeamInfoDetail.setRoundId(_roundId);
                competitionTeamInfoDetail.setTeam1Id(teamId1);
                competitionTeamInfoDetail.setTeam2Id(teamId2);
                competitionTeamInfoDetail.setTeamName1(roundTeamName(teamId1));
                competitionTeamInfoDetail.setTeamName2(roundTeamName(teamId2));
                competitionTeamInfoDetail.setScore(teamScore1 + " - " + teamScore2 + koScoreSuffix);
                competitionTeamInfoDetail.setWinnerTeamId(resultWinnerId);
                competitionTeamInfoDetail.setDecidedBy(resultDecidedBy);
                competitionTeamInfoDetail.setPenaltyTeam1Score(penaltyTeam1Score);
                competitionTeamInfoDetail.setPenaltyTeam2Score(penaltyTeam2Score);
                competitionTeamInfoDetail.setAggregateTeam1Score(aggregateTeam1Score);
                competitionTeamInfoDetail.setAggregateTeam2Score(aggregateTeam2Score);
                competitionTeamInfoDetail.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                competitionTeamInfoDetail.setLegNumber(match.getLegNumber());
                competitionTeamInfoDetail.setMatchIndex(match.getMatchIndex());
                competitionTeamInfoDetail.setDay(match.getDay());
                competitionTeamInfoDetail.setTieId(match.getTieId());
                competitionTeamInfoDetailRepository.save(competitionTeamInfoDetail);
                tDetail += System.nanoTime() - _tsDetail;
            }

            // Match day income for home team (team1) — competition cached for the round
            long _tsFin = System.nanoTime();
            String compName = !competitionName.isEmpty() ? competitionName : "Match";
            batchedMatchdayIncome.add(new FinanceService.MatchDayIncomeInput(
                    teamId1, match.getDay(), teamId2, compName));
            tFinance += System.nanoTime() - _tsFin;
        }

        long _tLoopDone = System.nanoTime();

        // Batch save all collected AI match data at once
        long _tsScorerBatch = System.nanoTime();
        if (!batchedScorers.isEmpty()) scorerRepository.saveAll(batchedScorers);
        if (!batchedLineups.isEmpty()) {
            lineupRatingService.persistPlayerRatingsBatch(
                    _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                    batchedLineups);
        }
        if (!batchedPlayerStats.isEmpty()) {
            playerMatchStatService.recordRealMatchesForTeams(
                    batchedPlayerStats, _competitionId, Integer.parseInt(getCurrentSeason()));
        }
        tScorers += System.nanoTime() - _tsScorerBatch;
        long _tsFinanceBatch = System.nanoTime();
        if (!batchedMatchdayIncome.isEmpty()) {
            financeService.processMatchDayIncomeBatch(
                    Integer.parseInt(getCurrentSeason()), batchedMatchdayIncome);
        }
        tFinance += System.nanoTime() - _tsFinanceBatch;
        if (!batchedInjuries.isEmpty()) injuryRepository.saveAll(batchedInjuries);
        // Players and managers came from this transaction's round preload; their morale,
        // fitness, injury status and reputation changes are flushed by JPA dirty checking.

        // Every canonical AI plan resolved this round is now COMMITTED — immutable, and only
        // after its Scorer/rating/stats effects are durably persisted above (all in this one
        // transaction, so a rollback un-commits the plan too and a retry re-runs cleanly).
        for (String fixtureKey : canonicalAiFixtureKeys) {
            matchPlanService.markCommitted(fixtureKey);
        }

        long _tBatchDone = System.nanoTime();

        // ===== Print breakdown =====
        long totalMs = (_tBatchDone - _t0) / 1_000_000;
        long preloadMs = (_tPreloadDone - _t0) / 1_000_000;
        long loopMs = (_tLoopDone - _tPreloadDone) / 1_000_000;
        long batchMs = (_tBatchDone - _tLoopDone) / 1_000_000;
        System.out.println(String.format(
                "  [TIMER simulateRound comp=%d round=%d] total=%dms  preload=%dms  loop=%dms  batch=%dms  | matches=%d (AI=%d, human=%d)",
                _competitionId, _roundId, totalMs, preloadMs, loopMs, batchMs,
                matches.size(), aiMatches, humanMatches));
        if (aiMatches > 0) {
            System.out.println(String.format(
                    "    AI breakdown (total ms across %d matches): rating=%d  scorers=%d  injuries=%d  morale=%d  coeff=%d  mgrRep=%d  stats=%d  detail=%d  finance=%d",
                    aiMatches,
                    tGetRating / 1_000_000, tScorers / 1_000_000, tInjuries / 1_000_000,
                    tMorale / 1_000_000, tCoeff / 1_000_000, tMgrRep / 1_000_000,
                    tMatchStats / 1_000_000, tDetail / 1_000_000, tFinance / 1_000_000));
        }
        if (humanMatches > 0) {
            System.out.println(String.format(
                    "    Human breakdown: total=%dms across %d matches", tHumanFull / 1_000_000, humanMatches));
        }

        } finally {
            // Managed entities must never leak into the next task executed by this worker.
            roundContext.remove();
        }
    }

    // ============================================================
    //  Batched AI helpers (use per-round caches above)
    // ============================================================

    /**
     * Standings + simplified morale for AI teams.
     * Instead of loading all season scorers to identify who played (expensive),
     * applies a uniform morale change to all players based on W/D/L result.
     * Cost: 1 query standings + 1 query players + 1 batch save players.
     */
    private void updateTeamWithSimpleMorale(long teamId, long competitionId, int scoreHome, int scoreAway,
                                            double teamPowerDifference, Random random) {
        // 1. Update standings (same as updateTeamSimple)
        RoundContext context = roundContext.get();
        TeamCompetitionDetail team = context != null
                ? context.competitionDetailsByTeam().get(teamId)
                : teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
            if (context != null) context.competitionDetailsByTeam().put(teamId, team);
        }

        String result;
        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            result = "W";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);
        } else if (scoreHome == scoreAway) {
            result = "D";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            result = "L";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "L");
            team.setLoses(team.getLoses() + 1);
        }
        team.setGames(team.getGames() + 1);
        if (team.getForm().length() > 5)
            team.setForm(team.getForm().substring(team.getForm().length() - 5));
        // Existing standings rows are managed by the round preload. Only a newly-created
        // fallback row needs an explicit persist call.
        if (team.getId() == 0) teamCompetitionDetailRepository.save(team);

        // 2. Morale with bench tracking for AI teams — use cached players from start of round
        double baseMoraleChange = teamPostMatchService.calculateMoraleChangeForTeamDifference(result, teamPowerDifference);
        List<Human> allPlayers = roundPlayers(teamId);

        // Determine best 11 from cache to know who played vs who was benched
        Set<Long> playedIds = new HashSet<>();
        List<PlayerView> cachedBestXI = bestElevenCache.getOrDefault(teamId, List.of());
        for (PlayerView pv : cachedBestXI) {
            playedIds.add(pv.getId());
        }

        MatchEngineConfig.Morale moraleCfg = engineConfig.getMorale();
        double indVariance = moraleCfg.getIndividualVariance();
        int benchThreshold = moraleCfg.getBenchConsecutiveThreshold();
        double benchExtra = moraleCfg.getBenchConsecutiveExtra();
        int contentMinMatches = moraleCfg.getMinMatchesContent();
        double contentDrop = moraleCfg.getContentmentDropChance();
        double demand7 = moraleCfg.getTransferDemandChance7Plus();
        double demand5 = moraleCfg.getTransferDemandChance5Plus();
        double demand3 = moraleCfg.getTransferDemandChance3Plus();
        double fitnessDrain = engineConfig.getStamina().getBatchMatchFitnessDrain();
        double fitnessFloor = engineConfig.getStamina().getPostMatchFloor();

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            double moraleChange;

            if (playedIds.contains(player.getId())) {
                // Player participated
                moraleChange = baseMoraleChange + random.nextDouble(-indVariance, indVariance);
                player.setSeasonMatchesPlayed(player.getSeasonMatchesPlayed() + 1);
                player.setConsecutiveBenched(0);

                // Fatigue: a played (instant/batch) match drains fitness, recovered
                // later via training. Live matches use the per-minute stamina model.
                player.setFitness(Math.max(fitnessFloor, player.getFitness() - fitnessDrain));

                // AI player calms down if getting regular game time
                if (player.isWantsTransfer() && player.getSeasonMatchesPlayed() > contentMinMatches) {
                    if (random.nextDouble() < contentDrop) {
                        player.setWantsTransfer(false);
                    }
                }
            } else {
                // Benched
                player.setConsecutiveBenched(player.getConsecutiveBenched() + 1);
                switch (result) {
                    case "W": moraleChange = moraleCfg.getBenchWinPenalty(); break;
                    case "D": moraleChange = moraleCfg.getBenchDrawPenalty(); break;
                    case "L": moraleChange = moraleCfg.getBenchLossPenalty(); break;
                    default: moraleChange = 0;
                }
                int benched = player.getConsecutiveBenched();
                if (benched >= benchThreshold) moraleChange += benchExtra;
                // AI players also request transfers when benched too long.
                // 150 = scaled-up 50 for the 1-300 rating range (mid-tier or better).
                if (benched >= 3 && player.getRating() > 150
                        && !player.isWantsTransfer() && !player.isWillNeverLeave()) {
                    double demandChance = (benched >= 7) ? demand7 : (benched >= benchThreshold) ? demand5 : demand3;
                    if (random.nextDouble() < demandChance) {
                        player.setWantsTransfer(true);
                    }
                }
            }

            player.setMorale(Math.min(100D, Math.max(0D, player.getMorale() + moraleChange)));
        }
        // allPlayers are managed entities loaded at the start of this transaction.
    }

    /**
     * Per-match injury roll for one team (interactive live-match path + the
     * non-batched dispatch in {@link MatchdayCoordinator#finalizeInteractiveLiveMatch}).
     * Persists each injury immediately. The batched twin
     * {@link #processInjuriesForTeamBatched} is used by AI-only rounds where
     * per-round caches are warm.
     */
    public void processInjuriesForTeam(long teamId) {
        if (availabilityDisabled()) return;
        Random random = threadRandom.get();
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        Set<Long> injuredPlayerIds = injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream().map(Injury::getPlayerId).collect(java.util.stream.Collectors.toSet());

        String[] injuryTypes = {"Hamstring Strain", "Knee Ligament", "Ankle Sprain", "Muscle Fatigue", "Broken Bone", "Concussion"};

        for (Human player : players) {
            if (player.isRetired()) continue;
            if (injuredPlayerIds.contains(player.getId())) continue;

            double injuryChance = engineConfig.getInjuries().getBaseChance();
            if (player.getFitness() < engineConfig.getInjuries().getLowFitnessThreshold())
                injuryChance += engineConfig.getInjuries().getLowFitnessBonus();

            if (random.nextDouble() < injuryChance) {
                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(teamId);
                injury.setSeasonNumber(Integer.parseInt(getCurrentSeason()));

                String injuryType = injuryTypes[random.nextInt(injuryTypes.length)];
                injury.setInjuryType(injuryType);

                MatchEngineConfig.Injuries inj = engineConfig.getInjuries();
                double severityRoll = random.nextDouble();
                if (severityRoll < inj.getMinorThreshold()) {
                    injury.setSeverity("Minor");
                    injury.setDaysRemaining(random.nextInt(inj.getMinorMinDays(), inj.getMinorMaxDays() + 1));
                } else if (severityRoll < inj.getModerateThreshold()) {
                    injury.setSeverity("Moderate");
                    injury.setDaysRemaining(random.nextInt(inj.getModerateMinDays(), inj.getModerateMaxDays() + 1));
                } else {
                    injury.setSeverity("Serious");
                    injury.setDaysRemaining(random.nextInt(inj.getSeriousMinDays(), inj.getSeriousMaxDays() + 1));
                }
                injuryTimelineService.scheduleFromCurrentDate(
                        injury, Integer.parseInt(getCurrentSeason()), injury.getDaysRemaining());

                injuryRepository.save(injury);

                player.setCurrentStatus("Injured - " + injuryType);
                humanRepository.save(player);
            }
        }
    }

    /**
     * Batched injury processing for AI teams.
     * Same logic as processInjuriesForTeam but collects injuries into lists
     * for a single batch save at the end of the round.
     */
    private void processInjuriesForTeamBatched(long teamId, Random random,
                                               List<Injury> batchedInjuries, List<Human> batchedInjuredPlayers) {
        if (availabilityDisabled()) return;
        // Use per-round caches when available (populated at start of simulateRound)
        List<Human> players = roundPlayers(teamId);
        Set<Long> injuredPlayerIds = roundInjuredIds(teamId);

        String[] injuryTypes = {"Hamstring Strain", "Knee Ligament", "Ankle Sprain", "Muscle Fatigue", "Broken Bone", "Concussion"};

        for (Human player : players) {
            if (player.isRetired()) continue;
            if (injuredPlayerIds.contains(player.getId())) continue;

            double injuryChance = engineConfig.getInjuries().getBaseChance();
            if (player.getFitness() < engineConfig.getInjuries().getLowFitnessThreshold())
                injuryChance += engineConfig.getInjuries().getLowFitnessBonus();

            if (random.nextDouble() < injuryChance) {
                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(teamId);
                injury.setSeasonNumber(Integer.parseInt(getCurrentSeason()));

                String injuryType = injuryTypes[random.nextInt(injuryTypes.length)];
                injury.setInjuryType(injuryType);

                MatchEngineConfig.Injuries inj = engineConfig.getInjuries();
                double severityRoll = random.nextDouble();
                if (severityRoll < inj.getMinorThreshold()) {
                    injury.setSeverity("Minor");
                    injury.setDaysRemaining(random.nextInt(inj.getMinorMinDays(), inj.getMinorMaxDays() + 1));
                } else if (severityRoll < inj.getModerateThreshold()) {
                    injury.setSeverity("Moderate");
                    injury.setDaysRemaining(random.nextInt(inj.getModerateMinDays(), inj.getModerateMaxDays() + 1));
                } else {
                    injury.setSeverity("Serious");
                    injury.setDaysRemaining(random.nextInt(inj.getSeriousMinDays(), inj.getSeriousMaxDays() + 1));
                }
                injuryTimelineService.scheduleFromCurrentDate(
                        injury, Integer.parseInt(getCurrentSeason()), injury.getDaysRemaining());

                batchedInjuries.add(injury);
                player.setCurrentStatus("Injured - " + injuryType);
                batchedInjuredPlayers.add(player);
            }
        }
    }

    /**
     * Batched manager reputation update for AI matches.
     * Collects manager changes into a list for a single batch save at end of round.
     */
    private void batchUpdateManagerReputation(long teamId1, long teamId2, int score1, int score2,
                                              List<Human> batchedManagers) {
        // Use per-round cached team entities (populated at start of simulateRound)
        Team team1 = roundTeam(teamId1);
        Team team2 = roundTeam(teamId2);
        if (team1 == null || team2 == null) return;

        RoundContext context = roundContext.get();
        List<Human> managers1 = context != null
                ? context.managersByTeam().getOrDefault(teamId1, List.of())
                : humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE);
        List<Human> managers2 = context != null
                ? context.managersByTeam().getOrDefault(teamId2, List.of())
                : humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE);

        if (!managers1.isEmpty()) {
            double change = matchSimulationService.calculateMatchRepChange(score1, score2, team1.getReputation(), team2.getReputation());
            Human mgr = managers1.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            batchedManagers.add(mgr);
        }
        if (!managers2.isEmpty()) {
            double change = matchSimulationService.calculateMatchRepChange(score2, score1, team2.getReputation(), team1.getReputation());
            Human mgr = managers2.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            batchedManagers.add(mgr);
        }
    }

    // ============================================================
    //  AI rating fast-path (uses persistent AI caches)
    // ============================================================

    /**
     * Matchday team value for AI teams: selects the best 11 by the manager's tactic, then
     * sums each starter's match value via {@link PlayerValueService} — position-weighted
     * attributes × familiarity (slot vs natural position) × morale × fitness. Cached per round.
     *
     * <p>Per-player morale and fitness now live inside this value (not the team-level
     * effective-power curve), so a fitter / higher-morale eleven yields a higher team value.
     * Team talk and home advantage are applied later via
     * {@link MatchSimulationService#effectiveTeamPower}.
     */
    private double getSimpleTeamRating(long teamId) {
        if (simpleRatingCache.containsKey(teamId)) return simpleRatingCache.get(teamId);

        // Get and cache manager's preferred tactic
        String tactic = getManagerTacticCached(teamId);

        // AI simulation only needs match data, not the nation/face/UI enrichment performed by
        // TacticController. Select from the already batch-loaded squad and create compact views.
        FormationEvaluationSquad evaluationSquad = formationSquadCache.computeIfAbsent(
                teamId, this::loadFormationEvaluationSquad);
        List<FormationEvaluationStarter> selected =
                selectFormationStarters(evaluationSquad, tactic);
        List<TacticController.StarterSlot> starters = selected.stream()
                .map(starter -> new TacticController.StarterSlot(
                        toMatchPlayerView(starter.player()), starter.usedPosition()))
                .toList();
        starterSlotsCache.put(teamId, starters);
        bestElevenCache.put(teamId, starters.stream().map(TacticController.StarterSlot::player).toList());

        // Also pre-cache substitutions (will be needed by getScorersForTeamSimplified)
        List<PlayerView> subs = selectMatchSubstitutes(evaluationSquad, selected, tactic);
        substitutionsCache.put(teamId, subs);

        // Skills were loaded once for the entire squad during formation ranking.
        Map<Long, PlayerSkills> skillsById = evaluationSquad.skillsByPlayerId();

        double rating = 0;
        for (TacticController.StarterSlot slot : starters) {
            PlayerView pv = slot.player();
            String natural = pv.getPosition();
            String used = slot.usedPosition();
            PlayerSkills skills = skillsById.get(pv.getId());
            if (skills != null) {
                rating += playerValueService.evaluatePlayer(skills, natural, used, pv.getMorale(), pv.getFitness());
            } else {
                rating += playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
            }
        }

        simpleRatingCache.put(teamId, rating);
        return rating;
    }

    /**
     * Drop the cached base rating / best-eleven / substitutions / tactic for one
     * team so the next match recomputes them from current data. Called when a
     * team's squad or ratings change mid-session (training, transfers) so AI base
     * power actually evolves over a simulated season instead of staying frozen.
     */
    // ============================================================
    //  Two-axis tactical model (production path; only when tactical-model.enabled)
    // ============================================================

    /** Coached attack/defense profile for a team (best 11 → positional split → manager coaching),
     *  cached per round like the scalar rating. */
    private TacticalScoreService.TeamProfile teamTacticalProfile(long teamId) {
        TacticalScoreService.TeamProfile cached = profileCache.get(teamId);
        if (cached != null) return cached;

        String tactic = getManagerTacticCached(teamId);
        double[] coach = coachAbilities(teamId);
        List<TacticalScoreService.StarterValue> starters = starterValues(teamId, tactic);
        TacticalScoreService.TeamProfile coached = tacticalScoreService.coachedProfile(
                tacticalScoreService.profile(starters), coach[0], coach[1]);
        profileCache.put(teamId, coached);
        wideShareCache.put(teamId, wideShare(starters));
        return coached;
    }

    private static final java.util.Set<String> WIDE_POSITIONS = java.util.Set.of("ML", "MR", "DL", "DR");

    /** Share of the XI's match value sitting in wide positions (for the AI width identity). */
    private static double wideShare(List<TacticalScoreService.StarterValue> starters) {
        double total = 0, wide = 0;
        for (TacticalScoreService.StarterValue s : starters) {
            total += s.value();
            if (WIDE_POSITIONS.contains(s.usedPosition())) wide += s.value();
        }
        return total <= 0 ? 0 : wide / total;
    }

    /** Evaluate the best-eleven match values for a team under a given formation (used position kept
     *  for position familiarity). Shared by the profile build and the formation ranking. */
    private List<TacticalScoreService.StarterValue> starterValues(long teamId, String formation) {
        List<TacticController.StarterSlot> starters = starterSlotsCache.get(teamId);
        FormationEvaluationSquad evaluationSquad = formationSquadCache.computeIfAbsent(
                teamId, this::loadFormationEvaluationSquad);
        if (starters == null) {
            starters = selectFormationStarters(evaluationSquad, formation).stream()
                    .map(starter -> new TacticController.StarterSlot(
                            toMatchPlayerView(starter.player()), starter.usedPosition()))
                    .toList();
            starterSlotsCache.put(teamId, starters);
            bestElevenCache.put(teamId, starters.stream()
                    .map(TacticController.StarterSlot::player).toList());
        }
        Map<Long, PlayerSkills> skillsById = evaluationSquad.skillsByPlayerId();
        // §D: per-player role + instructions from the saved tactic count in the two-axis value too.
        Map<Long, FormationData> savedById = savedRoleData(teamId);

        List<TacticalScoreService.StarterValue> values = new ArrayList<>();
        for (TacticController.StarterSlot slot : starters) {
            PlayerView pv = slot.player();
            String natural = pv.getPosition(), used = slot.usedPosition();
            PlayerSkills sk = skillsById.get(pv.getId());
            double v = sk != null
                    ? playerValueService.evaluatePlayer(sk, natural, used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
            v *= roleInstructionFactor(savedById.get(pv.getId()), sk, used);
            double[] apt = TacticalScoreService.playerAptitudes(sk, pv.getFitness());
            values.add(new TacticalScoreService.StarterValue(used, v, apt[0], apt[1], apt[2]));
        }
        return values;
    }

    /** Combined role-suitability × instruction multiplier for a starter, from the team's saved tactic.
     *  Mirrors {@code LineupRatingService}: role (when set) blends into the positional base via
     *  {@code computeEffectiveRating}; instructions give a small ±multiplier. Returns 1.0 when no saved
     *  role/instructions (so AI teams + unset humans are unchanged → determinism preserved). */
    private double roleInstructionFactor(FormationData fd, PlayerSkills sk, String used) {
        if (fd == null) return 1.0;
        String usedBase = TacticService.getBasePosition(used);
        double factor = 1.0;
        if (sk != null && fd.getRole() != null && !fd.getRole().isEmpty()) {
            double positional = playerValueService.computePositionalValue(sk, usedBase);
            if (positional > 0) {
                double effective = playerRoleService.computeEffectiveRating(sk, fd.getRole(), positional);
                factor *= effective / positional;
            }
        }
        factor *= playerInstructionService.computeInstructionMultiplier(fd.getInstructions(), usedBase, "general");
        return factor;
    }

    /** playerId → saved FormationData (role/duty/instructions) parsed from the team's PersonalizedTactic
     *  {@code first11} JSON. Empty when no saved tactic (AI teams). */
    private Map<Long, FormationData> savedRoleData(long teamId) {
        Map<Long, FormationData> map = new HashMap<>();
        personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId).ifPresent(pt -> {
            String json = pt.getFirst11();
            if (json == null || json.isBlank()) return;
            try {
                for (FormationData fd : roleObjectMapper.readValue(json, new TypeReference<List<FormationData>>() {})) {
                    if (fd.getPlayerId() > 0) map.put(fd.getPlayerId(), fd);
                }
            } catch (Exception ignored) { /* malformed JSON → no role data, value unchanged */ }
        });
        return map;
    }

    private final ObjectMapper roleObjectMapper = new ObjectMapper();

    /** The tactic vector a team plays: a human's chosen {@code PersonalizedTactic} when present,
     *  otherwise the AI manager's skill-ranked pick (cached per round). */
    private TacticalScoreService.TacticVector teamTacticVector(long teamId,
            TacticalScoreService.TeamProfile profile, PersonalizedTactic personalized) {
        if (personalized != null) {
            return tacticalScoreService.vector(personalized);
        }
        TacticalScoreService.TacticVector cached = tacticVectorCache.get(teamId);
        if (cached != null) return cached;
        double[] coach = coachAbilities(teamId);
        double pickAbility = alwaysUseBestPossibleTactic(teamId)
                ? 100.0
                : (coach[0] + coach[1]) / 2.0;
        // AI optimizes against a mirror of its own coached profile (an even, representative matchup).
        PersonalizedTactic chosen = managerTacticService.chooseTactic(profile, profile, pickAbility);
        // Width is a squad-shape identity (invisible against a width-neutral panel), set from the XI shape.
        Double ws = wideShareCache.get(teamId);
        if (ws != null) chosen.setWidth(managerTacticService.widthIdentity(ws));
        TacticalScoreService.TacticVector v = tacticalScoreService.vector(chosen);
        tacticVectorCache.put(teamId, v);
        return v;
    }

    /** Two-axis match result: scoreline + the scalar "team power" (attack+defense of the
     *  team-talk-scaled coached profile) so downstream consumers (match stats, morale power-diff,
     *  knockout tie resolution, live deferred context) stay consistent with the new model. */
    private record TwoAxisResult(int score1, int score2, double power1, double power2) {}

    /** Score a match with the two-axis model (team1 = home). Applies the manager-reputation team
     *  talk by scaling each side's profile, and uses the simulator's seeded RNG for determinism. */
    private TwoAxisResult twoAxisScores(long teamId1, PersonalizedTactic pt1, long teamId2, PersonalizedTactic pt2) {
        TacticalScoreService.TeamProfile p1 = scaleProfile(teamTacticalProfile(teamId1), teamTalkFactor(teamId1));
        TacticalScoreService.TeamProfile p2 = scaleProfile(teamTacticalProfile(teamId2), teamTalkFactor(teamId2));
        List<Integer> scores = tacticalScoreService.score(
                p1, teamTacticVector(teamId1, p1, pt1),
                p2, teamTacticVector(teamId2, p2, pt2),
                threadRandom.get());
        return new TwoAxisResult(scores.get(0), scores.get(1),
                p1.attack() + p1.defense(), p2.attack() + p2.defense());
    }

    private static TacticalScoreService.TeamProfile scaleProfile(TacticalScoreService.TeamProfile p, double k) {
        return new TacticalScoreService.TeamProfile(p.attack() * k, p.defense() * k);
    }

    /** Public scoreline for a standalone match (e.g. a friendly) using the SAME engine as competitive
     *  matches: the two-axis model when enabled (so squad value + tactics + the new axes apply), else
     *  the scalar fallback. Avoids a divergent scalar copy in {@code FriendlyMatchService}. */
    public record MatchOutcome(int homeGoals, int awayGoals, double homePower, double awayPower) {}

    public MatchOutcome scoreStandaloneMatch(long homeTeamId, long awayTeamId) {
        if (engineConfig.getTacticalModel().isEnabled()) {
            TwoAxisResult r = twoAxisScores(homeTeamId, null, awayTeamId, null);
            return new MatchOutcome(r.score1(), r.score2(), r.power1(), r.power2());
        }
        double hp = getSimpleTeamRating(homeTeamId), ap = getSimpleTeamRating(awayTeamId);
        List<Integer> s = matchSimulationService.calculateScores(hp, ap, threadRandom.get());
        return new MatchOutcome(s.get(0), s.get(1), hp, ap);
    }

    /** Canonical knockout result, already aligned to the displayed team1/team2 row. */
    /** Per-player [goals, assists] for one team, projected from canonical MatchPlan events. */
    private Map<Long, int[]> tallyForTeam(List<MatchEvent> events, long teamId) {
        Map<Long, int[]> tally = new HashMap<>();
        for (MatchEvent e : events) {
            if (e.getTeamId() != teamId) continue;
            boolean goal = "goal".equals(e.getEventType());
            boolean assist = "assist".equals(e.getEventType());
            if (!goal && !assist) continue;
            int[] ga = tally.computeIfAbsent(e.getPlayerId(), k -> new int[2]);
            if (goal) ga[0]++; else ga[1]++;
        }
        return tally;
    }

    private record KnockoutMatchResolution(
            int score1, int score2, String scoreSuffix,
            Long winnerTeamId, String decidedBy,
            Integer penalty1, Integer penalty2,
            Integer aggregate1, Integer aggregate2,
            Integer et1, Integer et2) {} // extra-time goals for team1/team2, null when no ET

    /**
     * Resolve extra time / penalties before scorers, statistics and standings are
     * written. This makes the final football score (including extra-time goals)
     * the source of truth for every downstream system. Shootout goals remain a
     * separate score, as they do not belong to the match score.
     */
    private KnockoutMatchResolution resolveKnockoutMatch(
            CompetitionTeamInfoMatch match,
            long teamId1, double teamPower1, long teamId2, double teamPower2,
            int score1, int score2, Map<Long, int[]> firstLegScores) {
        int legNumber = match.getLegNumber();
        long tieId = match.getTieId();

        if (legNumber == 1 && tieId != 0) {
            firstLegScores.put(tieId, new int[]{score1, score2});
            return new KnockoutMatchResolution(
                    score1, score2, " (1st leg)", null, "FIRST_LEG",
                    null, null, null, null, null, null);
        }

        int[] leg1 = null;
        if (legNumber == 2 && tieId != 0) {
            leg1 = firstLegScores.get(tieId);
            if (leg1 == null) {
                CompetitionTeamInfoMatch leg1Row = competitionTeamInfoMatchRepository
                        .findByTieIdAndLegNumber(tieId, 1).orElse(null);
                if (leg1Row != null && leg1Row.getTeam1Score() >= 0) {
                    leg1 = new int[]{leg1Row.getTeam1Score(), leg1Row.getTeam2Score()};
                }
            }
        }

        if (legNumber == 2 && tieId != 0 && leg1 != null) {
            // team1 hosts leg 2 (= side B), team2 was the leg-1 host (= side A).
            int aggregateA = leg1[0] + score2;
            int aggregateB = leg1[1] + score1;
            var decision = decideTie(teamId2, teamPower2, teamId1, teamPower1, aggregateA, aggregateB);

            // Extra-time goals are part of the second-leg football score and aggregate.
            score1 += decision.etB();
            score2 += decision.etA();
            int finalAggregate1 = aggregateB + decision.etB();
            int finalAggregate2 = aggregateA + decision.etA();
            Long winner = decision.teamAWon() ? teamId2 : teamId1;
            Integer penalty1 = decision.penalties() ? decision.penaltyB() : null;
            Integer penalty2 = decision.penalties() ? decision.penaltyA() : null;
            String decidedBy = decision.penalties() ? "PENALTIES"
                    : decision.extraTime() ? "EXTRA_TIME" : "AGGREGATE";
            String suffix = " (agg " + finalAggregate1 + "-" + finalAggregate2
                    + (decision.penalties()
                    ? ", pens " + penalty1 + "-" + penalty2
                    : decision.extraTime() ? ", a.e.t." : "") + ")";
            // team1 = side B, team2 = side A (see orientation above).
            Integer et1 = decision.extraTime() ? decision.etB() : null;
            Integer et2 = decision.extraTime() ? decision.etA() : null;
            return new KnockoutMatchResolution(
                    score1, score2, suffix, winner, decidedBy,
                    penalty1, penalty2, finalAggregate1, finalAggregate2, et1, et2);
        }

        // Single-leg knockout, including the defensive fallback for a missing leg 1.
        var decision = decideTie(teamId1, teamPower1, teamId2, teamPower2, score1, score2);
        score1 += decision.etA();
        score2 += decision.etB();
        Long winner = decision.teamAWon() ? teamId1 : teamId2;
        Integer penalty1 = decision.penalties() ? decision.penaltyA() : null;
        Integer penalty2 = decision.penalties() ? decision.penaltyB() : null;
        String decidedBy = decision.penalties() ? "PENALTIES"
                : decision.extraTime() ? "EXTRA_TIME" : "NORMAL";
        String suffix = decision.penalties()
                ? " (pens " + penalty1 + "-" + penalty2 + ")"
                : decision.extraTime() ? " (a.e.t.)" : "";
        Integer et1 = decision.extraTime() ? decision.etA() : null;
        Integer et2 = decision.extraTime() ? decision.etB() : null;
        return new KnockoutMatchResolution(
                score1, score2, suffix, winner, decidedBy,
                penalty1, penalty2, null, null, et1, et2);
    }

    /** Resolve a knockout tie's tiebreak (extra time + penalties). Under the two-axis model the
     *  extra-time mini-match runs on the attack-vs-defense engine (rebuilding each side's team-talk
     *  scaled profile + tactic vector, as used to score the match); otherwise it falls back to the
     *  scalar resolver. "A" is the side whose aggregate is {@code aggA}. */
    private com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver.TieDecision decideTie(
            long teamIdA, double powerA, long teamIdB, double powerB, int aggA, int aggB) {
        Random random = threadRandom.get();
        if (engineConfig.getTacticalModel().isEnabled()) {
            TacticalScoreService.TeamProfile pA = scaleProfile(teamTacticalProfile(teamIdA), teamTalkFactor(teamIdA));
            TacticalScoreService.TeamProfile pB = scaleProfile(teamTacticalProfile(teamIdB), teamTalkFactor(teamIdB));
            PersonalizedTactic ptA = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamIdA).orElse(null);
            PersonalizedTactic ptB = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamIdB).orElse(null);
            return tieResolver.decide(pA, teamTacticVector(teamIdA, pA, ptA),
                    pB, teamTacticVector(teamIdB, pB, ptB), aggA, aggB, random);
        }
        return tieResolver.decide(powerA, powerB, aggA, aggB, random);
    }

    /** Two-axis-consistent scalar "team power" (attack+defense of the team-talk-scaled coached
     *  profile) — used where the scalar engine fed a single power (live match, stats, morale). */
    private double twoAxisScalarPower(long teamId) {
        TacticalScoreService.TeamProfile p = scaleProfile(teamTacticalProfile(teamId), teamTalkFactor(teamId));
        return p.attack() + p.defense();
    }

    private double[] coachAbilities(long teamId) {
        return Optional.ofNullable(tacticalManager(teamId))
                .map(m -> new double[]{m.getOffensiveAbility(), m.getDefensiveAbility()})
                .orElse(new double[]{50.0, 50.0});
    }

    private Human tacticalManager(long teamId) {
        RoundContext context = roundContext.get();
        List<Human> managers = context != null
                ? context.managersByTeam().getOrDefault(teamId, List.of())
                : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        if (managers.isEmpty()) return null;
        if (userContext.isHumanTeam(teamId)) {
            return resolveCurrentManager(teamId, managers);
        }
        return managers.stream().filter(manager -> !manager.isRetired()).findFirst()
                .orElse(managers.get(0));
    }

    private boolean alwaysUseBestPossibleTactic(long teamId) {
        Human manager = tacticalManager(teamId);
        return manager != null && manager.isAlwaysUseBestPossibleTactic();
    }

    public void invalidateRatingCache(long teamId) {
        simpleRatingCache.remove(teamId);
        bestElevenCache.remove(teamId);
        starterSlotsCache.remove(teamId);
        substitutionsCache.remove(teamId);
        managerTacticCache.remove(teamId);
        profileCache.remove(teamId);
        formationSquadCache.remove(teamId);
        formationValueCache.remove(teamId);
        // Deliberately KEEP tacticVectorCache + wideShareCache: the AI manager's tactic choice is
        // stable across a season — it
        // does not need re-deriving after every training session (~88×/season). These are cleared only
        // at season transition via invalidateAllRatingCaches(). Match strength still refreshes because
        // the rating/profile caches above are dropped.
    }

    /**
     * A manager-editor change must refresh both formation and two-axis tactic
     * choices immediately, without turning ordinary training invalidations into
     * an expensive full tactical recalculation.
     */
    public void invalidateManagerTacticPolicy(long teamId) {
        managerTacticCache.remove(teamId);
        tacticVectorCache.remove(teamId);
    }

    private void invalidateMatchdayLineupCaches(long teamId) {
        simpleRatingCache.remove(teamId);
        bestElevenCache.remove(teamId);
        starterSlotsCache.remove(teamId);
        substitutionsCache.remove(teamId);
        profileCache.remove(teamId);
        wideShareCache.remove(teamId);
    }

    /** Drop ALL cached AI ratings — used at season transition (ageing + mass
     *  rating recompute affect every team at once). */
    public void invalidateAllRatingCaches() {
        simpleRatingCache.clear();
        bestElevenCache.clear();
        starterSlotsCache.clear();
        substitutionsCache.clear();
        managerTacticCache.clear();
        profileCache.clear();
        tacticVectorCache.clear();
        wideShareCache.clear();
        formationSquadCache.clear();
        formationValueCache.clear();
    }

    /** Test-only: expose the (cached) AI base rating so tests can prove the cache
     *  refreshes after {@link #invalidateRatingCache(long)}. Never call from
     *  production code. */
    public double aiBaseRatingForTest(long teamId) {
        return getSimpleTeamRating(teamId);
    }

    /** Test-only: the AI formation chosen for a team (value-ranked, skill-picked). */
    public String chooseFormationForTest(long teamId) {
        return chooseFormation(teamId);
    }

    /** Test-only: the base squad value a formation yields for a team. */
    public double formationBaseValueForTest(long teamId, String formation) {
        return formationBaseValue(teamId, formation);
    }

    private String getManagerTacticCached(long teamId) {
        if (managerTacticCache.containsKey(teamId)) return managerTacticCache.get(teamId);
        String tactic = chooseFormation(teamId);
        managerTacticCache.put(teamId, tactic);
        return tactic;
    }

    /**
     * Pick a team's formation. A human-managed team keeps the manager's chosen
     * {@code tacticStyle}. An AI manager with the editor trait enabled always takes
     * the value-maximal formation. Other AI managers keep the preferred formation
     * in the scalar fallback or pick a value rank according to coaching skill in
     * the two-axis model.
     */
    private String chooseFormation(long teamId) {
        Human manager = tacticalManager(teamId);
        String preferred = manager != null && manager.getTacticStyle() != null
                ? manager.getTacticStyle() : "442";
        if (userContext.isHumanTeam(teamId)) {
            return preferred;
        }
        List<String> formations = tacticService.getAllExistingTactics();
        if (formations.isEmpty()) return preferred;
        List<String> ranked = formations.stream()
                .sorted(Comparator.comparingDouble((String f) -> formationBaseValue(teamId, f)).reversed())
                .toList();
        if (manager != null && manager.isAlwaysUseBestPossibleTactic()) {
            return ranked.get(0);
        }
        if (!engineConfig.getTacticalModel().isEnabled()) {
            return preferred;
        }
        double[] coach = coachAbilities(teamId);
        double skill = Math.max(0, Math.min(100, (coach[0] + coach[1]) / 2.0));
        int index = (int) Math.round((100 - skill) / 100.0 * (ranked.size() - 1));
        return ranked.get(index);
    }

    /** The base squad value (attack + defense, pre-coaching) a formation yields for a team. */
    private double formationBaseValue(long teamId, String formation) {
        return formationValueCache
                .computeIfAbsent(teamId, ignored -> new HashMap<>())
                .computeIfAbsent(formation, ignored -> calculateFormationBaseValue(teamId, formation));
    }

    /**
     * Fast formation-only evaluation. The old path called TacticController for every candidate
     * formation; that controller intentionally enriches every PlayerView with nation/face/UI data,
     * which caused hundreds of redundant queries per team and pegged a CPU during matchdays.
     *
     * <p>This path loads players and skills once, selects the same natural-position-first XI, and
     * evaluates the exact PlayerValue/TacticalScore formula. The selected lineup is then reused for
     * scoring, analytics, and the historical match snapshot.
     */
    private double calculateFormationBaseValue(long teamId, String formation) {
        FormationEvaluationSquad squad = formationSquadCache.computeIfAbsent(
                teamId, this::loadFormationEvaluationSquad);
        if (squad.players().isEmpty()) return 0;

        List<FormationEvaluationStarter> selected = selectFormationStarters(squad, formation);
        List<TacticalScoreService.StarterValue> values = new ArrayList<>();
        for (FormationEvaluationStarter starter : selected) {
            Human player = starter.player();
            PlayerSkills skills = squad.skillsByPlayerId().get(player.getId());
            double value = skills != null
                    ? playerValueService.evaluatePlayer(
                            skills, player.getPosition(), starter.usedPosition(),
                            player.getMorale(), player.getFitness())
                    : playerValueService.evaluatePlayer(
                            player.getRating(), player.getPosition(), starter.usedPosition(),
                            player.getMorale(), player.getFitness());
            double[] aptitudes = TacticalScoreService.playerAptitudes(skills, player.getFitness());
            values.add(new TacticalScoreService.StarterValue(
                    starter.usedPosition(), value, aptitudes[0], aptitudes[1], aptitudes[2]));
        }

        TacticalScoreService.TeamProfile profile = tacticalScoreService.profile(values);
        return profile.attack() + profile.defense();
    }

    private List<FormationEvaluationStarter> selectFormationStarters(
            FormationEvaluationSquad squad, String formation) {
        List<String> requiredSlots = new ArrayList<>();
        tacticService.getRoomInTeamByTactic(formation).entrySet().stream()
                .sorted(Comparator.comparingInt(entry ->
                        tacticService.getValueForTacticDisplay(entry.getKey())))
                .forEach(entry -> {
                    for (int i = 0; i < entry.getValue(); i++) requiredSlots.add(entry.getKey());
                });

        Set<Long> unavailableIds = roundUnavailableIds(squad.teamId());
        List<Human> remaining = squad.players().stream()
                .filter(player -> !unavailableIds.contains(player.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        List<FormationEvaluationStarter> selected = new ArrayList<>();
        List<String> unfilledSlots = new ArrayList<>();

        // Preserve owner/board XI locks. The UI controller applies the same locks; keeping them in
        // this compact selector avoids a performance regression changing the team that takes field.
        for (Map.Entry<Long, String> lock : squad.lockedPositionByPlayerId().entrySet()) {
            Human lockedPlayer = remaining.stream()
                    .filter(player -> player.getId() == lock.getKey())
                    .findFirst()
                    .orElse(null);
            if (lockedPlayer == null) continue;
            remaining.remove(lockedPlayer);
            selected.add(new FormationEvaluationStarter(lockedPlayer, lock.getValue()));
            requiredSlots.remove(lock.getValue());
        }

        for (String slot : requiredSlots) {
            Human bestNatural = remaining.stream()
                    .filter(player -> slot.equals(player.getPosition()))
                    .max(Comparator.comparingDouble(this::formationAptness))
                    .orElse(null);
            if (bestNatural == null) {
                unfilledSlots.add(slot);
            } else {
                remaining.remove(bestNatural);
                selected.add(new FormationEvaluationStarter(bestNatural, slot));
            }
        }

        remaining.sort(Comparator.comparingDouble(this::formationAptness).reversed());
        for (int i = 0; i < unfilledSlots.size() && i < remaining.size(); i++) {
            selected.add(new FormationEvaluationStarter(remaining.get(i), unfilledSlots.get(i)));
        }
        return selected;
    }

    private List<PlayerView> selectMatchSubstitutes(
            FormationEvaluationSquad squad,
            List<FormationEvaluationStarter> starters,
            String formation) {
        Set<Long> starterIds = starters.stream()
                .map(starter -> starter.player().getId())
                .collect(Collectors.toSet());
        Set<Long> unavailableIds = roundUnavailableIds(squad.teamId());
        List<Human> remaining = squad.players().stream()
                .filter(player -> !unavailableIds.contains(player.getId()))
                .filter(player -> !starterIds.contains(player.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        List<PlayerView> selected = new ArrayList<>();

        List<String> preferredPositions = new ArrayList<>();
        tacticService.getSubstitutionsInTeamByTactic(formation).entrySet().stream()
                .sorted(Comparator.comparingInt(entry ->
                        tacticService.getValueForTacticDisplay(entry.getKey())))
                .forEach(entry -> {
                    for (int i = 0; i < entry.getValue(); i++) {
                        preferredPositions.add(entry.getKey());
                    }
                });

        for (String position : preferredPositions) {
            if (selected.size() >= 5) break;
            Human best = remaining.stream()
                    .filter(player -> position.equals(player.getPosition()))
                    .max(Comparator.comparingDouble(this::formationAptness))
                    .orElse(null);
            if (best != null) {
                remaining.remove(best);
                selected.add(toMatchPlayerView(best));
            }
        }
        remaining.sort(Comparator.comparingDouble(this::formationAptness).reversed());
        for (Human player : remaining) {
            if (selected.size() >= 5) break;
            selected.add(toMatchPlayerView(player));
        }
        return selected;
    }

    private PlayerView toMatchPlayerView(Human player) {
        PlayerView view = new PlayerView();
        view.setId(player.getId());
        view.setAge(player.getAge());
        view.setPosition(player.getPosition());
        view.setRating(player.getRating());
        view.setName(player.getName());
        view.setMorale(player.getMorale());
        view.setFitness(player.getFitness());
        return view;
    }

    private FormationEvaluationSquad loadFormationEvaluationSquad(long teamId) {
        List<Human> players;
        RoundContext context = roundContext.get();
        if (context != null && context.playersByTeam().containsKey(teamId)) {
            players = context.playersByTeam().get(teamId);
        } else {
            players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        }

        // Cache the stable squad and skills, not today's availability. Injuries
        // and bans are filtered when each matchday XI is selected.
        List<Human> squad = players.stream()
                .filter(player -> !player.isRetired())
                .toList();
        List<Long> playerIds = squad.stream().map(Human::getId).toList();
        Map<Long, PlayerSkills> skillsByPlayerId = playerSkillsRepository.findAllByPlayerIdIn(playerIds)
                .stream().collect(Collectors.toMap(PlayerSkills::getPlayerId, skills -> skills));
        Map<Long, String> lockedPositionByPlayerId = new HashMap<>();
        for (CoachPermissionService.LockedSlot lock : coachPermissionService.lockedSlots(teamId)) {
            String position = TacticService.getBasePosition(
                    tacticService.getPositionFromIndex(lock.positionIndex()));
            lockedPositionByPlayerId.putIfAbsent(lock.playerId(), position);
        }
        return new FormationEvaluationSquad(
                teamId, squad, skillsByPlayerId, lockedPositionByPlayerId);
    }

    private double formationAptness(Human player) {
        return player.getRating() * playerValueService.fitnessFactor(player.getFitness());
    }

    private record FormationEvaluationSquad(
            long teamId,
            List<Human> players,
            Map<Long, PlayerSkills> skillsByPlayerId,
            Map<Long, String> lockedPositionByPlayerId) {}

    private record FormationEvaluationStarter(Human player, String usedPosition) {}

    /** Team-level team-talk multiplier (centered on 1.0): a better man-manager rallies the squad
     *  to extract a little more team power. Quality is read from the manager's reputation and
     *  mapped via {@code MatchEngineConfig.TeamTalk}. Deterministic; neutral 1.0 when the team has
     *  no manager or team talk is disabled. */
    private double teamTalkFactor(long teamId) {
        RoundContext context = roundContext.get();
        List<Human> managers = context != null
                ? context.managersByTeam().getOrDefault(teamId, List.of())
                : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        double reputation = managers
                .stream()
                .filter(m -> !m.isRetired())
                .findFirst()
                .map(m -> (double) m.getManagerReputation())
                .orElse(engineConfig.getTeamTalk().getNeutralReputation());
        return engineConfig.getTeamTalk().multiplier(reputation);
    }

    /**
     * Optimized scorer tracking for AI vs AI matches.
     * Uses cached bestEleven/substitutions from getSimpleTeamRating() (0 extra DB queries for lineup).
     * Uses batch findAllByPlayerIdIn for leaderboard (1 query instead of N).
     * Creates Scorer entries for ALL players who played (appearances).
     * Weighted goal distribution by position. Batch saves.
     */
    private List<Scorer> getScorersForTeamSimplified(long teamId1, long teamId2,
                                                      int teamScore, int opponentScore,
                                                      long competitionId, int roundNumber) {

        // 1. Reuse cached best 11 + substitutions (already loaded by getSimpleTeamRating)
        List<PlayerView> playerViews = bestElevenCache.getOrDefault(teamId1, List.of());
        List<PlayerView> substitutionViews = substitutionsCache.getOrDefault(teamId1, List.of());

        if (playerViews.isEmpty()) return List.of();

        String currentSeason = getCurrentSeason();
        // Use per-round caches when this is the simulateRound's competition (the common case).
        // Falls through to repository lookups only for ad-hoc callers.
        long competitionTypeId;
        String competitionName;
        RoundContext context = roundContext.get();
        if (context != null && context.competition() != null
                && context.competition().getId() == competitionId) {
            competitionTypeId = context.competitionTypeId();
            competitionName = context.competitionName();
        } else {
            Long competitionTypeIdObj = competitionRepository.findTypeIdById(competitionId);
            competitionTypeId = competitionTypeIdObj != null ? competitionTypeIdObj : 0L;
            competitionName = competitionRepository.findNameById(competitionId);
        }
        String teamName = roundTeamName(teamId1);
        String opponentName = roundTeamName(teamId2);

        List<Scorer> possibleScorers = new ArrayList<>();

        for (PlayerView pv : playerViews) {
            Scorer scorer = new Scorer();
            scorer.setPlayerId(pv.getId());
            scorer.setSeasonNumber(Integer.parseInt(currentSeason));
            scorer.setRoundNumber(roundNumber);
            scorer.setTeamId(teamId1);
            scorer.setOpponentTeamId(teamId2);
            scorer.setPosition(pv.getPosition());
            scorer.setTeamScore(teamScore);
            scorer.setOpponentScore(opponentScore);
            scorer.setCompetitionId(competitionId);
            scorer.setCompetitionTypeId((int) competitionTypeId);
            scorer.setTeamName(teamName);
            scorer.setOpponentTeamName(opponentName);
            scorer.setCompetitionName(competitionName);
            // Base rating drives the weighted goal distribution below; assignMatchRatings() overwrites the displayed match rating afterwards.
            scorer.setRating(pv.getRating());
            scorer.setSubstitute(false);
            possibleScorers.add(scorer);
        }

        List<Scorer> substitutions = new ArrayList<>();
        for (PlayerView pv : substitutionViews) {
            Scorer scorer = new Scorer();
            scorer.setPlayerId(pv.getId());
            scorer.setSeasonNumber(Integer.parseInt(currentSeason));
            scorer.setRoundNumber(roundNumber);
            scorer.setTeamId(teamId1);
            scorer.setOpponentTeamId(teamId2);
            scorer.setPosition(pv.getPosition());
            scorer.setTeamScore(teamScore);
            scorer.setOpponentScore(opponentScore);
            scorer.setCompetitionId(competitionId);
            scorer.setCompetitionTypeId((int) competitionTypeId);
            scorer.setTeamName(teamName);
            scorer.setOpponentTeamName(opponentName);
            scorer.setCompetitionName(competitionName);
            // Base rating drives the weighted goal distribution below; assignMatchRatings() overwrites the displayed match rating afterwards.
            scorer.setRating(pv.getRating());
            scorer.setSubstitute(true);
            substitutions.add(scorer);
        }

        // 2. Simulate substitutions (0..max-1 subs enter the match)
        Random random = threadRandom.get();
        int subMaxExcl = engineConfig.getEvents().getSubInsertionsExclusiveMax();
        int substitutesDone = random.nextInt(0, Math.min(subMaxExcl, substitutions.size() + 1));
        if (!substitutions.isEmpty()) {
            Collections.shuffle(substitutions, random);
            for (int i = 0; i < Math.min(substitutesDone, substitutions.size()); i++) {
                possibleScorers.add(substitutions.get(i));
            }
        }

        // 3. Weighted goal distribution by position. Adds a per-match "form of the day"
        // bonus to one randomly-picked outfield player so hat-tricks happen at the
        // realistic frequency you'd expect from in-form strikers, not by pure RNG.
        List<Pair<Scorer, Double>> weightedPlayers = new ArrayList<>();
        for (Scorer scorer : possibleScorers) {
            if ("GK".equals(scorer.getPosition())) continue;
            double weight = competitionService.getDifferentValueForScoringBasedOnPosition(scorer);
            if (weight <= 0) weight = 0.1;
            weightedPlayers.add(new Pair<>(scorer, weight));
        }

        // Pick a "form of the day" player (weighted by base weight) and triple their
        // weight for this match only — recreates an on-fire striker / midfielder.
        if (weightedPlayers.size() >= 2) {
            try {
                EnumeratedDistribution<Scorer> pickHot = new EnumeratedDistribution<>(weightedPlayers);
                Scorer hotPlayer = pickHot.sample();
                for (int i = 0; i < weightedPlayers.size(); i++) {
                    if (weightedPlayers.get(i).getKey() == hotPlayer) {
                        weightedPlayers.set(i, new Pair<>(hotPlayer, weightedPlayers.get(i).getValue() * 3.0));
                        break;
                    }
                }
            } catch (Exception ignored) { /* no hot-player bonus if sampling fails */ }
        }

        if (!weightedPlayers.isEmpty()) {
            // Build the distribution ONCE per match (was per goal — wasteful).
            EnumeratedDistribution<Scorer> distribution;
            try {
                distribution = new EnumeratedDistribution<>(weightedPlayers);
            } catch (Exception e) {
                System.err.println("Distribution error: " + e.getMessage());
                distribution = null;
            }
            if (distribution != null) {
                for (int i = 0; i < teamScore; i++) {
                    Scorer selected = distribution.sample();
                    selected.setGoals(selected.getGoals() + 1);
                    if (random.nextDouble() < engineConfig.getEvents().getAssistProbability()) {
                        List<Pair<Scorer, Double>> assistCandidates = possibleScorers.stream()
                                .filter(player -> player.getPlayerId() != selected.getPlayerId())
                                .filter(player -> !"GK".equals(player.getPosition()))
                                .map(player -> new Pair<>(player, simplifiedAssistWeight(player)))
                                .filter(pair -> pair.getValue() > 0)
                                .toList();
                        if (!assistCandidates.isEmpty()) {
                            try {
                                Scorer assister =
                                        new EnumeratedDistribution<>(assistCandidates).sample();
                                assister.setAssists(assister.getAssists() + 1);
                            } catch (Exception ignored) {
                                // A malformed position weight must not abort the match.
                            }
                        }
                    }
                }
            }
        }

        // 4. Assign match performance ratings (1-10 scale)
        matchSimulationService.assignMatchRatings(possibleScorers, teamScore, opponentScore);

        // 5. Leaderboard rollup (round-cached, with an ad-hoc fallback). Shared with the
        // canonical projection so both write leaderboard stats identically.
        updateScorerLeaderboards(possibleScorers, competitionId);

        // Scorers, lineups and analytics are flushed once at the end of the competition round.
        // Leaderboard rows are managed entities and are persisted by JPA dirty checking.
        return possibleScorers;
    }

    /**
     * Per-player leaderboard rollup for a batch of {@link Scorer} rows. Uses the round-cached
     * {@code leaderboardByPlayer} map when inside {@code simulateRound}, else an ad-hoc batch
     * load. Extracted verbatim from the simplified path so the canonical AI projection and the
     * legacy path update leaderboards identically.
     */
    private void updateScorerLeaderboards(List<Scorer> scorers, long competitionId) {
        if (scorers.isEmpty()) return;
        RoundContext context = roundContext.get();
        Map<Long, ScorerLeaderboardEntry> leaderboardMap;
        if (context != null) {
            leaderboardMap = context.leaderboardByPlayer();
        } else {
            List<Long> playerIds = scorers.stream().map(Scorer::getPlayerId).toList();
            leaderboardMap = scorerLeaderboardRepository.findAllByPlayerIdIn(playerIds)
                    .stream()
                    .collect(Collectors.toMap(
                            ScorerLeaderboardEntry::getPlayerId, entry -> entry,
                            (left, right) -> left));
        }

        Set<Long> cachedLeagueCompIds = gameStateService.getLeagueCompetitionIdsCached();
        Set<Long> cachedCupCompIds = gameStateService.getCupCompetitionIdsCached();
        Set<Long> cachedSecondLeagueCompIds = gameStateService.getSecondLeagueCompetitionIdsCached();

        for (Scorer scorer : scorers) {
            ScorerLeaderboardEntry lb = leaderboardMap.get(scorer.getPlayerId());
            if (lb != null) {
                lb.setMatches(lb.getMatches() + 1);
                lb.setGoals(lb.getGoals() + scorer.getGoals());
                lb.setCurrentSeasonGoals(lb.getCurrentSeasonGoals() + scorer.getGoals());
                lb.setCurrentSeasonGames(lb.getCurrentSeasonGames() + 1);

                if (cachedLeagueCompIds.contains(competitionId)) {
                    lb.setLeagueGoals(lb.getLeagueGoals() + scorer.getGoals());
                    lb.setLeagueMatches(lb.getLeagueMatches() + 1);
                    lb.setCurrentSeasonLeagueGoals(lb.getCurrentSeasonLeagueGoals() + scorer.getGoals());
                    lb.setCurrentSeasonLeagueGames(lb.getCurrentSeasonLeagueGames() + 1);
                } else if (cachedCupCompIds.contains(competitionId)) {
                    lb.setCupGoals(lb.getCupGoals() + scorer.getGoals());
                    lb.setCupMatches(lb.getCupMatches() + 1);
                    lb.setCurrentSeasonCupGoals(lb.getCurrentSeasonCupGoals() + scorer.getGoals());
                    lb.setCurrentSeasonCupGames(lb.getCurrentSeasonCupGames() + 1);
                } else if (cachedSecondLeagueCompIds.contains(competitionId)) {
                    lb.setSecondLeagueGoals(lb.getSecondLeagueGoals() + scorer.getGoals());
                    lb.setSecondLeagueMatches(lb.getSecondLeagueMatches() + 1);
                    lb.setCurrentSeasonSecondLeagueGoals(lb.getCurrentSeasonSecondLeagueGoals() + scorer.getGoals());
                    lb.setCurrentSeasonSecondLeagueGames(lb.getCurrentSeasonSecondLeagueGames() + 1);
                }
            }
        }
    }

    private double simplifiedAssistWeight(Scorer scorer) {
        Map<String, Double> positionWeights = Map.of(
                "DL", 0.8,
                "DR", 0.8,
                "DC", 0.4,
                "ML", 2.5,
                "MR", 2.5,
                "MC", 2.0,
                "ST", 1.2);
        double weight = positionWeights.getOrDefault(scorer.getPosition(), 1.0)
                * Math.max(scorer.getRating(), 1.0);
        return scorer.isSubstitute() ? weight / 2.0 : weight;
    }

    // ============================================================
    //  Canonical AI fast-path (flag ON): plan → InstantMatchExecutor
    //  → ContributionResolver → MatchEvent → projected Scorer.
    // ============================================================

    /**
     * Build the canonical {@link Lineup} for an AI team ENTIRELY from the warm per-round caches
     * ({@code starterSlotsCache}, {@code substitutionsCache}, and the squad skills cached in
     * {@code formationSquadCache}). Issues ZERO queries — the whole point of the fast-path is to
     * keep season fast-forward query-free. Starters carry their FIELDED position (so the resolver
     * weights scorer/assist by the tactical role), bench keep their natural position. Deterministic
     * pre-planned substitutions are added via {@link LineupAdapter#buildFromSnapshot}, so a subbed-off
     * player can no longer score after his exit minute and subs get canonical appearance rows.
     */
    private Lineup buildAiCanonicalLineup(long teamId, long planSeed) {
        List<TacticController.StarterSlot> starters = starterSlotsCache.getOrDefault(teamId, List.of());
        List<PlayerView> bench = substitutionsCache.getOrDefault(teamId, List.of());
        FormationEvaluationSquad squad = formationSquadCache.get(teamId);
        Map<Long, PlayerSkills> skillsById = squad != null ? squad.skillsByPlayerId() : Map.of();

        RoundContext context = roundContext.get();
        PersonalizedTactic pt = context != null ? context.tacticsByTeam().get(teamId) : null;
        Long penaltyTakerId = pt != null ? pt.getPenaltyTakerId() : null;
        Long freeKickTakerId = pt != null ? pt.getFreeKickTakerId() : null;

        List<Contributor> xi = new ArrayList<>();
        for (TacticController.StarterSlot slot : starters) {
            xi.add(toCanonicalContributor(slot.player(), slot.usedPosition(),
                    skillsById, penaltyTakerId, freeKickTakerId));
        }
        List<Contributor> benchContributors = new ArrayList<>();
        for (PlayerView pv : bench) {
            benchContributors.add(toCanonicalContributor(pv, pv.getPosition(),
                    skillsById, penaltyTakerId, freeKickTakerId));
        }
        // planSeed*31+teamId mirrors LineupAdapter.buildAutomatic's AI_INSTANT sub-seed derivation.
        return lineupAdapter.buildFromSnapshot(xi, benchContributors, planSeed * 31 + teamId, true);
    }

    private Contributor toCanonicalContributor(PlayerView pv, String position,
            Map<Long, PlayerSkills> skillsById, Long penaltyTakerId, Long freeKickTakerId) {
        PlayerSkills s = skillsById.get(pv.getId());
        return new Contributor(pv.getId(), pv.getName(), position, pv.getRating(),
                s != null ? s.getFinishing() : 0, s != null ? s.getPassing() : 0,
                s != null ? s.getVision() : 0, pv.getFitness(),
                penaltyTakerId != null && penaltyTakerId == pv.getId(),
                freeKickTakerId != null && freeKickTakerId == pv.getId());
    }

    /**
     * Canonical twin of {@link #getScorersForTeamSimplified}: builds a {@link Scorer} row for every
     * player who took the field (the canonical XI plus the substitutes who actually came on, per the
     * lineup's timeline), then credits goals/assists STRICTLY from the canonical MatchEvent tally
     * (never RNG). No separate distribution for scorer, assist, or participation. Match ratings and
     * the leaderboard rollup are applied exactly as the simplified path does, so only the source of
     * goals/assists differs. Returns the rows for the round-level batch save.
     */
    private List<Scorer> getScorersForTeamCanonical(long teamId, long opponentTeamId,
            int teamScore, int opponentScore, long competitionId, int roundNumber,
            Lineup lineup, Map<Long, int[]> tally) {

        List<TacticController.StarterSlot> starterSlots = starterSlotsCache.getOrDefault(teamId, List.of());
        List<PlayerView> starterViews = bestElevenCache.getOrDefault(teamId, List.of());
        List<PlayerView> benchViews = substitutionsCache.getOrDefault(teamId, List.of());
        if (starterSlots.isEmpty() && starterViews.isEmpty()) return List.of();

        String currentSeason = getCurrentSeason();
        long competitionTypeId;
        String competitionName;
        RoundContext context = roundContext.get();
        if (context != null && context.competition() != null
                && context.competition().getId() == competitionId) {
            competitionTypeId = context.competitionTypeId();
            competitionName = context.competitionName();
        } else {
            Long competitionTypeIdObj = competitionRepository.findTypeIdById(competitionId);
            competitionTypeId = competitionTypeIdObj != null ? competitionTypeIdObj : 0L;
            competitionName = competitionRepository.findNameById(competitionId);
        }
        String teamName = roundTeamName(teamId);
        String opponentName = roundTeamName(opponentTeamId);

        Map<Long, PlayerView> viewById = new HashMap<>();
        for (PlayerView pv : starterViews) viewById.put(pv.getId(), pv);
        for (PlayerView pv : benchViews) viewById.put(pv.getId(), pv);

        List<Scorer> scorers = new ArrayList<>();
        Map<Long, Scorer> byPlayer = new HashMap<>();

        // Starters (took the field at kickoff). Position shown is the player's natural
        // position, matching the simplified path and the historical lineup snapshot.
        for (TacticController.StarterSlot slot : starterSlots) {
            PlayerView pv = slot.player();
            Scorer s = newCanonicalScorer(pv.getId(), pv.getPosition(), pv.getRating(),
                    teamId, opponentTeamId, teamScore, opponentScore, competitionId, competitionTypeId,
                    teamName, opponentName, competitionName, currentSeason, roundNumber, false);
            scorers.add(s);
            byPlayer.put(pv.getId(), s);
        }
        // Substitutes who actually came on, per the canonical timeline (appearance rows).
        for (Lineup.SubMove sub : lineup.getSubs()) {
            long onId = sub.on().playerId();
            if (byPlayer.containsKey(onId)) continue;
            PlayerView pv = viewById.get(onId);
            String position = pv != null ? pv.getPosition() : sub.on().position();
            double rating = pv != null ? pv.getRating() : sub.on().rating();
            Scorer s = newCanonicalScorer(onId, position, rating,
                    teamId, opponentTeamId, teamScore, opponentScore, competitionId, competitionTypeId,
                    teamName, opponentName, competitionName, currentSeason, roundNumber, true);
            scorers.add(s);
            byPlayer.put(onId, s);
        }

        // Goals + assists: canonical MatchEvent tally only. Every tally player was on the pitch at
        // its goal minute (the resolver only picks from onPitchAt), so it has a Scorer row above.
        for (Map.Entry<Long, int[]> entry : tally.entrySet()) {
            Scorer s = byPlayer.get(entry.getKey());
            if (s == null) continue; // defensive; canonically unreachable
            s.setGoals(s.getGoals() + entry.getValue()[0]);
            s.setAssists(s.getAssists() + entry.getValue()[1]);
        }

        matchSimulationService.assignMatchRatings(scorers, teamScore, opponentScore);
        updateScorerLeaderboards(scorers, competitionId);
        return scorers;
    }

    private Scorer newCanonicalScorer(long playerId, String position, double rating,
            long teamId, long opponentTeamId, int teamScore, int opponentScore,
            long competitionId, long competitionTypeId, String teamName, String opponentName,
            String competitionName, String season, int roundNumber, boolean substitute) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setSeasonNumber(Integer.parseInt(season));
        scorer.setRoundNumber(roundNumber);
        scorer.setTeamId(teamId);
        scorer.setOpponentTeamId(opponentTeamId);
        scorer.setPosition(position);
        scorer.setTeamScore(teamScore);
        scorer.setOpponentScore(opponentScore);
        scorer.setCompetitionId(competitionId);
        scorer.setCompetitionTypeId((int) competitionTypeId);
        scorer.setTeamName(teamName);
        scorer.setOpponentTeamName(opponentName);
        scorer.setCompetitionName(competitionName);
        scorer.setRating(rating);
        scorer.setSubstitute(substitute);
        return scorer;
    }

    // ============================================================
    //  Per-round cache accessors (public so commit-flow callers
    //  in MatchdayCoordinator + LineupRatingService can reuse them)
    // ============================================================

    public List<Human> roundPlayers(long teamId) {
        RoundContext context = roundContext.get();
        if (context != null) {
            return context.playersByTeam().getOrDefault(teamId, List.of());
        }
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
    }

    public Set<Long> roundInjuredIds(long teamId) {
        if (availabilityDisabled()) return Set.of();
        RoundContext context = roundContext.get();
        if (context != null) {
            return context.injuredIdsByTeam().getOrDefault(teamId, Set.of());
        }
        return injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream().map(Injury::getPlayerId).collect(Collectors.toSet());
    }

    /** Players unavailable for the current competition: injuries plus active bans. */
    public Set<Long> roundUnavailableIds(long teamId) {
        if (availabilityDisabled()) return Set.of();
        Set<Long> unavailable = new HashSet<>(roundInjuredIds(teamId));
        RoundContext context = roundContext.get();
        if (context != null) {
            unavailable.addAll(context.suspendedIdsByTeam().getOrDefault(teamId, Set.of()));
        } else {
            unavailable.addAll(suspensionRepository.findAllByTeamIdAndActive(teamId, true)
                    .stream().map(com.footballmanagergamesimulator.model.Suspension::getPlayerId).toList());
        }
        return Set.copyOf(unavailable);
    }

    private boolean availabilityDisabled() {
        return gameplayFeatures != null && gameplayFeatures.isPlayerAvailabilityDisabled();
    }

    public String roundTeamName(long teamId) {
        RoundContext context = roundContext.get();
        if (context != null) {
            Team t = context.teamsById().get(teamId);
            if (t != null) return t.getName();
        }
        return teamRepository.findNameById(teamId);
    }

    public Team roundTeam(long teamId) {
        RoundContext context = roundContext.get();
        if (context != null) {
            Team t = context.teamsById().get(teamId);
            if (t != null) return t;
        }
        return teamRepository.findById(teamId).orElse(null);
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private String getCurrentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(String::valueOf).orElse("1");
    }
}
