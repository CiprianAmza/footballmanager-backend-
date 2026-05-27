package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
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
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
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
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Round-level match simulation orchestration extracted from
 * {@link CompetitionController}. Owns {@code simulateRound} plus its
 * batched AI helpers and the per-round caches that they share.
 *
 * <p>The controller keeps the REST mapping and delegates to {@link #simulateRound}.
 * Cross-cutting helpers that the orchestrator still needs (full human-match
 * scorers, match events, match report, manager reputation, coefficient
 * award, best-eleven rating, tactical adjustments) are invoked back through
 * a {@link Lazy @Lazy} controller reference so we avoid moving those whole
 * subsystems at once.
 */
@Service
public class MatchSimulationOrchestrator {

    @Autowired private HumanRepository humanRepository;
    @Autowired private InjuryRepository injuryRepository;
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
    @Autowired private CompetitionService competitionService;
    @Autowired private TeamPostMatchService teamPostMatchService;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private CupBracketService cupBracketService;
    @Autowired private TacticController tacticController;
    @Autowired private UserContext userContext;

    @Autowired @Lazy private CompetitionController controllerRef;

    // ===== AI rating caches (lifetime: app, mirrors original controller behavior). =====
    private final Map<Long, Double> simpleRatingCache = new HashMap<>();
    private final Map<Long, String> managerTacticCache = new HashMap<>();
    private final Map<Long, List<PlayerView>> bestElevenCache = new HashMap<>();
    private final Map<Long, List<PlayerView>> substitutionsCache = new HashMap<>();

    // ===== Per-round caches (populated at start of simulateRound, cleared at end). =====
    // simulateRound runs sequentially (one competition's round at a time from the
    // single-threaded GameAdvanceService loop), so instance fields are safe.
    private Map<Long, List<Human>> roundPlayersByTeam;
    private Map<Long, Set<Long>> roundInjuredIdsByTeam;
    private Map<Long, Team> roundTeamsById;
    private Competition roundCompetition;
    private String roundCompetitionName;
    private long roundCompetitionTypeId;

    // ============================================================
    //  Public entry point
    // ============================================================

    @Transactional
    public void simulateRound(String competitionId, String roundId) {

        long _t0 = System.nanoTime();

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long nextRound = _roundId + 1;

        Random random = new Random();
        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(_competitionId, _roundId, getCurrentSeason());

        boolean knockout = europeanCompetitionService.isKnockoutRound(_competitionId, _roundId);

        // Batch collections for AI matches - saved once at end of round
        List<Injury> batchedInjuries = new ArrayList<>();
        List<Human> batchedInjuredPlayers = new ArrayList<>();
        List<Human> batchedManagers = new ArrayList<>();

        // Pre-cache competition type IDs (avoids repeated findAll per match)
        Set<Long> cachedLeagueCompIds = controllerRef.getLeagueCompetitionIdsCached();
        Set<Long> cachedCupCompIds = controllerRef.getCupCompetitionIdsCached();
        // cached second-league set kept warm even if unused below — keeps controller's
        // legacy lazy-initialization sequence intact.
        controllerRef.getSecondLeagueCompetitionIdsCached();

        // ===== Per-round pre-load: one IN-query per entity type instead of
        // re-querying inside every helper for every match. =====
        Set<Long> participatingTeamIds = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matches) {
            participatingTeamIds.add(m.getTeam1Id());
            participatingTeamIds.add(m.getTeam2Id());
        }

        if (!participatingTeamIds.isEmpty()) {
            roundPlayersByTeam = humanRepository
                    .findAllByTeamIdInAndTypeId(participatingTeamIds, TypeNames.PLAYER_TYPE)
                    .stream()
                    .collect(Collectors.groupingBy(Human::getTeamId));
            roundInjuredIdsByTeam = injuryRepository
                    .findAllByTeamIdInAndDaysRemainingGreaterThan(participatingTeamIds, 0)
                    .stream()
                    .collect(Collectors.groupingBy(
                            Injury::getTeamId,
                            Collectors.mapping(Injury::getPlayerId, Collectors.toSet())));
            roundTeamsById = teamRepository.findAllById(participatingTeamIds).stream()
                    .collect(Collectors.toMap(Team::getId, t -> t));
        } else {
            roundPlayersByTeam = Map.of();
            roundInjuredIdsByTeam = Map.of();
            roundTeamsById = Map.of();
        }

        roundCompetition = competitionRepository.findById(_competitionId).orElse(null);
        roundCompetitionName = roundCompetition != null ? roundCompetition.getName() : "";
        roundCompetitionTypeId = roundCompetition != null ? roundCompetition.getTypeId() : 0L;

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

            if (isHumanMatch) {
                humanMatches++;
                long _tsHuman = System.nanoTime();
                // --- FULL SIMULATION for human team matches ---
                // Defensive lookup: a team could temporarily be without a manager
                // (e.g. moments after a resign / job-offer transfer if some path
                // forgot to spawn a replacement). Default to "442" rather than
                // crashing the whole round on .get(0).
                String tactic1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE)
                        .stream().findFirst().map(Human::getTacticStyle).orElse("442");
                String tactic2 = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE)
                        .stream().findFirst().map(Human::getTacticStyle).orElse("442");

                teamPower1 = controllerRef.getBestElevenRatingByTactic(teamId1, tactic1);
                teamPower2 = controllerRef.getBestElevenRatingByTactic(teamId2, tactic2);

                Optional<PersonalizedTactic> personalizedTactic1 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
                Optional<PersonalizedTactic> personalizedTactic2 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

                if (personalizedTactic1.isPresent())
                    teamPower1 = controllerRef.adjustTeamPowerByTacticalProperties(teamPower1, teamPower2, personalizedTactic1.get());
                if (personalizedTactic2.isPresent())
                    teamPower2 = controllerRef.adjustTeamPowerByTacticalProperties(teamPower2, teamPower1, personalizedTactic2.get());

                // Admin override — if a score has been forced for this match, skip the
                // live engine entirely and use the instant path with the forced score.
                int[] adminScore = teamPostMatchService.consumePredeterminedScore(_competitionId, (int) _roundId, teamId1, teamId2);

                // Check if any human manager has viewFullMatch enabled
                boolean useFullMatchEngine = false;
                long humanTeamIdForMatch = userContext.isHumanTeam(teamId1) ? teamId1 : teamId2;
                Human humanManager = humanRepository.findAllByTeamIdAndTypeId(humanTeamIdForMatch, TypeNames.MANAGER_TYPE)
                        .stream().findFirst().orElse(null);
                if (humanManager != null && humanManager.isViewFullMatch() && adminScore == null) {
                    useFullMatchEngine = true;
                }

                boolean interactiveMatch = false;

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
                    liveMatchSimulationService.createInteractiveSession(
                            teamId1, teamId2, teamPower1, teamPower2,
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                            generateGoalAnims);
                    // Placeholder score — /commit overwrites with the real one
                    // after the user finishes the live playback.
                    teamScore1 = 0;
                    teamScore2 = 0;

                } else {
                    // --- INSTANT SIMULATION (original behavior) ---
                    if (adminScore != null) {
                        teamScore1 = adminScore[0];
                        teamScore2 = adminScore[1];
                    } else {
                        List<Integer> scores = teamPostMatchService.calculateScores(teamPower1, teamPower2);
                        teamScore1 = scores.get(0);
                        teamScore2 = scores.get(1);
                    }

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
                    controllerRef.getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId);
                    controllerRef.getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId);

                    // Detailed match events (goals, assists, cards, substitutions)
                    controllerRef.generateMatchEvents(_competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                            teamId1, teamId2, teamScore1, teamScore2, tactic1, tactic2);

                    // Generate and persist match stats
                    matchSimulationService.generateAndSaveMatchStats(
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId,
                            teamId1, teamId2, teamScore1, teamScore2, teamPower1, teamPower2,
                            personalizedTactic1.orElse(null), personalizedTactic2.orElse(null));
                }

                // Full post-match processing for human matches (same for both
                // non-interactive paths). Interactive matches defer ALL of this
                // to /commit so the user's manual subs change the real result.
                if (!interactiveMatch) {
                    controllerRef.processInjuriesForTeam(teamId1);
                    controllerRef.processInjuriesForTeam(teamId2);

                    teamPostMatchService.updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, teamId2);
                    teamPostMatchService.updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, teamId1);

                    europeanCompetitionService.awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);

                    controllerRef.generateMatchReport(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                    controllerRef.updateManagerReputationAfterMatch(teamId1, teamId2, teamScore1, teamScore2);
                } else {
                    // Stash team powers + tactics on the session so /commit can
                    // run the same post-match work without re-deriving them.
                    String liveKey = LiveMatchSimulationService.buildKey(
                            _competitionId, Integer.parseInt(getCurrentSeason()), (int) _roundId, teamId1, teamId2);
                    LiveMatchSession s = liveMatchSimulationService.getSession(liveKey);
                    if (s != null) {
                        s.setDeferredContext(teamPower1, teamPower2, tactic1, tactic2,
                                personalizedTactic1.orElse(null), personalizedTactic2.orElse(null),
                                knockout);
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
                } else {
                    List<Integer> scores = teamPostMatchService.calculateScores(teamPower1, teamPower2);
                    teamScore1 = scores.get(0);
                    teamScore2 = scores.get(1);
                }

                if (knockout && teamScore1 == teamScore2) {
                    double total = teamPower1 + teamPower2;
                    double winChance = total > 0 ? (teamPower1 / total) * 0.3 + 0.35 : 0.5;
                    if (random.nextDouble() < winChance) {
                        teamScore1++;
                    } else {
                        teamScore2++;
                    }
                }

                _ts = System.nanoTime();
                getScorersForTeamSimplified(teamId1, teamId2, teamScore1, teamScore2, _competitionId);
                getScorersForTeamSimplified(teamId2, teamId1, teamScore2, teamScore1, _competitionId);
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
                europeanCompetitionService.awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
                tCoeff += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                batchUpdateManagerReputation(teamId1, teamId2, teamScore1, teamScore2, batchedManagers);
                tMgrRep += System.nanoTime() - _ts;

                _ts = System.nanoTime();
                matchSimulationService.generateAndSaveMatchStats(
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

            // Knockout progression (needed for both human and AI matches)
            if (knockout && !_isInteractivePending) {
                long winnerId = teamScore1 > teamScore2 ? teamId1 : teamId2;

                // National cup: propagate the winner into the pre-created bracket slot.
                // For LoC/Stars Cup we keep the legacy CompetitionTeamInfo flow below.
                if (cachedCupCompIds != null && cachedCupCompIds.contains(_competitionId)
                        && match.getMatchIndex() > 0) {
                    cupBracketService.propagateWinner(
                            _competitionId, Integer.parseInt(getCurrentSeason()),
                            _roundId, match.getMatchIndex(), winnerId);
                } else {
                    CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                    competitionTeamInfo.setCompetitionId(_competitionId);
                    competitionTeamInfo.setRound(nextRound);
                    competitionTeamInfo.setTeamId(winnerId);
                    competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                    competitionTeamInfoRepository.save(competitionTeamInfo);
                }
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
                competitionTeamInfoDetail.setScore(teamScore1 + " - " + teamScore2);
                competitionTeamInfoDetail.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                competitionTeamInfoDetailRepository.save(competitionTeamInfoDetail);
                tDetail += System.nanoTime() - _tsDetail;
            }

            // Match day income for home team (team1) — competition cached for the round
            long _tsFin = System.nanoTime();
            String compName = roundCompetitionName != null && !roundCompetitionName.isEmpty()
                    ? roundCompetitionName : "Match";
            financeService.processMatchDayIncome(teamId1, Integer.parseInt(getCurrentSeason()),
                    match.getDay(), teamId2, compName);
            tFinance += System.nanoTime() - _tsFin;
        }

        long _tLoopDone = System.nanoTime();

        // Batch save all collected AI match data at once
        if (!batchedInjuries.isEmpty()) injuryRepository.saveAll(batchedInjuries);
        if (!batchedInjuredPlayers.isEmpty()) humanRepository.saveAll(batchedInjuredPlayers);
        if (!batchedManagers.isEmpty()) humanRepository.saveAll(batchedManagers);

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
            // Clear per-round caches so they don't leak across simulateRound calls.
            roundPlayersByTeam = null;
            roundInjuredIdsByTeam = null;
            roundTeamsById = null;
            roundCompetition = null;
            roundCompetitionName = null;
            roundCompetitionTypeId = 0L;
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
    void updateTeamWithSimpleMorale(long teamId, long competitionId, int scoreHome, int scoreAway,
                                    double teamPowerDifference, Random random) {
        // 1. Update standings (same as updateTeamSimple)
        TeamCompetitionDetail team = teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(teamId, competitionId);
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
        teamCompetitionDetailRepository.save(team);

        // 2. Morale with bench tracking for AI teams — use cached players from start of round
        double baseMoraleChange = teamPostMatchService.calculateMoraleChangeForTeamDifference(result, teamPowerDifference);
        List<Human> allPlayers = roundPlayers(teamId);

        // Determine best 11 from cache to know who played vs who was benched
        Set<Long> playedIds = new HashSet<>();
        List<PlayerView> cachedBestXI = bestElevenCache.getOrDefault(teamId, List.of());
        for (PlayerView pv : cachedBestXI) {
            playedIds.add(pv.getId());
        }

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            double moraleChange;

            if (playedIds.contains(player.getId())) {
                // Player participated
                moraleChange = baseMoraleChange + random.nextDouble(-1, 1);
                player.setSeasonMatchesPlayed(player.getSeasonMatchesPlayed() + 1);
                player.setConsecutiveBenched(0);

                // AI player calms down if getting regular game time
                if (player.isWantsTransfer() && player.getSeasonMatchesPlayed() > 5) {
                    if (random.nextDouble() < 0.3) {
                        player.setWantsTransfer(false);
                    }
                }
            } else {
                // Benched
                player.setConsecutiveBenched(player.getConsecutiveBenched() + 1);
                switch (result) {
                    case "W": moraleChange = -2; break;
                    case "D": moraleChange = -4; break;
                    case "L": moraleChange = -3; break;
                    default: moraleChange = 0;
                }
                int benched = player.getConsecutiveBenched();
                if (benched >= 5) moraleChange -= 2;
                // AI players also request transfers when benched too long
                if (benched >= 3 && player.getRating() > 50 && !player.isWantsTransfer()) {
                    double demandChance = (benched >= 7) ? 0.5 : (benched >= 5) ? 0.3 : 0.1;
                    if (random.nextDouble() < demandChance) {
                        player.setWantsTransfer(true);
                    }
                }
            }

            player.setMorale(Math.min(100D, Math.max(0D, player.getMorale() + moraleChange)));
        }
        humanRepository.saveAll(allPlayers);
    }

    /**
     * Batched injury processing for AI teams.
     * Same logic as processInjuriesForTeam but collects injuries into lists
     * for a single batch save at the end of the round.
     */
    void processInjuriesForTeamBatched(long teamId, Random random,
                                       List<Injury> batchedInjuries, List<Human> batchedInjuredPlayers) {
        // Use per-round caches when available (populated at start of simulateRound)
        List<Human> players = roundPlayers(teamId);
        Set<Long> injuredPlayerIds = roundInjuredIds(teamId);

        String[] injuryTypes = {"Hamstring Strain", "Knee Ligament", "Ankle Sprain", "Muscle Fatigue", "Broken Bone", "Concussion"};

        for (Human player : players) {
            if (player.isRetired()) continue;
            if (injuredPlayerIds.contains(player.getId())) continue;

            double injuryChance = 0.002;
            if (player.getFitness() < 50) injuryChance += 0.001;

            if (random.nextDouble() < injuryChance) {
                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(teamId);
                injury.setSeasonNumber(Integer.parseInt(getCurrentSeason()));

                String injuryType = injuryTypes[random.nextInt(injuryTypes.length)];
                injury.setInjuryType(injuryType);

                double severityRoll = random.nextDouble();
                if (severityRoll < 0.55) {
                    injury.setSeverity("Minor");
                    injury.setDaysRemaining(random.nextInt(1, 4));
                } else if (severityRoll < 0.85) {
                    injury.setSeverity("Moderate");
                    injury.setDaysRemaining(random.nextInt(4, 9));
                } else {
                    injury.setSeverity("Serious");
                    injury.setDaysRemaining(random.nextInt(10, 21));
                }

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
    void batchUpdateManagerReputation(long teamId1, long teamId2, int score1, int score2,
                                      List<Human> batchedManagers) {
        // Use per-round cached team entities (populated at start of simulateRound)
        Team team1 = roundTeam(teamId1);
        Team team2 = roundTeam(teamId2);
        if (team1 == null || team2 == null) return;

        List<Human> managers1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE);
        List<Human> managers2 = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE);

        if (!managers1.isEmpty()) {
            double change = controllerRef.calculateMatchRepChange(score1, score2, team1.getReputation(), team2.getReputation());
            Human mgr = managers1.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            batchedManagers.add(mgr);
        }
        if (!managers2.isEmpty()) {
            double change = controllerRef.calculateMatchRepChange(score2, score1, team2.getReputation(), team1.getReputation());
            Human mgr = managers2.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            batchedManagers.add(mgr);
        }
    }

    // ============================================================
    //  AI rating fast-path (uses persistent AI caches)
    // ============================================================

    /**
     * Fast team rating for AI teams: selects best 11 by manager's tactic, cached per round.
     * Uses the same position-based selection as the original getBestEleven but without
     * morale/fitness multipliers or JSON tactic parsing.
     */
    double getSimpleTeamRating(long teamId) {
        if (simpleRatingCache.containsKey(teamId)) return simpleRatingCache.get(teamId);

        // Get and cache manager's preferred tactic
        String tactic = getManagerTacticCached(teamId);

        // Select and cache best 11 by tactic positions
        List<PlayerView> bestEleven = tacticController.getBestEleven(String.valueOf(teamId), tactic);
        bestElevenCache.put(teamId, bestEleven);

        // Also pre-cache substitutions (will be needed by getScorersForTeamSimplified)
        List<PlayerView> subs = tacticController.getSubstitutions(String.valueOf(teamId), tactic);
        substitutionsCache.put(teamId, subs);

        double rating = bestEleven.stream()
                .mapToDouble(PlayerView::getRating)
                .sum();

        // Manager morale influence on AI team power
        rating *= teamPostMatchService.getManagerMoraleMultiplier(teamId);

        simpleRatingCache.put(teamId, rating);
        return rating;
    }

    String getManagerTacticCached(long teamId) {
        if (managerTacticCache.containsKey(teamId)) return managerTacticCache.get(teamId);
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        String tactic = (!managers.isEmpty() && managers.get(0).getTacticStyle() != null)
                ? managers.get(0).getTacticStyle() : "442";
        managerTacticCache.put(teamId, tactic);
        return tactic;
    }

    /**
     * Optimized scorer tracking for AI vs AI matches.
     * Uses cached bestEleven/substitutions from getSimpleTeamRating() (0 extra DB queries for lineup).
     * Uses batch findAllByPlayerIdIn for leaderboard (1 query instead of N).
     * Creates Scorer entries for ALL players who played (appearances).
     * Weighted goal distribution by position. Batch saves.
     */
    void getScorersForTeamSimplified(long teamId1, long teamId2, int teamScore, int opponentScore, long competitionId) {

        // 1. Reuse cached best 11 + substitutions (already loaded by getSimpleTeamRating)
        List<PlayerView> playerViews = bestElevenCache.getOrDefault(teamId1, List.of());
        List<PlayerView> substitutionViews = substitutionsCache.getOrDefault(teamId1, List.of());

        if (playerViews.isEmpty()) return;

        String currentSeason = getCurrentSeason();
        // Use per-round caches when this is the simulateRound's competition (the common case).
        // Falls through to repository lookups only for ad-hoc callers.
        long competitionTypeId;
        String competitionName;
        if (roundCompetition != null && roundCompetition.getId() == competitionId) {
            competitionTypeId = roundCompetitionTypeId;
            competitionName = roundCompetitionName;
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
            // Rating will be set by assignMatchRatings() below
            scorer.setSubstitute(false);
            possibleScorers.add(scorer);
        }

        List<Scorer> substitutions = new ArrayList<>();
        for (PlayerView pv : substitutionViews) {
            Scorer scorer = new Scorer();
            scorer.setPlayerId(pv.getId());
            scorer.setSeasonNumber(Integer.parseInt(currentSeason));
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
            // Rating will be set by assignMatchRatings() below
            scorer.setSubstitute(true);
            substitutions.add(scorer);
        }

        // 2. Simulate substitutions (0-5 subs enter the match)
        Random random = new Random();
        int substitutesDone = random.nextInt(0, Math.min(6, substitutions.size() + 1));
        if (!substitutions.isEmpty()) {
            Collections.shuffle(substitutions);
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
                }
            }
        }

        // 4. Assign match performance ratings (1-10 scale)
        matchSimulationService.assignMatchRatings(possibleScorers, teamScore, opponentScore);

        // 5. Batch leaderboard lookup (1 query instead of N individual findByPlayerId calls)
        List<Long> playerIds = possibleScorers.stream().map(Scorer::getPlayerId).toList();
        List<ScorerLeaderboardEntry> leaderboardEntries = scorerLeaderboardRepository.findAllByPlayerIdIn(playerIds);
        Map<Long, ScorerLeaderboardEntry> leaderboardMap = new HashMap<>();
        for (ScorerLeaderboardEntry lb : leaderboardEntries) {
            leaderboardMap.put(lb.getPlayerId(), lb);
        }

        Set<Long> cachedLeagueCompIds = controllerRef.getLeagueCompetitionIdsCached();
        Set<Long> cachedCupCompIds = controllerRef.getCupCompetitionIdsCached();
        Set<Long> cachedSecondLeagueCompIds = controllerRef.getSecondLeagueCompetitionIdsCached();

        List<ScorerLeaderboardEntry> leaderboardToSave = new ArrayList<>();
        for (Scorer scorer : possibleScorers) {
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
                leaderboardToSave.add(lb);
            }
        }

        scorerRepository.saveAll(possibleScorers);
        if (!leaderboardToSave.isEmpty()) scorerLeaderboardRepository.saveAll(leaderboardToSave);
    }

    // ============================================================
    //  Per-round cache accessors (public so commit-flow callers
    //  in CompetitionController can reuse roundTeamName)
    // ============================================================

    public List<Human> roundPlayers(long teamId) {
        if (roundPlayersByTeam != null) {
            return roundPlayersByTeam.getOrDefault(teamId, List.of());
        }
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
    }

    public Set<Long> roundInjuredIds(long teamId) {
        if (roundInjuredIdsByTeam != null) {
            return roundInjuredIdsByTeam.getOrDefault(teamId, Set.of());
        }
        return injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream().map(Injury::getPlayerId).collect(Collectors.toSet());
    }

    public String roundTeamName(long teamId) {
        if (roundTeamsById != null) {
            Team t = roundTeamsById.get(teamId);
            if (t != null) return t.getName();
        }
        return teamRepository.findNameById(teamId);
    }

    public Team roundTeam(long teamId) {
        if (roundTeamsById != null) {
            Team t = roundTeamsById.get(teamId);
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
