package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.LiveMatchData.PlayerStaminaInfo;
import com.footballmanagergamesimulator.frontend.LiveMatchData.StaminaSnapshot;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.InvalidSubstitutionException;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.PlayerMatchState;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.SubReason;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.SubSwap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tickable live-match engine. Holds all per-match state (squads, scores,
 * timeline, stamina, subs) and exposes the public surface used by
 * MatchController endpoints (advance / snapshot / substitute / commit) plus
 * the deferred-context plumbing used by CompetitionController.
 *
 * <p>This used to be an inner class of {@link LiveMatchSimulationService}.
 * It was hoisted to its own file once {@code tickOneMinute} was split into
 * branch helpers — the file is large enough to deserve top-level treatment
 * and the {@code svc.}-prefixed references make the engine/service split
 * explicit. The service still owns the autowired repositories, stateless
 * helpers (pickWeightedAttacker, pickFouler, applyStaminaTick, …), and the
 * commentary / description constants — session reaches into them via the
 * package-private {@code svc} reference.
 */
public class LiveMatchSession {

    private final LiveMatchSimulationService svc;

    // --- Identity / inputs (final) ---
    final long teamId1, teamId2;
    final long competitionId;
    final int season;
    final int round;
    final boolean generateGoalAnimations;
    final String homeTeamName, awayTeamName, competitionName;

    // --- Squads ---
    final List<Human> team1Outfield, team2Outfield;
    final List<Human> team1All, team2All;
    final Set<Long> team1Ids, team2Ids;

    // --- Stamina model ---
    final Map<Long, PlayerMatchState> matchStates;
    final List<StaminaSnapshot> staminaSnapshots = new ArrayList<>();

    // --- Output containers ---
    final Map<Integer, GoalAnimationData> goalAnimations;
    final List<LiveMatchMinute> timeline = new ArrayList<>();
    final List<MatchEvent> dbEvents = new ArrayList<>();

    // --- Score / stats ---
    int homeScore = 0, awayScore = 0;
    int homeShots = 0, awayShots = 0;
    int homeShotsOnTarget = 0, awayShotsOnTarget = 0;
    int homeCorners = 0, awayCorners = 0;
    int homeFouls = 0, awayFouls = 0;
    int homeYellowCards = 0, awayYellowCards = 0;
    int homeRedCards = 0, awayRedCards = 0;
    int homeOffsides = 0, awayOffsides = 0;
    int homePossessionMinutes = 0;

    // --- Sub tracking ---
    int homeSubsUsed = 0, awaySubsUsed = 0;
    int homeLastSubMin = -10, awayLastSubMin = -10;

    // --- Pre-computed schedules ---
    final double team1PossChance;
    final double team1AttackChance, team2AttackChance;
    final Set<Integer> team1BigChanceMinutes, team2BigChanceMinutes;
    final int firstHalfStoppage, secondHalfStoppage, halfTimeMinute, totalMinutes;

    // --- Engine state ---
    // Non-final so determinism IT can swap in a seeded Random via
    // setRandomForTesting() after construction.
    Random random;
    int currentMinute = 0;
    boolean finished = false;
    /** Flipped to true by {@link #markCommitted()} once the post-match
     *  work has been persisted. Used by GameAdvanceService to know
     *  whether to skip suspensions/news/PC for this match (they happen
     *  on /commit instead). */
    boolean committed = false;

    public boolean isCommitted() { return committed; }
    public boolean isFinished() { return finished; }
    public int getHomeScore() { return homeScore; }
    public int getAwayScore() { return awayScore; }
    public long getTeamId1() { return teamId1; }
    public long getTeamId2() { return teamId2; }
    public long getCompetitionId() { return competitionId; }
    public int getSeason() { return season; }
    public int getRound() { return round; }
    public int getTotalMinutes() { return totalMinutes; }

    /** Mark the session as committed — called by CompetitionController after
     *  all post-match work has run. Idempotent. */
    public synchronized void markCommitted() {
        this.committed = true;
    }

    // ---- Deferred context for /commit (stashed by simulateMatchday) ----
    double deferredTeamPower1, deferredTeamPower2;
    String deferredTactic1, deferredTactic2;
    PersonalizedTactic deferredPersonalizedTactic1;
    PersonalizedTactic deferredPersonalizedTactic2;
    boolean deferredKnockout;
    // Two-leg / bracket context so /commit can aggregate legs and propagate the
    // winner the same way the AI/batch path does in MatchRoundSimulator.
    // legNumber: 0 = single match, 1 = first leg, 2 = second leg.
    // tieId: 0 for single matches; links the two legs of a two-leg tie.
    // matchIndex: 1-based bracket slot for national-cup propagation (0 = N/A).
    int deferredLegNumber;
    long deferredTieId;
    int deferredMatchIndex;
    // Two-axis profiles + tactic vectors so /commit runs the knockout extra time on the same
    // attack-vs-defense model as the AI/batch path (null = scalar fallback).
    TacticalScoreService.TeamProfile deferredProfile1, deferredProfile2;
    TacticalScoreService.TacticVector deferredVector1, deferredVector2;

    public void setDeferredContext(double teamPower1, double teamPower2,
                                   String tactic1, String tactic2,
                                   PersonalizedTactic pt1,
                                   PersonalizedTactic pt2,
                                   boolean knockout,
                                   int legNumber, long tieId, int matchIndex) {
        this.deferredTeamPower1 = teamPower1;
        this.deferredTeamPower2 = teamPower2;
        this.deferredTactic1 = tactic1;
        this.deferredTactic2 = tactic2;
        this.deferredPersonalizedTactic1 = pt1;
        this.deferredPersonalizedTactic2 = pt2;
        this.deferredKnockout = knockout;
        this.deferredLegNumber = legNumber;
        this.deferredTieId = tieId;
        this.deferredMatchIndex = matchIndex;
    }

    /** Stash the two-axis profiles + tactic vectors used to score this match, so /commit can resolve
     *  a knockout tie's extra time on the same model. Pass null when the scalar engine is active. */
    public void setDeferredTwoAxis(TacticalScoreService.TeamProfile p1, TacticalScoreService.TacticVector v1,
                                   TacticalScoreService.TeamProfile p2, TacticalScoreService.TacticVector v2) {
        this.deferredProfile1 = p1;
        this.deferredVector1 = v1;
        this.deferredProfile2 = p2;
        this.deferredVector2 = v2;
    }

    public TacticalScoreService.TeamProfile getDeferredProfile1() { return deferredProfile1; }
    public TacticalScoreService.TeamProfile getDeferredProfile2() { return deferredProfile2; }
    public TacticalScoreService.TacticVector getDeferredVector1() { return deferredVector1; }
    public TacticalScoreService.TacticVector getDeferredVector2() { return deferredVector2; }

    public double getDeferredTeamPower1() { return deferredTeamPower1; }
    public double getDeferredTeamPower2() { return deferredTeamPower2; }
    public String getDeferredTactic1() { return deferredTactic1; }
    public String getDeferredTactic2() { return deferredTactic2; }
    public PersonalizedTactic getDeferredPersonalizedTactic1() { return deferredPersonalizedTactic1; }
    public PersonalizedTactic getDeferredPersonalizedTactic2() { return deferredPersonalizedTactic2; }
    public boolean isDeferredKnockout() { return deferredKnockout; }
    public int getDeferredLegNumber() { return deferredLegNumber; }
    public long getDeferredTieId() { return deferredTieId; }
    public int getDeferredMatchIndex() { return deferredMatchIndex; }

    /** Mutators for /commit's knockout extra-time decider. */
    public synchronized void bumpHomeScore() { this.homeScore++; }
    public synchronized void bumpAwayScore() { this.awayScore++; }

    /** Snapshot the LiveMatchData DTO that's currently cached so /commit's
     *  callers can pass it to persistLiveMatchStats. */
    public LiveMatchData asLiveMatchData() { return snapshot(); }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, null, new Random());
    }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     Random random) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, null, random);
    }

    /**
     * Seeded constructor — used by determinism IT / fuzz tests to make a live
     * match reproducible given a fixed seed. Same (seed, config, inputs) →
     * identical event timeline + score.
     *
     * <p>When {@code matchup} is non-null (two-axis production engine), the per-minute possession and
     * attack chances are derived from the attack-vs-defense matchup (each side's effective attack vs
     * the other's defense, amplified by {@code ratioExponent}, modulated by openness + home bonus) so
     * the live scoreline tracks the same model as the instant engine. When null, the legacy scalar
     * power ratio drives the chances (flag OFF).
     */
    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     TacticalScoreService.Matchup matchup,
                     Random random) {
        this.svc = svc;
        this.teamId1 = teamId1;
        this.teamId2 = teamId2;
        this.competitionId = competitionId;
        this.season = season;
        this.round = round;
        this.generateGoalAnimations = generateGoalAnimations;
        this.random = random;

        this.homeTeamName = svc.teamRepository.findNameById(teamId1);
        this.awayTeamName = svc.teamRepository.findNameById(teamId2);
        this.competitionName = svc.competitionRepository.findNameById(competitionId);

        this.team1Outfield = svc.getOutfieldPlayers(teamId1);
        this.team2Outfield = svc.getOutfieldPlayers(teamId2);
        this.team1All = svc.humanRepository.findAllByTeamIdAndTypeId(teamId1, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());
        this.team2All = svc.humanRepository.findAllByTeamIdAndTypeId(teamId2, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());

        this.matchStates = new HashMap<>();
        Map<Long, PlayerSkills> skillsCache = svc.loadSkillsCache(team1All, team2All);
        svc.initializeMatchStates(matchStates, team1All, skillsCache);
        svc.initializeMatchStates(matchStates, team2All, skillsCache);
        this.team1Ids = team1All.stream().map(Human::getId).collect(Collectors.toSet());
        this.team2Ids = team2All.stream().map(Human::getId).collect(Collectors.toSet());

        this.goalAnimations = generateGoalAnimations ? new LinkedHashMap<>() : null;

        double rawRatio1, rawRatio2;
        if (matchup != null) {
            // Two-axis: chances come from the attack-vs-defense matchup, not a symmetric power ratio.
            MatchEngineConfig.TacticalModel cfg = svc.engineConfig.getTacticalModel();
            double exp = cfg.getRatioExponent();
            double a1 = Math.pow(matchup.effAtt1(), exp), d2 = Math.pow(matchup.effDef2(), exp);
            double a2 = Math.pow(matchup.effAtt2(), exp), d1 = Math.pow(matchup.effDef1(), exp);
            double atkRatio1 = a1 / (a1 + d2); // team1 attack effectiveness vs team2 defense
            double atkRatio2 = a2 / (a2 + d1); // team2 attack effectiveness vs team1 defense
            // Possession from each side's overall strength (effective attack + defense).
            double tot1 = matchup.effAtt1() + matchup.effDef1();
            double tot2 = matchup.effAtt2() + matchup.effDef2();
            double poss1 = (tot1 + tot2) > 0 ? tot1 / (tot1 + tot2) : 0.5;
            // An open game lifts both sides' chances; home side gets the attack bonus.
            double opennessFactor = cfg.getBaseOpenness() > 0 ? matchup.openness() / cfg.getBaseOpenness() : 1.0;
            double homeBonus = 1 + cfg.getHomeAttackBonus();
            this.team1PossChance = Math.min(0.65, poss1 + 0.03);
            this.team1AttackChance = Math.min(0.22, (0.04 + Math.min(0.14, atkRatio1 * 0.18)) * opennessFactor * homeBonus);
            this.team2AttackChance = Math.min(0.22, (0.04 + Math.min(0.14, atkRatio2 * 0.18)) * opennessFactor);
            rawRatio1 = atkRatio1;
            rawRatio2 = atkRatio2;
        } else {
            // Scalar fallback (flag OFF): symmetric power ratio.
            double totalPower = power1 + power2;
            double t1Poss = totalPower > 0 ? power1 / totalPower : 0.5;
            this.team1PossChance = Math.min(0.65, t1Poss + 0.03);
            rawRatio1 = totalPower > 0 ? power1 / totalPower : 0.5;
            rawRatio2 = 1.0 - rawRatio1;
            this.team1AttackChance = 0.04 + Math.min(0.14, rawRatio1 * 0.18);
            this.team2AttackChance = 0.04 + Math.min(0.14, rawRatio2 * 0.18);
        }

        int team1BigChances = svc.computeBigChances(rawRatio1, random);
        int team2BigChances = svc.computeBigChances(rawRatio2, random);
        this.team1BigChanceMinutes = svc.pickRandomMinutes(team1BigChances, 5, 89, random);
        this.team2BigChanceMinutes = svc.pickRandomMinutes(team2BigChances, 5, 89, random);
        team2BigChanceMinutes.removeAll(team1BigChanceMinutes);

        this.firstHalfStoppage = random.nextInt(6);
        this.secondHalfStoppage = random.nextInt(6);
        this.halfTimeMinute = 45 + firstHalfStoppage;
        this.totalMinutes = 90 + firstHalfStoppage + secondHalfStoppage;

        // Kickoff event — emitted once at session creation so the timeline
        // is non-empty even before the first advance.
        timeline.add(svc.createMinuteEvent(1, 0, 0, "kickoff",
                "The referee blows the whistle! " + homeTeamName + " vs " + awayTeamName + " is underway!",
                0, null, 0, null));
    }

    /**
     * Tick the engine forward up to {@code targetMinute} (clamped to
     * {@code totalMinutes}). Idempotent — calling with a minute already
     * past returns immediately. When the engine reaches full time on this
     * call, the full-time event is emitted, match events flushed to DB,
     * and post-match fitness persisted.
     *
     * <p>Synchronized — guards against concurrent /advance and /substitute
     * calls from racing the same session.
     */
    synchronized void advanceUntil(int targetMinute) {
        int endMinute = Math.min(targetMinute, totalMinutes);
        if (currentMinute >= endMinute) return;

        // The ThreadLocal lives on goalAnimationService — set + cleared
        // around the tick so animations created mid-advance pick up the
        // first-half stoppage value, and so we don't leak the value to
        // unrelated work on this thread.
        svc.goalAnimationService.setMatchStoppage(firstHalfStoppage);
        try {
            while (currentMinute < endMinute) {
                currentMinute++;
                tickOneMinute(currentMinute);
            }
        } finally {
            svc.goalAnimationService.clearMatchStoppage();
        }

        if (currentMinute >= totalMinutes && !finished) {
            finished = true;
            timeline.add(svc.createMinuteEvent(totalMinutes, homeScore, awayScore, "full_time",
                    "FULL TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                    0, null, 0, null));
            if (!dbEvents.isEmpty()) svc.matchEventRepository.saveAll(dbEvents);
            svc.persistPostMatchFitness(matchStates, team1All, team2All);
        }
    }

    /** Count players from {@code teamIds} currently on the pitch. */
    private int countOnPitch(Set<Long> teamIds) {
        int count = 0;
        for (Long id : teamIds) {
            PlayerMatchState s = matchStates.get(id);
            if (s != null && s.isOnPitch) count++;
        }
        return count;
    }

    private void tickOneMinute(int min) {
        boolean isHomeBigChance = team1BigChanceMinutes.contains(min);
        boolean isAwayBigChance = team2BigChanceMinutes.contains(min);
        boolean forcedAttack = isHomeBigChance || isAwayBigChance;

        // Man-advantage adjustment: a short-handed team (red card) sees less
        // of the ball and creates fewer chances. The opponent benefits from
        // the extra possession; their attack chance is left untouched since
        // they already get more attacking rolls per minute.
        int team1OnPitch = countOnPitch(team1Ids);
        int team2OnPitch = countOnPitch(team2Ids);
        int manDiff = team1OnPitch - team2OnPitch; // + = team1 advantage
        double effectivePossChance = team1PossChance;
        if (manDiff != 0) {
            effectivePossChance = Math.max(0.15, Math.min(0.85, team1PossChance + 0.08 * manDiff));
        }

        boolean team1HasBall = forcedAttack
                ? isHomeBigChance
                : random.nextDouble() < effectivePossChance;
        if (team1HasBall) homePossessionMinutes++;

        long attackingTeamId = team1HasBall ? teamId1 : teamId2;
        String attackingTeamName = team1HasBall ? homeTeamName : awayTeamName;
        List<Human> attackers = svc.filterOnPitch(team1HasBall ? team1Outfield : team2Outfield, matchStates);
        if (attackers.isEmpty()) attackers = team1HasBall ? team1Outfield : team2Outfield;
        List<Human> allDefenders = svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates);
        if (allDefenders.isEmpty()) allDefenders = team1HasBall ? team2All : team1All;

        double roll = random.nextDouble();
        double currentAttackChance;
        if (forcedAttack) {
            currentAttackChance = 1.0;
        } else {
            double base = team1HasBall ? team1AttackChance : team2AttackChance;
            int attackerOnPitch = team1HasBall ? team1OnPitch : team2OnPitch;
            currentAttackChance = base * svc.manAdvantageAttackMultiplier(attackerOnPitch);
        }
        double attackEnd     = currentAttackChance;
        double possessionEnd = attackEnd + 0.38;
        double foulEnd       = possessionEnd + 0.10;
        double offsideEnd    = foulEnd + 0.04;

        if (roll < attackEnd) {
            tickAttackBranch(min, attackers, attackingTeamId, attackingTeamName, team1HasBall, forcedAttack);
        } else if (roll < possessionEnd) {
            tickPossessionBranch(min, attackingTeamId, attackingTeamName);
        } else if (roll < foulEnd) {
            tickFoulBranch(min, allDefenders, team1HasBall);
        } else if (roll < offsideEnd) {
            tickOffsideBranch(min, attackers, attackingTeamId, attackingTeamName, team1HasBall);
        } else {
            tickBuildupBranch(min, attackingTeamId, attackingTeamName);
        }

        // Reactive AI substitutions for both sides.
        tickAiSubsForTeam(min, true);
        tickAiSubsForTeam(min, false);

        // Stamina tick + snapshot — tempo from deferred tactics (set by
        // CompetitionController right after createInteractiveSession). Falls
        // back to Standard tempo when tactics weren't stashed (legacy path).
        double tempoMult1 = svc.tempoMultiplier(
                deferredPersonalizedTactic1 != null ? deferredPersonalizedTactic1.getTempo() : null);
        double tempoMult2 = svc.tempoMultiplier(
                deferredPersonalizedTactic2 != null ? deferredPersonalizedTactic2.getTempo() : null);
        svc.applyStaminaTick(matchStates, team1Ids, team2Ids, tempoMult1, tempoMult2);
        if (min % LiveMatchSimulationService.STAMINA_SNAPSHOT_INTERVAL == 0 || min == totalMinutes) {
            staminaSnapshots.add(svc.captureStaminaSnapshot(min, matchStates, team1Ids, team2Ids));
        }

        // Half time
        if (min == halfTimeMinute) {
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "half_time",
                    "HALF TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                    0, null, 0, null));
        }
    }

    /** Goal / save / miss / blocked / corner outcomes. Picks the shooter,
     *  rolls the outcome, updates score+shot counters, emits timeline +
     *  dbEvents + (optionally) goal animations. */
    private void tickAttackBranch(int min, List<Human> attackers,
                                   long attackingTeamId, String attackingTeamName,
                                   boolean team1HasBall, boolean forcedAttack) {
        if (attackers.isEmpty()) return;
        Human attacker = svc.pickWeightedAttacker(attackers, matchStates, random);
        if (team1HasBall) homeShots++; else awayShots++;

        double attackRoll = random.nextDouble();
        double goalCutoff    = forcedAttack ? 0.30 : 0.17;
        double saveCutoff    = forcedAttack ? 0.60 : 0.42;
        double missCutoff    = forcedAttack ? 0.85 : 0.70;
        double blockedCutoff = forcedAttack ? 0.95 : 0.87;

        if (attackRoll < goalCutoff) {
            String playType = svc.pickAttackPlayType("GOAL", random);
            if (team1HasBall) { homeScore++; homeShotsOnTarget++; }
            else { awayScore++; awayShotsOnTarget++; }

            String goalDesc = LiveMatchSimulationService.GOAL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.GOAL_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "GOAL");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "goal",
                    prefix + "GOAL! " + attacker.getName() + "! " + goalDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "goal", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "" : prefix.trim() + " ") + goalDesc));

            Human goalAssister = null;
            if (random.nextDouble() < 0.7 && attackers.size() > 1) {
                goalAssister = svc.pickDifferentPlayer(attackers, attacker, random);
                if (goalAssister != null) {
                    dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "assist", goalAssister.getId(), goalAssister.getName(), attackingTeamId, "Assist"));
                }
            }

            if (goalAnimations != null) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, goalAssister,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "GOAL", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < saveCutoff) {
            String playType = svc.pickAttackPlayType("SAVE", random);
            if (team1HasBall) homeShotsOnTarget++; else awayShotsOnTarget++;
            String saveDesc = LiveMatchSimulationService.SAVE_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.SAVE_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "SAVE");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_saved",
                    prefix + attacker.getName() + " " + saveDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            // Persist save events so the Match Events summary can show
            // "penalty saved" / "free kick saved" alongside goals + cards.
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "shot_saved", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "Shot saved" : prefix.trim())));

            boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.25);
            if (goalAnimations != null && shouldAnimate) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, null,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "SAVE", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < missCutoff) {
            String playType = svc.pickAttackPlayType("MISS", random);
            String missDesc = LiveMatchSimulationService.MISS_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.MISS_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "MISS");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_wide",
                    prefix + attacker.getName() + " " + missDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "shot_wide", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "Shot wide" : prefix.trim())));

            boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.15);
            if (goalAnimations != null && shouldAnimate) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, null,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "MISS", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < blockedCutoff) {
            String blockDesc = LiveMatchSimulationService.BLOCK_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.BLOCK_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_blocked",
                    attacker.getName() + blockDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

        } else {
            // Corner
            if (team1HasBall) homeCorners++; else awayCorners++;
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "corner",
                    "Corner kick for " + attackingTeamName + ".",
                    0, null, attackingTeamId, attackingTeamName));

            if (random.nextDouble() < 0.08 && !attackers.isEmpty()) {
                Human header = svc.pickWeightedAttacker(attackers, matchStates, random);
                if (team1HasBall) { homeScore++; homeShotsOnTarget++; homeShots++; }
                else { awayScore++; awayShotsOnTarget++; awayShots++; }

                timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "goal",
                        "GOAL! " + header.getName() + " rises highest and heads it in from the corner!",
                        header.getId(), header.getName(), attackingTeamId, attackingTeamName));
                dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "goal", header.getId(), header.getName(), attackingTeamId, "Header from corner"));

                if (goalAnimations != null) {
                    GoalAnimationData anim = svc.goalAnimationService.generate(
                            svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                            svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                            header, null,
                            attackingTeamId, team1HasBall ? teamId2 : teamId1,
                            teamId1, min, "GOAL");
                    if (anim != null) goalAnimations.put(min, anim);
                }
            }
        }
    }

    /** Sparse "team holding the ball" commentary; only every 5 minutes (and
     *  at kickoff) to avoid spamming the feed. */
    private void tickPossessionBranch(int min, long attackingTeamId, String attackingTeamName) {
        if (min % 5 == 0 || min == 1) {
            String template = LiveMatchSimulationService.POSSESSION_COMMENTARY[
                    random.nextInt(LiveMatchSimulationService.POSSESSION_COMMENTARY.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "commentary",
                    String.format(template, attackingTeamName),
                    0, null, attackingTeamId, attackingTeamName));
        }
    }

    /** Foul + card resolution. Pace-weighted fouler pick; 22% yellow, 2% red,
     *  rest are uncommented fouls (gated by min%4 to keep the feed quiet).
     *  Red card flips the player's isOnPitch off so subsequent ticks
     *  exclude them from action picking + animations. */
    private void tickFoulBranch(int min, List<Human> allDefenders, boolean team1HasBall) {
        if (allDefenders.isEmpty()) return;
        Human fouler = svc.pickFouler(allDefenders, matchStates, random);
        if (fouler == null) fouler = allDefenders.get(random.nextInt(allDefenders.size()));
        if (team1HasBall) awayFouls++; else homeFouls++;
        long foulerTeamId = team1HasBall ? teamId2 : teamId1;
        String foulerTeamName = team1HasBall ? awayTeamName : homeTeamName;
        double cardRoll = random.nextDouble();
        if (cardRoll < 0.22) {
            if (team1HasBall) awayYellowCards++; else homeYellowCards++;
            String foulDesc = LiveMatchSimulationService.FOUL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.FOUL_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "yellow_card",
                    fouler.getName() + " " + foulDesc + " Yellow card!",
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "yellow_card", fouler.getId(), fouler.getName(), foulerTeamId, "Yellow card"));
            PlayerMatchState ycState = matchStates.get(fouler.getId());
            // First yellow only — the per-player snapshot just needs to know
            // they're carrying one. A second yellow would normally promote to
            // red, but the engine handles red as a separate path below.
            if (ycState != null && ycState.yellowCardMinute == 0) {
                ycState.yellowCardMinute = min;
            }
        } else if (cardRoll < 0.24) {
            if (team1HasBall) awayRedCards++; else homeRedCards++;
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "red_card",
                    "RED CARD! " + fouler.getName() + " is sent off for a terrible challenge!",
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "red_card", fouler.getId(), fouler.getName(), foulerTeamId, "Red card"));
            PlayerMatchState rcState = matchStates.get(fouler.getId());
            if (rcState != null) {
                rcState.isOnPitch = false;
                rcState.redCardMinute = min;
            }
        } else if (min % 4 == 0) {
            String foulDesc = LiveMatchSimulationService.FOUL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.FOUL_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "foul",
                    fouler.getName() + " " + foulDesc,
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
        }
    }

    /** Offside whistle; throttled to one event every 3 minutes to keep the
     *  feed readable when the engine rolls offsides repeatedly. */
    private void tickOffsideBranch(int min, List<Human> attackers,
                                    long attackingTeamId, String attackingTeamName,
                                    boolean team1HasBall) {
        if (attackers.isEmpty()) return;
        Human offside = attackers.get(random.nextInt(attackers.size()));
        if (team1HasBall) homeOffsides++; else awayOffsides++;
        if (min % 3 == 0) {
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "offside",
                    offside.getName() + " is caught offside. Free kick to the defence.",
                    offside.getId(), offside.getName(), attackingTeamId, attackingTeamName));
        }
    }

    /** Generic "build-up play" commentary fallback; every 7 minutes so the
     *  feed picks up some chatter even on quiet stretches. */
    private void tickBuildupBranch(int min, long attackingTeamId, String attackingTeamName) {
        if (min % 7 == 0) {
            String template = LiveMatchSimulationService.BUILDUP_COMMENTARY[
                    random.nextInt(LiveMatchSimulationService.BUILDUP_COMMENTARY.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "commentary",
                    String.format(template, attackingTeamName),
                    0, null, attackingTeamId, attackingTeamName));
        }
    }

    /** Reactive AI substitutions for one side, parameterised by team. Caps
     *  at 3 subs per side and enforces ≥8 minutes between subs. Falls back
     *  to a FATIGUE swap if the originally-decided reason (OFFENSIVE /
     *  DEFENSIVE) can't find an eligible swap. */
    private void tickAiSubsForTeam(int min, boolean isHome) {
        int subsUsed   = isHome ? homeSubsUsed : awaySubsUsed;
        int lastSubMin = isHome ? homeLastSubMin : awayLastSubMin;
        List<Human> squad = isHome ? team1All : team2All;
        if (subsUsed >= 3 || min - lastSubMin < 8 || squad.size() <= 11) return;

        Set<Long> teamIds = isHome ? team1Ids : team2Ids;
        int ownScore = isHome ? homeScore : awayScore;
        int oppScore = isHome ? awayScore : homeScore;
        long teamId = isHome ? teamId1 : teamId2;
        String teamName = isHome ? homeTeamName : awayTeamName;

        SubReason reason = svc.decideSubReason(min, ownScore, oppScore, matchStates, teamIds);
        if (reason == null) return;

        SubSwap swap = svc.performSub(matchStates, squad, reason);
        if (swap == null && reason != SubReason.FATIGUE) {
            swap = svc.performSub(matchStates, squad, SubReason.FATIGUE);
            if (swap != null) reason = SubReason.FATIGUE;
        }
        if (swap == null) return;

        if (isHome) { homeSubsUsed++; homeLastSubMin = min; }
        else        { awaySubsUsed++; awayLastSubMin = min; }

        String commentary = svc.buildSubCommentary(teamName, swap, reason);
        timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "substitution",
                commentary, swap.off.getId(), swap.off.getName(), teamId, teamName));
        dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                min, "substitution", swap.off.getId(), swap.off.getName(), teamId,
                reason.name() + " | Off: " + swap.off.getName() + " | On: " + swap.on.getName()));
    }

    /**
     * Apply a manager-driven substitution. Validates everything the
     * endpoint can validate and throws {@link InvalidSubstitutionException}
     * with a human-readable message on any failure. On success, toggles
     * the on-pitch flags, bumps the per-side counter, and adds a manual
     * sub event to the timeline.
     *
     * <p>The team side is derived from {@code playerOutId} — caller does
     * not have to pass it, and we reject mixing players across teams.
     */
    synchronized void applyUserSub(long playerOutId, long playerInId) {
        // Note: we deliberately allow subs even after the engine reaches
        // full-time — Faza 3 v1 runs the engine synchronously and lets the
        // user make subs DURING playback. The swap toggles isOnPitch (so
        // the live pitch+bench view updates) and a sub event is spliced
        // into the timeline at the user's current minute. The pre-baked
        // events past that point are not re-simulated — that's the v1
        // limitation, and the real "engine respects subs" mode is a
        // bigger refactor.
        if (playerOutId == playerInId) throw new InvalidSubstitutionException("Same player on both ends of the swap.");

        PlayerMatchState off = matchStates.get(playerOutId);
        PlayerMatchState on = matchStates.get(playerInId);
        if (off == null) throw new InvalidSubstitutionException("Player coming off is not in either squad.");
        if (on == null) throw new InvalidSubstitutionException("Player coming on is not in either squad.");

        boolean isHome;
        if (team1Ids.contains(playerOutId) && team1Ids.contains(playerInId)) isHome = true;
        else if (team2Ids.contains(playerOutId) && team2Ids.contains(playerInId)) isHome = false;
        else throw new InvalidSubstitutionException("Both players must belong to the same team.");

        int subsUsed = isHome ? homeSubsUsed : awaySubsUsed;
        if (subsUsed >= 3) throw new InvalidSubstitutionException("No substitutions remaining.");
        if (!off.isOnPitch) throw new InvalidSubstitutionException("Player " + off.name + " is not on the pitch.");
        if (on.isOnPitch) throw new InvalidSubstitutionException("Player " + on.name + " is already on the pitch.");

        // GK can only be swapped with a GK; otherwise either side becomes
        // GK-less or has two GKs.
        boolean offIsGk = "GK".equals(off.position);
        boolean onIsGk = "GK".equals(on.position);
        if (offIsGk != onIsGk) {
            throw new InvalidSubstitutionException("GK can only be swapped with another GK.");
        }

        // Apply
        off.isOnPitch = false;
        on.isOnPitch = true;
        if (isHome) { homeSubsUsed++; homeLastSubMin = currentMinute; }
        else        { awaySubsUsed++; awayLastSubMin = currentMinute; }

        // Build Human refs for the event
        Human offHuman = svc.findById(isHome ? team1All : team2All, off.playerId);
        Human onHuman  = svc.findById(isHome ? team1All : team2All, on.playerId);
        if (offHuman != null && onHuman != null) {
            SubSwap swap = new SubSwap(offHuman, onHuman);
            String teamName = isHome ? homeTeamName : awayTeamName;
            long teamId = isHome ? teamId1 : teamId2;
            String commentary = svc.buildSubCommentary(teamName, swap, SubReason.MANUAL);
            int eventMinute = Math.max(1, lastSubAtMinute > 0 ? lastSubAtMinute : currentMinute);

            LiveMatchMinute subEvent = svc.createMinuteEvent(eventMinute, homeScore, awayScore, "substitution",
                    commentary, offHuman.getId(), offHuman.getName(), teamId, teamName);
            // Insert chronologically — find the last existing event with
            // minute <= eventMinute and insert right after it. The pre-baked
            // timeline already covers minutes 1..totalMinutes so the user's
            // sub at min 60 needs to land between the existing min-60 events
            // and the min-61 events, not at the end.
            int insertAt = timeline.size();
            for (int i = 0; i < timeline.size(); i++) {
                if (timeline.get(i).getMinute() > eventMinute) { insertAt = i; break; }
            }
            timeline.add(insertAt, subEvent);

            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    eventMinute, "substitution", offHuman.getId(), offHuman.getName(), teamId,
                    "MANUAL | Off: " + offHuman.getName() + " | On: " + onHuman.getName()));
        }
    }

    /** Optional override for the minute at which a manual sub should be
     *  recorded. Set by the controller from the request body and consumed
     *  by the next applyUserSub call. Reset to -1 between calls. */
    private int lastSubAtMinute = -1;

    public synchronized LiveMatchData applyUserSubAtMinuteAndSnapshot(long playerOutId, long playerInId, int atMinute) {
        this.lastSubAtMinute = Math.max(1, atMinute);
        try {
            return applyUserSubAndSnapshot(playerOutId, playerInId);
        } finally {
            this.lastSubAtMinute = -1;
        }
    }

    /** Combined advance + snapshot in a single critical section. */
    public synchronized LiveMatchData advanceUntilAndSnapshot(int targetMinute) {
        advanceUntil(targetMinute);
        return buildResult();
    }

    /** Combined manual-sub + snapshot in a single critical section. */
    public synchronized LiveMatchData applyUserSubAndSnapshot(long playerOutId, long playerInId) {
        applyUserSub(playerOutId, playerInId);
        return buildResult();
    }

    /** Synchronized snapshot of current state (safe to call from any thread). */
    public synchronized LiveMatchData snapshot() {
        return buildResult();
    }

    /** Build the LiveMatchData DTO from current state. Safe to call mid-match
     *  for interactive endpoints — possession uses elapsed minutes as the
     *  denominator so partial-match values stay sensible. Also populates
     *  the live-state fields (pitch/bench/subsRemaining/currentMinute). */
    LiveMatchData buildResult() {
        int denom = Math.max(1, Math.min(currentMinute, 90));
        int possessionPct = Math.min(100, homePossessionMinutes * 100 / denom);

        LiveMatchData data = new LiveMatchData();
        data.setGoalAnimations(goalAnimations);
        data.setHomeTeamId(teamId1);
        data.setAwayTeamId(teamId2);
        data.setHomeTeamName(homeTeamName);
        data.setAwayTeamName(awayTeamName);
        data.setCompetitionName(competitionName);
        data.setCompetitionId(competitionId);
        data.setRound(round);
        data.setTimeline(timeline);
        data.setHomeScore(homeScore);
        data.setAwayScore(awayScore);
        data.setHomePossession(possessionPct);
        data.setAwayPossession(100 - possessionPct);
        data.setHomeShots(homeShots);
        data.setAwayShots(awayShots);
        data.setHomeShotsOnTarget(homeShotsOnTarget);
        data.setAwayShotsOnTarget(awayShotsOnTarget);
        data.setHomeCorners(homeCorners);
        data.setAwayCorners(awayCorners);
        data.setHomeFouls(homeFouls);
        data.setAwayFouls(awayFouls);
        data.setHomeYellowCards(homeYellowCards);
        data.setAwayYellowCards(awayYellowCards);
        data.setHomeRedCards(homeRedCards);
        data.setAwayRedCards(awayRedCards);
        data.setHomeOffsides(homeOffsides);
        data.setAwayOffsides(awayOffsides);
        data.setFirstHalfStoppage(firstHalfStoppage);
        data.setSecondHalfStoppage(secondHalfStoppage);
        data.setStaminaSnapshots(staminaSnapshots);

        // --- Interactive live state ---
        data.setCurrentMinute(currentMinute);
        data.setFinished(finished);
        data.setHomeSubsRemaining(Math.max(0, 3 - homeSubsUsed));
        data.setAwaySubsRemaining(Math.max(0, 3 - awaySubsUsed));
        data.setHomePitch(buildPitchView(team1Ids, true));
        data.setAwayPitch(buildPitchView(team2Ids, true));
        data.setHomeBench(buildPitchView(team1Ids, false));
        data.setAwayBench(buildPitchView(team2Ids, false));
        return data;
    }

    /** Slice of {@link #matchStates} for the live pitch/bench view. */
    private List<PlayerStaminaInfo> buildPitchView(Set<Long> teamIds, boolean onPitch) {
        List<PlayerStaminaInfo> out = new ArrayList<>();
        for (PlayerMatchState s : matchStates.values()) {
            if (!teamIds.contains(s.playerId)) continue;
            if (s.isOnPitch != onPitch) continue;
            PlayerStaminaInfo info = new PlayerStaminaInfo();
            info.setPlayerId(s.playerId);
            info.setName(s.name);
            info.setPosition(s.position);
            info.setStamina((int) Math.round(s.currentStamina));
            info.setMinutesPlayed(s.minutesPlayed);
            info.setOnPitch(s.isOnPitch);
            out.add(info);
        }
        return out;
    }
}
