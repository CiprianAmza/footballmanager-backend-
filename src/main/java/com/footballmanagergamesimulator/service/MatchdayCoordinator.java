package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Calendar-driven matchday dispatch + result lookups + interactive-live-match
 * commit, extracted from {@link MatchSimulationOrchestrator}. Wraps
 * {@link MatchRoundSimulator#simulateRound} with the per-competition-type
 * setup (knockout draws, group draws, Stars Cup playoff drops) that the
 * round-only entry point can't perform.
 */
@Service
public class MatchdayCoordinator {

    @Autowired private HumanRepository humanRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private RoundRepository roundRepository;

    @Autowired private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired private MatchStatsService matchStatsService;
    @Autowired private TeamPostMatchService teamPostMatchService;
    @Autowired private LineupRatingService lineupRatingService;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;
    @Autowired private CompetitionDisplayService competitionDisplayService;
    @Autowired private FixtureSchedulingService fixtureSchedulingService;
    @Autowired private UserContext userContext;
    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private com.footballmanagergamesimulator.config.CompetitionFormatConfig competitionFormat;
    @Autowired private com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver tieResolver;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CupBracketService cupBracketService;
    @Autowired private GameStateService gameStateService;

    // ============================================================
    //  Matchday dispatch (calendar-driven). Mirrors {@code simulateRound}
    //  but does the per-competition-type setup (knockout draws, group draws,
    //  Stars Cup playoff drops) that the round-only entry point can't.
    //  Called from {@link com.footballmanagergamesimulator.service.GameAdvanceService}
    //  via a thin orchestrator delegate.
    // ============================================================

    /**
     * Simulate a single matchday for a competition. Handles:
     *   LoC (typeId 4): 11 matchdays → rounds 0-10 (matchday - 1 = round)
     *     matchday 1 = round 0 (preliminary), matchday 2 = round 1 (qualifying),
     *     matchdays 3-8 = rounds 2-7 (groups), matchdays 9-11 = rounds 8-10 (QF/SF/Final)
     *   Stars Cup (typeId 5): 10 matchdays → rounds 1-10 (matchday = round)
     *     matchdays 1-6 = rounds 1-6 (groups), matchday 7 = round 7 (playoff),
     *     matchdays 8-10 = rounds 8-10 (QF/SF/Final)
     *   Cup (typeId 2): matchday = round (1-based knockout)
     *   League (typeId 1) / Second League (typeId 3): matchday = round
     */
    @Transactional
    public void simulateMatchday(long competitionId, int matchday, int season) {
        simulateMatchday(competitionId, matchday, season, null);
    }

    /**
     * @param legNumber when non-null, this matchday event is one leg (1 or 2) of a
     *        two-leg round played on its own calendar day. Leg 1 simulates and
     *        defers; leg 2 aggregates with the persisted leg 1 and draws the next
     *        round. When null, the whole round is simulated in one pass.
     */
    @Transactional
    public void simulateMatchday(long competitionId, int matchday, int season, Integer legNumber) {
        long _tMatchdayStart = System.nanoTime();
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return;

        int typeId = (int) competition.getTypeId();
        String compIdStr = String.valueOf(competitionId);

        com.footballmanagergamesimulator.config.CompetitionFormat fmt = competitionFormat.get(typeId);
        int round = fmt.roundForMatchday(matchday);
        String roundStr = String.valueOf(round);

        System.out.println("=== simulateMatchday: comp=" + competitionId + " typeId=" + typeId
                + " matchday=" + matchday + " → round=" + round + " leg=" + legNumber + " season=" + season + " ===");

        try {

        // Guard: skip if this round (or this specific leg) was already simulated.
        List<CompetitionTeamInfoDetail> existing = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, round, season);
        boolean alreadySimulated = (legNumber == null)
                ? !existing.isEmpty()
                : existing.stream().anyMatch(d -> d.getLegNumber() == legNumber);
        if (alreadySimulated) {
            System.out.println("Round " + round + " leg " + legNumber + " already simulated, skipping");
            return;
        }

        // === LoC (typeId 4) ===
        if (typeId == 4) {
            if (fmt.isPreliminaryRound(round)) {
                var plan = fmt.europeanPlan();
                if (plan != null) {
                    // Configurable format: trim with byes (strongest seeds skip the round).
                    europeanCompetitionService.drawEuropeanPreliminarySeeded(competitionId, round, plan.slots());
                } else {
                    fixtureSchedulingService.getFixturesForRound(compIdStr, roundStr);
                }
                matchRoundSimulator.simulateRound(compIdStr, roundStr);
                europeanCompetitionService.assignLocLosersToStarsCup(competitionId, round);
                return;
            }
            if (fmt.isGroupRound(round)) {
                if (fmt.isGroupDrawRound(round)) {
                    europeanCompetitionService.drawEuropeanGroups(competitionId, round);
                    europeanCompetitionService.resetEuropeanStats(competitionId);
                    europeanCompetitionService.generateGroupStageFixtures(competitionId);
                    for (int md = matchday + 1; md <= matchday + fmt.groupMatchdayCount() - 1; md++) {
                        fixtureSchedulingService.assignMatchDayForNewRound(competitionId, md, season);
                    }
                }
                matchRoundSimulator.simulateRound(compIdStr, roundStr);
                if (fmt.isQualifyRound(round)) {
                    europeanCompetitionService.qualifyFromGroupStage(competitionId);
                }
                return;
            }
            // Knockout rounds (QF/SF/Final) — two-leg-aware when per-leg events are used.
            simulateKnockoutRound(compIdStr, competitionId, roundStr, round, matchday, season, fmt, legNumber);
            return;
        }

        // === Stars Cup (typeId 5) ===
        if (typeId == 5) {
            if (fmt.isGroupRound(round)) {
                if (fmt.isGroupDrawRound(round)) {
                    europeanCompetitionService.drawEuropeanGroups(competitionId, round);
                    europeanCompetitionService.resetEuropeanStats(competitionId);
                    europeanCompetitionService.generateGroupStageFixtures(competitionId);
                    for (int md = matchday + 1; md <= matchday + fmt.groupMatchdayCount() - 1; md++) {
                        fixtureSchedulingService.assignMatchDayForNewRound(competitionId, md, season);
                    }
                }
                matchRoundSimulator.simulateRound(compIdStr, roundStr);
                if (fmt.isQualifyRound(round)) {
                    europeanCompetitionService.qualifyFromStarsCupGroupStage(competitionId);
                }
                return;
            }
            // Knockout rounds (playoff, QF, SF, Final) — two-leg-aware when per-leg events are used.
            simulateKnockoutRound(compIdStr, competitionId, roundStr, round, matchday, season, fmt, legNumber);
            return;
        }

        // === League (typeId 1) / Second League (typeId 3) ===
        // Fixtures are pre-generated at season start. Re-running getFixturesForRound
        // here duplicates every round in competition_team_info_match and trips
        // unique-constraint violations on match_stats inside simulateRound.
        if (typeId == 1 || typeId == 3) {
            matchRoundSimulator.simulateRound(compIdStr, roundStr);
            return;
        }

        // === Cup (typeId 2) ===
        // Bracket is fully pre-generated at season start by CupBracketService —
        // no per-round draw, no "draw next round" step. simulateRound propagates
        // winners into the pre-created next-round slots via cupBracketService.
        matchRoundSimulator.simulateRound(compIdStr, roundStr);

        int numTeams = competitionDisplayService.getTeamCountForCompetition(competitionId);
        int maxRounds = Math.max(1, (int) Math.ceil(Math.log(numTeams) / Math.log(2)));
        if (round < maxRounds) {
            fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday + 1, season);
        }

        } finally {
            long totalMs = (System.nanoTime() - _tMatchdayStart) / 1_000_000;
            System.out.println(String.format(
                    "<<< simulateMatchday comp=%d matchday=%d DONE in %dms",
                    competitionId, matchday, totalMs));
        }
    }

    /**
     * Simulate one European knockout round (LoC/SC). For a two-leg round driven by
     * per-leg calendar events: leg 1 draws the fixtures (both legs) and plays only
     * leg 1 (no propagation); leg 2 aggregates with the persisted leg 1, decides,
     * and draws the next round. For single-leg rounds — or a two-leg round invoked
     * without a leg number (single-pass / back-compat) — the whole round is played
     * in one call and the next round is drawn.
     */
    private void simulateKnockoutRound(String compIdStr, long competitionId, String roundStr, int round,
                                       int matchday, int season,
                                       com.footballmanagergamesimulator.config.CompetitionFormat fmt, Integer legNumber) {
        boolean twoLeg = fmt.isTwoLeg(round);
        if (twoLeg && legNumber != null) {
            if (legNumber == 1) {
                fixtureSchedulingService.getFixturesForRound(compIdStr, roundStr); // draws both legs (idempotent)
                matchRoundSimulator.simulateRound(compIdStr, roundStr, 1);          // leg 1 only — defers
            } else {
                matchRoundSimulator.simulateRound(compIdStr, roundStr, 2);          // leg 2 — aggregate + propagate
                drawNextKnockoutRound(compIdStr, competitionId, round, matchday, season, fmt);
            }
        } else {
            fixtureSchedulingService.getFixturesForRound(compIdStr, roundStr);
            matchRoundSimulator.simulateRound(compIdStr, roundStr, null);
            drawNextKnockoutRound(compIdStr, competitionId, round, matchday, season, fmt);
        }
    }

    private void drawNextKnockoutRound(String compIdStr, long competitionId, int round,
                                       int matchday, int season,
                                       com.footballmanagergamesimulator.config.CompetitionFormat fmt) {
        if (round < fmt.finalRound()) {
            int nextRound = round + 1;
            fixtureSchedulingService.getFixturesForRound(compIdStr, String.valueOf(nextRound));
            fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday + 1, season);
        }
    }

    /**
     * Persist a {@link MatchEvent} "goal" row for the extra-time decider so the
     * match-report timeline never shows fewer goals than the final score after
     * a knockout tiebreaker bumps it. Picks an outfield player from the winning
     * team via position+rating weighting (mirrors the live-sim attacker pick)
     * and writes the goal at minute 120.
     */
    /**
     * Resolve a deferred (live-commit) knockout tie's tiebreak. When the session carries two-axis
     * profiles + vectors (production model), the extra time runs on the attack-vs-defense engine;
     * otherwise it falls back to the scalar powers. {@code team1IsA} maps the session's team1/team2
     * onto the resolver's A/B sides so the deferred profiles line up with the aggregates.
     */
    private com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver.TieDecision decideTie(
            LiveMatchSession session, boolean team1IsA, double powerA, double powerB, int aggA, int aggB) {
        var p1 = session.getDeferredProfile1();
        var p2 = session.getDeferredProfile2();
        if (p1 != null && p2 != null) {
            var v1 = session.getDeferredVector1();
            var v2 = session.getDeferredVector2();
            return team1IsA
                    ? tieResolver.decide(p1, v1, p2, v2, aggA, aggB, new Random())
                    : tieResolver.decide(p2, v2, p1, v1, aggA, aggB, new Random());
        }
        return tieResolver.decide(powerA, powerB, aggA, aggB, new Random());
    }

    public void appendKnockoutWinnerGoal(long competitionId, int season, int roundNumber,
                                         long teamId1, long teamId2,
                                         long winnerTeamId, long loserTeamId) {
        List<Human> winners = humanRepository.findAllByTeamIdAndTypeId(winnerTeamId, TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired())
                .filter(h -> !"GK".equals(h.getPosition()))
                .toList();
        if (winners.isEmpty()) return;

        double totalWeight = 0;
        double[] weights = new double[winners.size()];
        for (int i = 0; i < winners.size(); i++) {
            double posMul = switch (winners.get(i).getPosition()) {
                case "ST" -> 3.0;
                case "AMC", "AML", "AMR" -> 2.0;
                case "MC", "ML", "MR" -> 1.2;
                case "DC", "DL", "DR", "DM" -> 0.4;
                default -> 1.0;
            };
            weights[i] = winners.get(i).getRating() * posMul;
            totalWeight += weights[i];
        }
        Random rnd = new Random();
        double r = rnd.nextDouble() * totalWeight;
        double cum = 0;
        Human scorer = winners.get(winners.size() - 1);
        for (int i = 0; i < winners.size(); i++) {
            cum += weights[i];
            if (r < cum) { scorer = winners.get(i); break; }
        }

        MatchEvent goal = new MatchEvent();
        goal.setCompetitionId(competitionId);
        goal.setSeasonNumber(season);
        goal.setRoundNumber(roundNumber);
        goal.setTeamId1(teamId1);
        goal.setTeamId2(teamId2);
        goal.setMinute(120);
        goal.setEventType("goal");
        goal.setPlayerId(scorer.getId());
        goal.setPlayerName(scorer.getName());
        goal.setTeamId(winnerTeamId);
        goal.setDetails("Extra time winner");
        matchEventRepository.save(goal);
    }

    // ============================================================
    //  Match-result lookups + interactive-live-match commit. Mirrors
    //  what {@link #simulateMatchday} writes per-AI-match, but exposed
    //  so {@link com.footballmanagergamesimulator.service.GameAdvanceService}
    //  and the live-match REST commit path can read/finalize results
    //  without going through controller plumbing.
    // ============================================================

    /**
     * AI vs AI results for a single matchday — excludes any match involving a
     * human team (they get their own popup via {@link #getHumanMatchResult}).
     */
    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Long> humanTeamIds = userContext.getAllHumanTeamIds();
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return results;

        List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, matchday, season);
        for (CompetitionTeamInfoDetail d : details) {
            if (humanTeamIds.contains(d.getTeam1Id()) || humanTeamIds.contains(d.getTeam2Id())) continue;
            if (d.getScore() == null || d.getScore().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("competitionName", competition.getName());
            m.put("team1Name", d.getTeamName1());
            m.put("team2Name", d.getTeamName2());
            m.put("score", d.getScore());
            results.add(m);
        }
        return results;
    }

    /**
     * Single-match result + event timeline for one human team's matchday.
     * Returns an empty map if the round hasn't produced a detail row for
     * that team yet.
     */
    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        Map<String, Object> result = new LinkedHashMap<>();

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

    /**
     * Finalize an interactive live match — runs ALL the post-match work that
     * {@link #simulateMatchday} skipped for sessions handed off to the frontend
     * (scorers, stats, injuries, standings, coefficient points, match report,
     * manager reputation, the detail row that drives standings).
     *
     * <p>Called by {@code POST /match/live/{key}/commit} via the controller
     * delegate when the FE has finished polling the engine to full time.
     * The session's final scores become the source of truth for the round.
     *
     * <p>Idempotent: a session already marked {@code committed} returns
     * an unchanged result map.
     */
    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        LiveMatchSession session = liveMatchSimulationService.getSession(liveKey);
        if (session == null) {
            throw new RuntimeException("No interactive session for key=" + liveKey);
        }
        if (!session.isFinished()) {
            throw new RuntimeException("Cannot commit: match is still in progress (currentMinute < totalMinutes).");
        }
        if (session.isCommitted()) {
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("alreadyCommitted", true);
            already.put("homeScore", session.getHomeScore());
            already.put("awayScore", session.getAwayScore());
            return already;
        }

        long teamId1 = session.getTeamId1();
        long teamId2 = session.getTeamId2();
        long _competitionId = session.getCompetitionId();
        long _roundId = session.getRound();
        int season = session.getSeason();
        int teamScore1 = session.getHomeScore();
        int teamScore2 = session.getAwayScore();
        double teamPower1 = session.getDeferredTeamPower1();
        double teamPower2 = session.getDeferredTeamPower2();
        String tactic1 = session.getDeferredTactic1();
        String tactic2 = session.getDeferredTactic2();
        boolean knockout = session.isDeferredKnockout();
        int legNumber = session.getDeferredLegNumber();
        long tieId = session.getDeferredTieId();
        int matchIndex = session.getDeferredMatchIndex();

        // Knockout progression — the AI/batch path runs this inline in
        // MatchRoundSimulator; for interactive matches it is deferred to here.
        // Routes through KnockoutTieResolver so the live-commit path uses the
        // same config-driven rules. Leg-aware (mirrors MatchRoundSimulator):
        //   leg 1 of a two-leg tie → record the score, decide/propagate nothing;
        //   leg 2 → aggregate with leg 1, decide, propagate the winner;
        //   single-leg → decide via extra time/penalties + propagate.
        String koScoreSuffix = "";
        // Human-readable knockout outcome for the live modal (null = nothing extra
        // to say beyond the 90' score). Surfaced to the FE via the commit response.
        String koResultText = null;
        Long winnerId = null; // null → propagation deferred (first leg of a two-leg tie)
        if (knockout) {
            if (legNumber == 1 && tieId != 0) {
                // First leg: stash the score on the leg-1 row so leg 2 (a later
                // calendar day) can aggregate with it. No decider, no propagation.
                CompetitionTeamInfoMatch leg1Row = competitionTeamInfoMatchRepository
                        .findByTieIdAndLegNumber(tieId, 1).orElse(null);
                if (leg1Row != null) {
                    leg1Row.setTeam1Score(teamScore1);
                    leg1Row.setTeam2Score(teamScore2);
                    competitionTeamInfoMatchRepository.save(leg1Row);
                }
                koScoreSuffix = " (1st leg)";
                koResultText = "First leg — return leg to come";
            } else if (legNumber == 2 && tieId != 0) {
                // Second leg: persist this leg, aggregate with leg 1, decide. The
                // tie is settled on aggregate, so the live 90' score is NOT bumped.
                CompetitionTeamInfoMatch leg2Row = competitionTeamInfoMatchRepository
                        .findByTieIdAndLegNumber(tieId, 2).orElse(null);
                if (leg2Row != null) {
                    leg2Row.setTeam1Score(teamScore1);
                    leg2Row.setTeam2Score(teamScore2);
                    competitionTeamInfoMatchRepository.save(leg2Row);
                }
                CompetitionTeamInfoMatch leg1Row = competitionTeamInfoMatchRepository
                        .findByTieIdAndLegNumber(tieId, 1).orElse(null);
                if (leg1Row != null && leg1Row.getTeam1Score() >= 0) {
                    // team1 here hosted leg 2 (= tie side B); team2 hosted leg 1 (= side A).
                    int aggA = leg1Row.getTeam1Score() + teamScore2;
                    int aggB = leg1Row.getTeam2Score() + teamScore1;
                    var d = decideTie(session, false, teamPower2, teamPower1, aggA, aggB);
                    winnerId = d.teamAWon() ? teamId2 : teamId1;
                    koScoreSuffix = " (agg " + aggA + "-" + aggB
                            + (d.penalties() ? ", pens" : d.extraTime() ? ", a.e.t." : "") + ")";
                    int winnerAgg = d.teamAWon() ? aggA : aggB;
                    int loserAgg = d.teamAWon() ? aggB : aggA;
                    String tail = d.penalties() ? " (won on penalties)" : d.extraTime() ? " (a.e.t.)" : "";
                    koResultText = matchRoundSimulator.roundTeamName(winnerId)
                            + " advance " + winnerAgg + "-" + loserAgg + " on aggregate" + tail;
                } else {
                    // Lost leg-1 record — decide on this match alone (defensive).
                    var d = decideTie(session, true, teamPower1, teamPower2, teamScore1, teamScore2);
                    winnerId = d.teamAWon() ? teamId1 : teamId2;
                    koResultText = matchRoundSimulator.roundTeamName(winnerId) + " advance";
                }
            } else {
                // Single-leg knockout: decide via extra time / penalties. Keep the
                // UI's "+1 extra-time winner goal" bump on a level 90' score.
                if (teamScore1 == teamScore2) {
                    var d = decideTie(session, true, teamPower1, teamPower2, teamScore1, teamScore2);
                    long loserTeamId;
                    if (d.teamAWon()) { session.bumpHomeScore(); teamScore1++; winnerId = teamId1; loserTeamId = teamId2; }
                    else              { session.bumpAwayScore(); teamScore2++; winnerId = teamId2; loserTeamId = teamId1; }
                    appendKnockoutWinnerGoal(_competitionId, season, (int) _roundId, teamId1, teamId2, winnerId, loserTeamId);
                    if (d.penalties()) koScoreSuffix = " (pens)";
                    else if (d.extraTime()) koScoreSuffix = " (a.e.t.)";
                    koResultText = matchRoundSimulator.roundTeamName(winnerId)
                            + (d.penalties() ? " win on penalties" : " win after extra time");
                } else {
                    winnerId = teamScore1 > teamScore2 ? teamId1 : teamId2;
                }
            }

            // Propagate the winner into the next round (mirrors MatchRoundSimulator):
            // national cup → fill the pre-created bracket slot; LoC/Stars Cup →
            // legacy CompetitionTeamInfo flow keyed on round+1.
            if (winnerId != null) {
                Set<Long> cupIds = gameStateService.getCupCompetitionIdsCached();
                if (cupIds != null && cupIds.contains(_competitionId) && matchIndex > 0) {
                    cupBracketService.propagateWinner(_competitionId, season, _roundId, matchIndex, winnerId);
                } else {
                    CompetitionTeamInfo cti = new CompetitionTeamInfo();
                    cti.setCompetitionId(_competitionId);
                    cti.setRound(_roundId + 1);
                    cti.setTeamId(winnerId);
                    cti.setSeasonNumber((long) season);
                    competitionTeamInfoRepository.save(cti);
                }
            }
        }

        // Scorer tracking + match stats from the live data
        lineupRatingService.getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, _competitionId);
        lineupRatingService.getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, _competitionId);
        // Per-player lineup ratings for the match statistics view
        lineupRatingService.persistPlayerRatings(_competitionId, season, (int) _roundId, teamId1, tactic1);
        lineupRatingService.persistPlayerRatings(_competitionId, season, (int) _roundId, teamId2, tactic2);
        matchStatsService.persistLiveMatchStats(
                _competitionId, season, (int) _roundId, teamId1, teamId2,
                session.asLiveMatchData(), teamPower1, teamPower2);

        // Same post-match work the legacy path runs inline
        matchRoundSimulator.processInjuriesForTeam(teamId1);
        matchRoundSimulator.processInjuriesForTeam(teamId2);
        teamPostMatchService.updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2, teamId2);
        teamPostMatchService.updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1, teamId1);
        europeanCoefficientService.awardCoefficientPoints(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
        teamPostMatchService.generateMatchReport(_competitionId, _roundId, teamId1, teamId2, teamScore1, teamScore2);
        teamPostMatchService.updateManagerReputationAfterMatch(teamId1, teamId2, teamScore1, teamScore2);

        // Detail row (the simulateMatchday loop skipped this for interactive
        // matches). Standings + results page now show the real final score.
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setCompetitionId(_competitionId);
        detail.setRoundId(_roundId);
        detail.setTeam1Id(teamId1);
        detail.setTeam2Id(teamId2);
        detail.setTeamName1(matchRoundSimulator.roundTeamName(teamId1));
        detail.setTeamName2(matchRoundSimulator.roundTeamName(teamId2));
        detail.setScore(teamScore1 + " - " + teamScore2 + koScoreSuffix);
        detail.setSeasonNumber((long) season);
        detail.setLegNumber(legNumber);
        competitionTeamInfoDetailRepository.save(detail);

        session.markCommitted();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knockoutResultText", koResultText);
        result.put("homeScore", teamScore1);
        result.put("awayScore", teamScore2);
        result.put("homeTeamId", teamId1);
        result.put("awayTeamId", teamId2);
        result.put("competitionId", _competitionId);
        result.put("matchday", _roundId);
        result.put("season", season);
        // The post-match PC + suspensions + news are wired up by the caller
        // (MatchController) so this method stays purely "finalize the engine
        // state" without coupling to GameAdvanceService internals.
        return result;
    }
}
