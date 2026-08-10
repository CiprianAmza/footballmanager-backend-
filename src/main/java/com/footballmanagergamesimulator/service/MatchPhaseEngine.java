package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import com.footballmanagergamesimulator.frontend.MatchPhaseData.PhaseAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Presentational possession-chain generator (Faza A of the 3D visualization
 * plan): turns each authoritative live-engine minute into a continuous spatial
 * sequence of discrete actions — forward/back/lateral passes, dribbles,
 * tackles, throw-ins, corners, free kicks, penalties, offside through-balls —
 * with ball coordinates in the shared 0-100 pitch space.
 *
 * <p>Strictly cosmetic and strictly derived: it runs AFTER {@code tickOneMinute}
 * has decided the minute's timeline events, consumes its own deterministic RNG
 * (never the session's checkpointed stream, so RNG parity and cold recovery are
 * untouched), and its chain terminals always reproduce the timeline events it
 * was handed. Scores, stats, and persistence never read from this layer.
 *
 * <p>Known v1 limits: penalty/free-kick flavour is detected from the stable
 * commentary prefixes built by {@code playTypePrefix} (the timeline does not
 * carry play type as data yet), and chains for minutes simulated before a cold
 * recovery are not regenerated.
 */
@Service
public class MatchPhaseEngine {

    @Autowired
    MatchEngineConfig engineConfig;

    /** Optional taste model (Phase Lab): learned scenario weights from the
     *  user's ratings. Absent in tests / prod-lean boots — engine falls back
     *  to uniform scenario choice. */
    @Autowired(required = false)
    PhaseTasteService taste;

    /** The scenario library: strategy family → its concrete scenarios. The
     *  engine DECLARES which pair produced each chain (sent to the frontend),
     *  and the Phase Lab can force any pair for preview/rating. */
    public static final java.util.LinkedHashMap<String, List<String>> SCENARIOS = new java.util.LinkedHashMap<>();
    static {
        SCENARIOS.put("POSSESSION", List.of(
                "TIKI_TAKA", "PATIENT_SPELL", "THIRD_MAN", "OVERLOAD_SWITCH", "RECYCLE_RESTART"));
        SCENARIOS.put("HIGH_PRESS", List.of(
                "PRESS_TRAP", "COUNTERPRESS", "FORCED_ERROR", "HIGH_TACKLE", "RUSHED_CLEARANCE"));
        SCENARIOS.put("COUNTER", List.of(
                "CLASSIC_OUTLET", "CARRY_BREAK", "WIDE_BREAK", "OVER_THE_TOP", "QUICK_DAGGER"));
        SCENARIOS.put("WING_PLAY", List.of(
                "BYLINE_CROSS", "EARLY_CROSS", "CUTBACK", "UNDERLAP", "SWITCH_CROSS"));
        SCENARIOS.put("DIRECT", List.of(
                "LONG_BALL_FLICK", "GK_LAUNCH", "CHANNEL_BALL", "SECOND_BALL", "DIAGONAL_SPRAY"));
        SCENARIOS.put("INDIVIDUAL", List.of(
                "SLALOM_RUN", "DEEP_CARRY", "CUT_INSIDE", "WING_ISO", "DRAW_FOUL"));
    }

    /** Light on-pitch player view handed over by the session. */
    public record PhasePlayer(long id, String name, String role, int passing, int vision, int pace,
                              int dribbling, int flair, int crossing, int heading) {}

    /** Everything the engine needs about one simulated minute.
     *  {@code previousEndEvent} is the endEvent of the previous minute's chain
     *  (null at kickoff) — a broken-down attack cues counter-attacks. */
    public record MinuteContext(int minute, boolean homeAttacksRight, boolean team1HasBall,
                                long teamId1, long teamId2,
                                List<PhasePlayer> team1OnPitch, List<PhasePlayer> team2OnPitch,
                                List<LiveMatchMinute> minuteEvents, boolean kickoff,
                                String previousEndEvent,
                                Double previousBallX, Double previousBallY, long seed) {}

    // Timeline eventTypes that the chain must resolve into, in order.
    private static final java.util.Set<String> TERMINAL_EVENTS = java.util.Set.of(
            "goal", "shot_saved", "shot_wide", "shot_blocked",
            "corner", "foul", "yellow_card", "red_card", "offside");

    public MatchPhaseData buildMinutePhase(MinuteContext ctx) {
        return buildPhase(ctx, null, null);
    }

    /** Phase Lab entry point: force a specific strategy/scenario pair. */
    public MatchPhaseData buildScenarioPhase(MinuteContext ctx, String strategy, String scenario) {
        return buildPhase(ctx, strategy, scenario);
    }

    private MatchPhaseData buildPhase(MinuteContext ctx, String forcedStrategy, String forcedScenario) {
        if (ctx.team1OnPitch().isEmpty() || ctx.team2OnPitch().isEmpty()) return null;
        MatchEngineConfig.Phase cfg = engineConfig.getPhase();
        Random rng = new Random(ctx.seed());
        ChainState st = new ChainState(ctx, cfg, rng);
        st.forcedStrategy = forcedStrategy;
        st.forcedScenario = forcedScenario;

        st.startChain();
        List<LiveMatchMinute> terminals = ctx.minuteEvents().stream()
                .filter(e -> TERMINAL_EVENTS.contains(e.getEventType()))
                .toList();
        for (int i = 0; i < terminals.size(); i++) {
            // Restart continuations (keeper distribution, goal kicks, centre
            // restarts) only follow the LAST terminal — chaining a full
            // aftermath between back-to-back shots read as the same shot
            // repeating over and over.
            st.terminalIsLast = i == terminals.size() - 1;
            st.resolveTerminal(terminals.get(i));
        }
        if (terminals.isEmpty()) {
            st.endWithTurnover();
        }
        if (st.phase.getActions().isEmpty()) return null;
        st.phase.setEndEvent(terminals.isEmpty()
                ? "possession" : terminals.get(terminals.size() - 1).getEventType());
        return st.phase;
    }

    /** Mutable state of one minute's chain, in ABSOLUTE pitch coordinates. */
    private class ChainState {
        final MinuteContext ctx;
        final MatchEngineConfig.Phase cfg;
        final Random rng;
        final MatchPhaseData phase = new MatchPhaseData();

        boolean t1HasBall;
        double x, y;
        PhasePlayer carrier;
        /** Pacing multiplier for every action duration this chain — counters
         *  run at sprint tempo (<1), everything else at 1.0. */
        double paceFactor = 1.0;
        /** True while resolving the minute's final terminal — gates the
         *  post-outcome continuations. */
        boolean terminalIsLast = true;
        /** Phase Lab override: play exactly this strategy/scenario. */
        String forcedStrategy;
        String forcedScenario;

        ChainState(MinuteContext ctx, MatchEngineConfig.Phase cfg, Random rng) {
            this.ctx = ctx;
            this.cfg = cfg;
            this.rng = rng;
            this.t1HasBall = ctx.team1HasBall();
            phase.setMinute(ctx.minute());
            phase.setTeamId(possessionTeamId());
            phase.setAttackingRight(attacksRight());
        }

        long possessionTeamId() { return t1HasBall ? ctx.teamId1() : ctx.teamId2(); }
        long defendingTeamId()  { return t1HasBall ? ctx.teamId2() : ctx.teamId1(); }
        List<PhasePlayer> possession() { return t1HasBall ? ctx.team1OnPitch() : ctx.team2OnPitch(); }
        List<PhasePlayer> defenders()  { return t1HasBall ? ctx.team2OnPitch() : ctx.team1OnPitch(); }
        boolean attacksRight() { return t1HasBall == ctx.homeAttacksRight(); }
        /** 0 = own goal line, 100 = opponent goal line, regardless of direction. */
        double progress() { return attacksRight() ? x : 100 - x; }
        void setProgress(double p) { x = attacksRight() ? p : 100 - p; }
        double toAbsoluteX(double p) { return attacksRight() ? p : 100 - p; }

        // ---- chain construction ------------------------------------------------

        void startChain() {
            if (ctx.kickoff()) {
                phase.setStrategy("POSSESSION");
                phase.setScenario("KICKOFF");
                x = 50; y = 50;
                carrier = pickByZone(30);
                add("KICKOFF", carrier, null, possessionTeamId(), x, y, x, y, 400);
                buildup(cfg.getMinBuildupActions()
                        + rng.nextInt(Math.max(1, cfg.getMaxBuildupActions() - cfg.getMinBuildupActions() + 1)));
                return;
            }
            // Ball continuity: pick up EXACTLY where the previous chain left the
            // ball — each pattern then relocates with a real opening pass, so
            // minute boundaries never teleport the ball.
            if (ctx.previousBallX() != null && ctx.previousBallY() != null) {
                x = clamp(ctx.previousBallX(), 1, 99);
                y = clamp(ctx.previousBallY(), 2, 98);
                carrier = pickByZone(progress());
            }
            // Faza A.2: each chain plays a PATTERN, picked from context (a
            // broken-down attack cues the counter) and the squad's attributes
            // (wingers cross, dribblers run, target men win long balls).
            String strategy = forcedStrategy != null ? forcedStrategy : chooseStrategy();
            String scenario = forcedScenario != null ? forcedScenario : chooseScenario(strategy);
            phase.setStrategy(strategy);
            phase.setScenario(scenario);
            runScenario(scenario);
        }

        /** The engine's declaration of WHY this attack happens — biased by how
         *  the previous chain ended (a broken-down attack cues transitions)
         *  and by the squad's strengths. */
        String chooseStrategy() {
            String previous = ctx.previousEndEvent() == null ? "" : ctx.previousEndEvent();
            boolean transitionCue = switch (previous) {
                case "shot_saved", "shot_wide", "shot_blocked", "corner", "offside" -> true;
                default -> false;
            };
            double counterW = transitionCue ? 0.33 : 0.12;
            double pressW = transitionCue ? 0.18 : 0.12;
            PhasePlayer winger = bestWinger();
            double wingW = winger != null && winger.crossing() >= 11 ? 0.17 : 0.08;
            PhasePlayer ace = bestBy(PhasePlayer::dribbling);
            double soloW = ace != null && ace.dribbling() >= 14 ? 0.13 : 0.07;
            double directW = 0.10;
            double possW = 0.30;
            String[] names = { "POSSESSION", "HIGH_PRESS", "COUNTER", "WING_PLAY", "DIRECT", "INDIVIDUAL" };
            double[] weights = { possW, pressW, counterW, wingW, directW, soloW };
            // Learned taste: strategies whose scenarios the user rates well
            // appear more often; a fully dropped family effectively vanishes.
            if (taste != null) {
                for (int i = 0; i < names.length; i++) {
                    weights[i] *= taste.strategyWeight(names[i]);
                }
            }
            double total = 0;
            for (double w : weights) total += w;
            double roll = rng.nextDouble() * (total <= 0 ? 1 : total);
            for (int i = 0; i < names.length; i++) {
                roll -= weights[i];
                if (roll <= 0) return names[i];
            }
            return "POSSESSION";
        }

        /** Uniform among the family's scenarios, scaled by learned weights —
         *  a DROPPED scenario (weight 0) never plays in a live match. */
        String chooseScenario(String strategy) {
            List<String> pool = SCENARIOS.getOrDefault(strategy, List.of("TIKI_TAKA"));
            double[] weights = new double[pool.size()];
            double total = 0;
            for (int i = 0; i < pool.size(); i++) {
                weights[i] = taste != null ? taste.scenarioWeight(pool.get(i)) : 1.0;
                total += weights[i];
            }
            if (total <= 0) return "TIKI_TAKA";
            double roll = rng.nextDouble() * total;
            for (int i = 0; i < pool.size(); i++) {
                roll -= weights[i];
                if (roll <= 0) return pool.get(i);
            }
            return pool.get(pool.size() - 1);
        }

        void runScenario(String scenario) {
            switch (scenario) {
                // POSSESSION
                case "PATIENT_SPELL" -> buildupPatient();
                case "THIRD_MAN" -> buildupThirdMan();
                case "OVERLOAD_SWITCH" -> buildupOverloadSwitch();
                case "RECYCLE_RESTART" -> buildupRecycleRestart();
                // HIGH_PRESS
                case "PRESS_TRAP" -> buildupPressTrap();
                case "COUNTERPRESS" -> buildupCounterpress();
                case "FORCED_ERROR" -> buildupForcedError();
                case "HIGH_TACKLE" -> buildupHighTackle();
                case "RUSHED_CLEARANCE" -> buildupRushedClearance();
                // COUNTER
                case "CLASSIC_OUTLET" -> buildupCounter();
                case "CARRY_BREAK" -> buildupCarryBreak();
                case "WIDE_BREAK" -> buildupWideBreak();
                case "OVER_THE_TOP" -> buildupOverTheTop();
                case "QUICK_DAGGER" -> buildupQuick();
                // WING_PLAY
                case "BYLINE_CROSS" -> buildupWing();
                case "EARLY_CROSS" -> buildupEarlyCross();
                case "CUTBACK" -> buildupCutback();
                case "UNDERLAP" -> buildupUnderlap();
                case "SWITCH_CROSS" -> buildupSwitchCross();
                // DIRECT
                case "LONG_BALL_FLICK" -> buildupLongBall();
                case "GK_LAUNCH" -> buildupGkLaunch();
                case "CHANNEL_BALL" -> buildupChannelBall();
                case "SECOND_BALL" -> buildupSecondBall();
                case "DIAGONAL_SPRAY" -> buildupDiagonalSpray();
                // INDIVIDUAL
                case "SLALOM_RUN" -> buildupIndividual();
                case "DEEP_CARRY" -> buildupDeepCarry();
                case "CUT_INSIDE" -> buildupCutInside();
                case "WING_ISO" -> buildupWingIso();
                case "DRAW_FOUL" -> buildupDrawFoul();
                default -> buildupTikiTaka();
            }
        }

        // ---- pattern buildups --------------------------------------------------

        /** Patient circulation with a one-two punched through the middle. */
        void buildupTikiTaka() {
            openAt(22 + rng.nextInt(16), 30 + rng.nextInt(41));
            buildup(2 + rng.nextInt(4));
            oneTwo();
            buildup(1 + rng.nextInt(4));
        }

        /** A LONG possession spell — 16 to ~30 actions: blocks of circulation
         *  broken up by cross-field switches of play, then the one-two. */
        void buildupPatient() {
            openAt(20 + rng.nextInt(14), 25 + rng.nextInt(51));
            int blocks = 2 + rng.nextInt(3);
            for (int b = 0; b < blocks; b++) {
                buildup(4 + rng.nextInt(4));
                if (rng.nextDouble() < 0.6) switchPlay();
            }
            oneTwo();
            buildup(2 + rng.nextInt(3));
        }

        /** Cross-field switch: one long diagonal out to the far flank. */
        void switchPlay() {
            if (carrier == null) return;
            double targetY = y < 50 ? 78 + rng.nextInt(14) : 8 + rng.nextInt(14);
            PhasePlayer receiver = pickByZone(progress() + 4, targetY);
            passToAt(receiver, "PASS", progress() + 2 + rng.nextInt(6), targetY);
            liftLast(1.8 + rng.nextDouble());
        }

        /** The three-pass dagger: won, moved, delivered — no circulation. */
        void buildupQuick() {
            openAt(30 + rng.nextInt(14), 25 + rng.nextInt(51));
            passTo(pickByZone(progress() + 14), "PASS", 12 + rng.nextInt(8));
            if (rng.nextBoolean()) {
                passTo(pickByZone(progress() + 12), "THROUGH_BALL", 10 + rng.nextInt(8));
            }
            buildup(1);
        }

        // ---- POSSESSION scenarios ---------------------------------------------

        /** Wall pass to a third man arriving between the lines. */
        void buildupThirdMan() {
            openAt(30 + rng.nextInt(12), 30 + rng.nextInt(41));
            buildup(2 + rng.nextInt(3));
            PhasePlayer wall = pickByZone(progress() + 8);
            passToAt(wall, "PASS", progress() + 7, clamp(y + rng.nextInt(9) - 4, 8, 92));
            passTo(pickByZone(Math.max(15, progress() - 6)), "BACK_PASS", -(4 + rng.nextInt(4)));
            passTo(pickByZone(progress() + 14), "THROUGH_BALL", 12 + rng.nextInt(8));
            buildup(1 + rng.nextInt(2));
        }

        /** Crowd one flank with short passes, then release the far side. */
        void buildupOverloadSwitch() {
            double lane = rng.nextBoolean() ? 14 + rng.nextInt(10) : 76 + rng.nextInt(10);
            openAt(34 + rng.nextInt(10), lane);
            int shorts = 3 + rng.nextInt(2);
            for (int i = 0; i < shorts; i++) {
                passToAt(pickByZone(progress() + 3, lane), "PASS",
                        progress() + 2 + rng.nextInt(4), clamp(lane + rng.nextInt(9) - 4, 6, 94));
            }
            switchPlay();
            dribbleTo("DRIBBLE", progress() + 6 + rng.nextInt(5), y);
            buildup(1 + rng.nextInt(2));
        }

        /** Attack stalls, everything back to the keeper, restart the far side. */
        /** The attack stalls, so play drops to MIDFIELD (never all the way
         *  home from the final third) and restarts down the other side. */
        void buildupRecycleRestart() {
            openAt(55 + rng.nextInt(15), 20 + rng.nextInt(61));
            buildup(1 + rng.nextInt(2));
            double resetTo = Math.max(35, progress() - (12 + rng.nextInt(8)));
            passTo(pickByZone(resetTo), "BACK_PASS", -(progress() - resetTo));
            switchPlay();
            buildup(2 + rng.nextInt(3));
        }

        // ---- HIGH_PRESS scenarios ---------------------------------------------

        /** The opponent plays into the trap: high interception, instant strike
         *  buildup. */
        void buildupPressTrap() {
            paceFactor = 0.6;
            setProgress(60 + rng.nextInt(14)); y = 20 + rng.nextInt(61);
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 420);
            passTo(pickByZone(progress() + 10), "PASS", 8 + rng.nextInt(7));
            buildup(1);
        }

        /** Ball lost then won straight back — the counterpress regain. */
        void buildupCounterpress() {
            paceFactor = 0.6;
            setProgress(58 + rng.nextInt(12)); y = 18 + rng.nextInt(65);
            PhasePlayer loser = pickDefenderByZone();
            if (loser != null) {
                double escapeX = clamp(x + (attacksRight() ? -4 : 4), 2, 98);
                add("DRIBBLE", loser, null, defendingTeamId(), x, y, escapeX, y, 0);
                x = escapeX;
            }
            carrier = pickByZone(progress());
            add("TACKLE", carrier, loser, possessionTeamId(), x, y, x, y, 450);
            oneTwo();
            buildup(1);
        }

        /** The press forces a defender's loose back pass — pounced on high. */
        void buildupForcedError() {
            paceFactor = 0.65;
            setProgress(74 + rng.nextInt(10)); y = 30 + rng.nextInt(41);
            PhasePlayer sloppy = pick(defenders(), p -> p.role().startsWith("D"));
            PhasePlayer oppKeeper = pick(defenders(), p -> "GK".equals(p.role()));
            if (sloppy != null && oppKeeper != null) {
                double towardOwnGoalX = clamp(x + (attacksRight() ? 8 : -8), 2, 98);
                double newY = 44 + rng.nextInt(13);
                add("BACK_PASS", sloppy, oppKeeper, defendingTeamId(), x, y, towardOwnGoalX, newY, 0);
                x = towardOwnGoalX; y = newY;
            }
            carrier = pick(possession(), p -> p.role().startsWith("ST"));
            if (carrier == null) carrier = pickByZone(85);
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 420);
        }

        /** The winger hunts the fullback down and robs him on the flank. */
        void buildupHighTackle() {
            paceFactor = 0.6;
            double flankY = rng.nextBoolean() ? 10 + rng.nextInt(10) : 80 + rng.nextInt(10);
            setProgress(64 + rng.nextInt(12)); y = flankY;
            PhasePlayer victim = pick(defenders(), p -> p.role().startsWith("D"));
            carrier = pickByZone(progress(), flankY);
            add("TACKLE", carrier, victim, possessionTeamId(), x, y, x, y, 480);
            dribbleTo("SPRINT_DRIBBLE", progress() + 8 + rng.nextInt(6), flankY);
            passTo(pickByZone(progress() + 6, 50), "PASS", 4 + rng.nextInt(5));
        }

        /** The press forces a hurried clearance; midfield wins the header. */
        void buildupRushedClearance() {
            setProgress(70 + rng.nextInt(10)); y = 25 + rng.nextInt(51);
            PhasePlayer clearer = pick(defenders(), p -> p.role().startsWith("D"));
            if (clearer != null) {
                double outX = toAbsoluteX(Math.max(30, progress() - 28));
                double outY = 20 + rng.nextInt(61);
                add("CLEARANCE", clearer, null, defendingTeamId(), x, y, outX, outY, 0);
                liftLast(2.5 + rng.nextDouble());
                x = outX; y = outY;
            }
            carrier = pickByZone(progress());
            add("HEADER", carrier, null, possessionTeamId(), x, y, x, y, 500);
            buildup(2 + rng.nextInt(2));
        }

        // ---- COUNTER scenarios ------------------------------------------------

        /** One runner carries the break half the pitch himself. */
        void buildupCarryBreak() {
            paceFactor = 0.55;
            setProgress(16 + rng.nextInt(10)); y = 25 + rng.nextInt(51);
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 400);
            PhasePlayer breakRunner = bestByNear(PhasePlayer::pace, progress() + 8, y);
            if (breakRunner != null) {
                passToAt(breakRunner, "PASS", progress() + 8,
                        clamp((laneYOf(breakRunner) + y) / 2, 10, 90));
            }
            dribbleTo("SPRINT_DRIBBLE", progress() + 14 + rng.nextInt(8), y);
            skillMove();
            dribbleTo("SPRINT_DRIBBLE", progress() + 12 + rng.nextInt(8),
                    clamp(y + rng.nextInt(13) - 6, 8, 92));
            passTo(pickByZone(progress() + 8), "PASS", 5 + rng.nextInt(5));
        }

        /** Break down the touchline, finished with the low cutback. */
        void buildupWideBreak() {
            paceFactor = 0.55;
            setProgress(20 + rng.nextInt(10)); y = 30 + rng.nextInt(41);
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 400);
            // Break down the flank the flyer actually plays on.
            PhasePlayer flyer = bestByNear(PhasePlayer::pace, progress() + 20,
                    rng.nextBoolean() ? 15 : 85);
            double flankY = flyer != null && laneYOf(flyer) < 50
                    ? 9 + rng.nextInt(8) : 83 + rng.nextInt(8);
            passToAt(flyer, "PASS", progress() + 18 + rng.nextInt(8), flankY);
            dribbleTo("SPRINT_DRIBBLE", progress() + 16 + rng.nextInt(8), flankY);
            passToAt(pickByZone(88, 50), "PASS", 86 + rng.nextInt(5), 44 + rng.nextInt(13));
        }

        /** Regain and immediately over the top for the sprinter. */
        void buildupOverTheTop() {
            paceFactor = 0.6;
            setProgress(18 + rng.nextInt(10)); y = 25 + rng.nextInt(51);
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 400);
            PhasePlayer sprinter = bestByNear(PhasePlayer::pace, 78, y);
            if (sprinter != null) {
                passToAt(sprinter, "THROUGH_BALL", 74 + rng.nextInt(10),
                        clamp(laneYOf(sprinter) + rng.nextInt(17) - 8, 12, 88));
                liftLast(2.6 + rng.nextDouble() * 1.2);
            }
            dribbleTo("SPRINT_DRIBBLE", progress() + 8 + rng.nextInt(5), y);
        }

        // ---- WING_PLAY scenarios ----------------------------------------------

        /** Cross whipped in early from the deep flank, no byline run. */
        void buildupEarlyCross() {
            PhasePlayer winger = bestWinger();
            if (winger == null) { buildupTikiTaka(); return; }
            openAt(30 + rng.nextInt(8), 35 + rng.nextInt(31));
            boolean left = winger.role().endsWith("L");
            double flankY = left ? 10 + rng.nextInt(8) : 82 + rng.nextInt(8);
            passToAt(winger, "PASS", progress() + 10 + rng.nextInt(6), flankY);
            dribbleTo("DRIBBLE", progress() + 6 + rng.nextInt(4), flankY);
            PhasePlayer spearhead = bestTargetMan();
            double fromX = x, fromY = y;
            setProgress(82 + rng.nextInt(6)); y = 42 + rng.nextInt(17);
            add("CROSS", carrier, spearhead, possessionTeamId(), fromX, fromY, x, y, 0);
            liftLast(2.6 + rng.nextDouble());
            if (spearhead != null) carrier = spearhead;
        }

        /** To the byline and pulled back for the arriving midfielder. */
        void buildupCutback() {
            PhasePlayer winger = bestWinger();
            if (winger == null) { buildupTikiTaka(); return; }
            openAt(40 + rng.nextInt(10), 35 + rng.nextInt(31));
            boolean left = winger.role().endsWith("L");
            double flankY = left ? 6 + rng.nextInt(6) : 88 + rng.nextInt(6);
            passToAt(winger, "PASS", progress() + 12 + rng.nextInt(6), flankY);
            dribbleTo("SPRINT_DRIBBLE", 90 + rng.nextInt(5), flankY);
            passToAt(pickByZone(80, 50), "BACK_PASS", 80 + rng.nextInt(4), 40 + rng.nextInt(21));
        }

        /** The fullback underlaps inside and gets played through. */
        void buildupUnderlap() {
            openAt(36 + rng.nextInt(10),
                    rng.nextBoolean() ? 16 + rng.nextInt(10) : 74 + rng.nextInt(10));
            PhasePlayer back = pick(possession(),
                    p -> p.role().startsWith("D") && !p.role().startsWith("DC"));
            passToAt(back != null ? back : pickByZone(progress() + 6), "PASS",
                    progress() + 8 + rng.nextInt(5), clamp(y + (y < 50 ? 10 : -10), 10, 90));
            passTo(pickByZone(progress() + 12), "THROUGH_BALL", 10 + rng.nextInt(8));
            dribbleTo("DRIBBLE", progress() + 6 + rng.nextInt(4), y);
        }

        /** Switch the flanks, then the cross arrives first time. */
        void buildupSwitchCross() {
            openAt(45 + rng.nextInt(10),
                    rng.nextBoolean() ? 15 + rng.nextInt(10) : 75 + rng.nextInt(10));
            buildup(1 + rng.nextInt(2));
            switchPlay();
            PhasePlayer spearhead = bestTargetMan();
            double fromX = x, fromY = y;
            setProgress(84 + rng.nextInt(6)); y = 43 + rng.nextInt(15);
            add("CROSS", carrier, spearhead, possessionTeamId(), fromX, fromY, x, y, 0);
            liftLast(2.3 + rng.nextDouble());
            if (spearhead != null) carrier = spearhead;
        }

        // ---- DIRECT scenarios --------------------------------------------------

        /** The keeper launches it himself onto the target man. */
        void buildupGkLaunch() {
            PhasePlayer keeper = pick(possession(), p -> "GK".equals(p.role()));
            setProgress(4); y = 50;
            carrier = keeper != null ? keeper : pickByZone(6);
            add("GK_HOLD", carrier, null, possessionTeamId(), x, y, x, y, 700);
            PhasePlayer launchTarget = bestTargetMan();
            passToAt(launchTarget, "PASS", 55 + rng.nextInt(16),
                    launchTarget != null
                            ? clamp(laneYOf(launchTarget) + rng.nextInt(13) - 6, 15, 85)
                            : 25 + rng.nextInt(51));
            liftLast(3.6 + rng.nextDouble() * 1.4);
            passTo(pickByZone(progress() + 5), "HEADER", 3 + rng.nextInt(5));
            liftLast(0.8);
            buildup(1 + rng.nextInt(2));
        }

        /** Ball into the channel pocket for the striker to chase. */
        void buildupChannelBall() {
            openAt(30 + rng.nextInt(10), 30 + rng.nextInt(41));
            PhasePlayer chaser = bestByNear(PhasePlayer::pace, 74, y);
            double channelY = chaser != null && laneYOf(chaser) < 50
                    ? 16 + rng.nextInt(8) : 76 + rng.nextInt(8);
            passToAt(chaser, "THROUGH_BALL", 70 + rng.nextInt(10), channelY);
            liftLast(1.8 + rng.nextDouble());
            dribbleTo("SPRINT_DRIBBLE", progress() + 8 + rng.nextInt(6),
                    clamp(channelY + (channelY < 50 ? 6 : -6), 8, 92));
            passTo(pickByZone(progress() + 4, 50), "PASS", 2 + rng.nextInt(4));
        }

        /** The long ball is headed clear — but the second ball is ours. */
        void buildupSecondBall() {
            openAt(12 + rng.nextInt(8), 30 + rng.nextInt(41));
            PhasePlayer flickTarget = bestTargetMan();
            passToAt(flickTarget, "PASS", 58 + rng.nextInt(10),
                    flickTarget != null
                            ? clamp(laneYOf(flickTarget) + rng.nextInt(13) - 6, 15, 85)
                            : 30 + rng.nextInt(41));
            liftLast(3.0 + rng.nextDouble());
            PhasePlayer header = pick(defenders(), p -> p.role().startsWith("DC"));
            if (header != null) {
                double backX = toAbsoluteX(Math.max(35, progress() - 12));
                double backY = clamp(y + rng.nextInt(25) - 12, 10, 90);
                add("HEADER", header, null, defendingTeamId(), x, y, backX, backY, 0);
                liftLast(1.4);
                x = backX; y = backY;
            }
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 450);
            buildup(2 + rng.nextInt(2));
        }

        /** The deep playmaker sprays raking diagonals flank to flank. */
        void buildupDiagonalSpray() {
            openAt(20 + rng.nextInt(8), 30 + rng.nextInt(41));
            switchPlay();
            buildup(1 + rng.nextInt(2));
            switchPlay();
            buildup(1 + rng.nextInt(2));
        }

        // ---- INDIVIDUAL scenarios ----------------------------------------------

        /** The ball-playing centre back strides out from the back himself. */
        void buildupDeepCarry() {
            openAt(14 + rng.nextInt(8), 30 + rng.nextInt(41));
            PhasePlayer libero = pick(possession(),
                    p -> p.role().startsWith("DC") || p.role().startsWith("DM"));
            if (libero != null && carrier != null && carrier.id() != libero.id()) {
                passToAt(libero, "PASS", progress() + 2, clamp(y + rng.nextInt(13) - 6, 10, 90));
            }
            dribbleTo("DRIBBLE", progress() + 12 + rng.nextInt(6),
                    clamp(y + rng.nextInt(13) - 6, 10, 90));
            dribbleTo("DRIBBLE", progress() + 10 + rng.nextInt(6), y);
            passTo(pickByZone(progress() + 10), "PASS", 8 + rng.nextInt(6));
        }

        /** The winger cuts inside off the flank and drives at the box. */
        void buildupCutInside() {
            PhasePlayer winger = bestWinger();
            if (winger == null) { buildupIndividual(); return; }
            boolean left = winger.role().endsWith("L");
            double flankY = left ? 12 + rng.nextInt(8) : 78 + rng.nextInt(8);
            openAt(50 + rng.nextInt(10), flankY);
            passToAt(winger, "PASS", progress() + 6 + rng.nextInt(4), flankY);
            skillMove();
            dribbleTo("DRIBBLE", progress() + 7 + rng.nextInt(4),
                    clamp(50 + rng.nextInt(21) - 10.0, 20, 80));
        }

        /** Isolate the winger one-v-one: feints, then the burst past. */
        void buildupWingIso() {
            PhasePlayer winger = bestWinger();
            if (winger == null) { buildupIndividual(); return; }
            boolean left = winger.role().endsWith("L");
            double flankY = left ? 8 + rng.nextInt(7) : 85 + rng.nextInt(7);
            openAt(55 + rng.nextInt(10), flankY);
            passToAt(winger, "PASS", progress() + 4, flankY);
            skillMove();
            skillMove();
            dribbleTo("SPRINT_DRIBBLE", progress() + 12 + rng.nextInt(6), flankY);
        }

        /** The dribbler invites the lunge and wins the free kick. */
        void buildupDrawFoul() {
            openAt(48 + rng.nextInt(14), 20 + rng.nextInt(61));
            PhasePlayer ace = bestBy(PhasePlayer::dribbling);
            if (ace != null) {
                passToAt(ace, "PASS", progress() + 5, clamp(y + rng.nextInt(13) - 6, 10, 90));
            }
            dribbleTo("DRIBBLE", progress() + 6 + rng.nextInt(4), y);
            skillMove();
            PhasePlayer fouler = pickDefenderByZone();
            if (fouler != null && carrier != null) {
                add("FOUL", fouler, carrier, defendingTeamId(), x, y, x, y, 700);
                add("FREE_KICK", carrier, null, possessionTeamId(), x, y, x, y, 600);
            }
            buildup(1 + rng.nextInt(2));
        }

        /** Give-and-go: wall pass and the return played first-time in behind. */
        void oneTwo() {
            if (carrier == null) return;
            PhasePlayer runner = carrier;
            PhasePlayer wall = pickByZone(progress() + 6);
            if (wall == null || wall.id() == runner.id()) return;
            passToAt(wall, "PASS", progress() + 5, clamp(y + rng.nextInt(11) - 5, 8, 92));
            passToAt(runner, "PASS", progress() + 8, clamp(y + rng.nextInt(11) - 5, 8, 92));
        }

        /** Feed the winger, tear down the touchline, cross for the spearhead. */
        void buildupWing() {
            PhasePlayer winger = bestWinger();
            if (winger == null) { buildupTikiTaka(); return; }
            openAt(24 + rng.nextInt(10), 35 + rng.nextInt(31));
            boolean left = winger.role().endsWith("L");
            double flankY = left ? 9 + rng.nextInt(7) : 84 + rng.nextInt(7);
            passTo(pickByZone(progress() + 8), "PASS", 6 + rng.nextInt(6));
            passToAt(winger, "PASS", progress() + 9 + rng.nextInt(8), flankY);
            dribbleTo("SPRINT_DRIBBLE", progress() + 10 + rng.nextInt(6), flankY);
            if (winger.flair() >= 12 && rng.nextBoolean()) {
                skillMove();
            }
            dribbleTo("SPRINT_DRIBBLE", Math.min(88, progress() + 8 + rng.nextInt(6)), flankY);
            PhasePlayer spearhead = bestTargetMan();
            double fromX = x, fromY = y;
            setProgress(87 + rng.nextInt(5));
            y = 42 + rng.nextInt(17);
            // A big-flair winger occasionally wraps the cross behind the
            // standing leg — the rabona.
            String crossType = winger.flair() >= 16 && rng.nextDouble() < 0.35
                    ? "RABONA_CROSS" : "CROSS";
            add(crossType, carrier, spearhead, possessionTeamId(), fromX, fromY, x, y, 0);
            liftLast(2.2 + rng.nextDouble());
            if (spearhead != null) carrier = spearhead;
        }

        /** Transition: win it deep, one long out-ball, runner in behind — all
         *  at sprint pacing (paceFactor shortens every action). */
        void buildupCounter() {
            paceFactor = 0.55;
            // The counter starts EXACTLY where the ball was won — only when
            // there is no carried-over position does it pick a deep spot.
            if (ctx.previousBallX() == null) {
                setProgress(14 + rng.nextInt(10));
                y = 25 + rng.nextInt(51);
            }
            carrier = pickByZone(progress());
            add("INTERCEPTION", carrier, null, possessionTeamId(), x, y, x, y, 400);
            PhasePlayer outlet = pickByZone(progress() + 25);
            passToAt(outlet, "PASS", progress() + 22 + rng.nextInt(8),
                    clamp(y + rng.nextInt(31) - 15, 12, 88));
            // Some breaks need an extra carrier before the killer ball.
            if (rng.nextDouble() < 0.4) {
                passTo(pickByZone(progress() + 8), "PASS", 6 + rng.nextInt(5));
            }
            // The through ball goes down the RUNNER's lane — he was already
            // stationed there, so he meets it instead of crossing the pitch.
            double runnerProgress = progress() + 16 + rng.nextInt(8);
            PhasePlayer runner = bestByNear(PhasePlayer::pace, runnerProgress, y);
            if (runner != null) {
                passToAt(runner, "THROUGH_BALL", runnerProgress,
                        clamp(laneYOf(runner) + rng.nextInt(13) - 6, 12, 88));
            }
            dribbleTo("SPRINT_DRIBBLE", progress() + 8 + rng.nextInt(5), y);
        }

        /** The team's dribbler takes on the block. How many defenders the run
         *  beats (0-3) scales with his talent: an average carrier just drives
         *  into space and lays it off; the star strings skill moves together,
         *  leaving a man behind after each one. */
        void buildupIndividual() {
            openAt(35 + rng.nextInt(12), 25 + rng.nextInt(51));
            PhasePlayer ace = bestBy(PhasePlayer::dribbling);
            if (ace == null) { buildup(4); return; }
            passToAt(ace, "PASS", progress() + 6 + rng.nextInt(6),
                    clamp(y + rng.nextInt(21) - 10, 10, 90));
            dribbleTo("DRIBBLE", progress() + 7 + rng.nextInt(5),
                    clamp(y + rng.nextInt(9) - 4, 8, 92));
            int beats = rng.nextInt(4);
            if (ace.dribbling() < 13) beats = Math.min(beats, 1);
            for (int i = 0; i < beats; i++) {
                skillMove();
                dribbleTo("SPRINT_DRIBBLE", progress() + 5 + rng.nextInt(5),
                        clamp(y + rng.nextInt(9) - 4, 8, 92));
            }
            if (beats == 0) {
                // Nothing on: recycle and let the chain carry on.
                passTo(pickByZone(progress() + 8), "PASS", 6 + rng.nextInt(6));
            }
        }

        /** Route one: launched from the back, flicked on by the target man,
         *  second ball collected. */
        void buildupLongBall() {
            openAt(9 + rng.nextInt(8), 30 + rng.nextInt(41));
            PhasePlayer launcher = pick(possession(),
                    p -> "GK".equals(p.role()) || p.role().startsWith("DC"));
            if (launcher != null && carrier != null && carrier.id() != launcher.id()) {
                passToAt(launcher, "BACK_PASS", Math.max(5, progress() - 4), y);
            } else if (launcher != null) {
                carrier = launcher;
            }
            PhasePlayer targetMan = bestTargetMan();
            passToAt(targetMan, "PASS", 58 + rng.nextInt(14),
                    targetMan != null
                            ? clamp(laneYOf(targetMan) + rng.nextInt(13) - 6, 15, 85)
                            : clamp(25 + rng.nextInt(51), 15, 85));
            liftLast(3.2 + rng.nextDouble() * 1.3);
            PhasePlayer second = pickByZone(progress() - 5);
            passToAt(second, "HEADER", progress() - 4, clamp(y + rng.nextInt(13) - 6, 10, 90));
            liftLast(0.8);
            buildup(1 + rng.nextInt(2));
        }

        // ---- pattern helpers ---------------------------------------------------

        PhasePlayer bestWinger() {
            return possession().stream()
                    .filter(p -> p.role().endsWith("L") || p.role().endsWith("R"))
                    .filter(p -> p.role().startsWith("M") || p.role().startsWith("AM")
                            || p.role().startsWith("WB"))
                    .max(java.util.Comparator.comparingInt(PhasePlayer::crossing))
                    .orElse(null);
        }

        PhasePlayer bestTargetMan() {
            return possession().stream()
                    .filter(p -> p.role().startsWith("ST") || p.role().startsWith("AM"))
                    .max(java.util.Comparator.comparingInt(PhasePlayer::heading))
                    .orElse(bestBy(PhasePlayer::heading));
        }

        PhasePlayer bestBy(java.util.function.ToIntFunction<PhasePlayer> attribute) {
            return possession().stream()
                    .filter(p -> !"GK".equals(p.role()))
                    .max(java.util.Comparator.comparingInt(attribute))
                    .orElse(null);
        }

        /** One flavoured skill move by the current carrier: cuts jink hard
         *  toward a side (named relative to the attacking direction), the
         *  stepover sells a feint in stride, the roulette needs real flair. */
        void skillMove() {
            if (carrier == null) return;
            String move;
            double roll = rng.nextDouble();
            if (carrier.flair() >= 16 && roll < 0.25) move = "ROULETTE";
            else if (roll < 0.5) move = "STEPOVER";
            else move = rng.nextBoolean() ? "CUT_LEFT" : "CUT_RIGHT";
            double dy = switch (move) {
                case "CUT_LEFT" -> attacksRight() ? -6 : 6;
                case "CUT_RIGHT" -> attacksRight() ? 6 : -6;
                default -> rng.nextBoolean() ? 3 : -3;
            };
            dribbleTo(move, progress() + ("ROULETTE".equals(move) ? 2 : 4),
                    clamp(y + dy, 6, 94));
        }

        /** Relocate play to a pattern's opening zone with a REAL pass when the
         *  ball is far away (continuity — no teleports between minutes). */
        void openAt(double targetProgress, double targetY) {
            double dist = Math.hypot(toAbsoluteX(targetProgress) - x, targetY - y);
            if (carrier != null && dist > 14) {
                passToAt(pickByZone(targetProgress, targetY), "PASS", targetProgress, targetY);
                if (dist > 34) liftLast(1.5 + rng.nextDouble());
            } else {
                setProgress(targetProgress);
                y = clamp(targetY, 4, 96);
                if (carrier == null) carrier = pickByZone(targetProgress);
            }
        }

        /** Pass with an explicit destination (progress + lane), for patterns
         *  that steer play rather than drift with the generic buildup. */
        void passToAt(PhasePlayer target, String type, double newProgress, double newY) {
            if (target == null || carrier == null) return;
            double fromX = x, fromY = y;
            setProgress(clamp(newProgress, 3, 96));
            y = clamp(newY, 4, 96);
            add(type, carrier, target, possessionTeamId(), fromX, fromY, x, y, 0);
            carrier = target;
        }

        /** Carry the ball to an explicit destination (the carrier keeps it). */
        void dribbleTo(String type, double newProgress, double newY) {
            if (carrier == null) return;
            double fromX = x, fromY = y;
            setProgress(clamp(newProgress, 3, 95));
            y = clamp(newY, 4, 96);
            add(type, carrier, null, possessionTeamId(), fromX, fromY, x, y, 0);
        }

        /** A defender steps in mid-chain: possession genuinely flips, the other
         *  side plays a pass, and often the original side wins it straight back
         *  — the visible midfield battle. Terminals re-assert the event team
         *  via ensurePossession, so authoritative outcomes are never touched. */
        void contest() {
            PhasePlayer thief = pickDefenderByZone();
            if (thief == null) return;
            // Sometimes the challenge is mistimed: a plain midfield foul, the
            // free kick is taken quickly and the same chain flows on. Cosmetic
            // only — cards and set-piece goals stay owned by the timeline.
            if (carrier != null && rng.nextDouble() < 0.2) {
                add("FOUL", thief, carrier, defendingTeamId(), x, y, x, y, 700);
                add("FREE_KICK", carrier, null, possessionTeamId(), x, y, x, y, 600);
                return;
            }
            add("INTERCEPTION", thief, null, defendingTeamId(), x, y, x, y, 300);
            t1HasBall = !t1HasBall;
            carrier = thief;
            // The winner plays INSTANTLY — a first-touch escape, never a
            // stand-still while the other side regroups.
            if (rng.nextDouble() < 0.5) {
                dribbleTo("DRIBBLE", clamp(progress() + 4 + rng.nextInt(4), 5, 92),
                        clamp(y + rng.nextInt(11) - 5, 6, 94));
            } else {
                passTo(pickByZone(progress() + 5), "PASS", 4 + rng.nextInt(5));
            }
            if (rng.nextDouble() < 0.55) {
                PhasePlayer winner = pickDefenderByZone();
                if (winner != null) {
                    add("TACKLE", winner, carrier, defendingTeamId(), x, y, x, y, 350);
                    t1HasBall = !t1HasBall;
                    carrier = winner;
                }
            } else {
                passTo(pickByZone(progress() + 6), "PASS", 5 + rng.nextInt(6));
            }
        }

        /** Circulation: forward/back/lateral passes + dribbles, drifting toward
         *  goal — with the occasional genuine midfield contest. */
        void buildup(int steps) {
            boolean contested = false;
            for (int i = 0; i < steps; i++) {
                if (!contested && rng.nextDouble() < 0.14) {
                    contested = true;
                    contest();
                    continue;
                }
                double roll = rng.nextDouble();
                double backChance = cfg.getBackPassBase()
                        + cfg.getBackPassPressureBoost() * (progress() / 100.0);
                // Better passers/readers of the game recycle less blindly and
                // find the forward option a bit more often.
                double skill = carrier == null ? 10 : (carrier.passing() + carrier.vision()) / 2.0;
                backChance = Math.max(0.05, backChance - (skill - 10) * 0.004);

                // Back passes are for midfield circulation — near the
                // opponent's box the only sane options are forward, square,
                // or a SHORT lay-off, never turning the whole attack around.
                if (roll < backChance && progress() > 18 && progress() < 72) {
                    passTo(pickByZone(Math.max(8, progress() - (8 + rng.nextInt(8)))), "BACK_PASS",
                            -(6 + rng.nextInt(9)));
                } else if (roll < backChance + cfg.getLateralPassChance()) {
                    passTo(pickByZone(progress() + rng.nextInt(5) - 2), "LATERAL_PASS",
                            rng.nextInt(5) - 2);
                } else if (roll < backChance + cfg.getLateralPassChance() + cfg.getDribbleChance()) {
                    dribble(4 + rng.nextInt(7));
                } else {
                    passTo(pickByZone(Math.min(92, progress() + (8 + rng.nextInt(11)))), "PASS",
                            8 + rng.nextInt(11));
                }
            }
        }

        void passTo(PhasePlayer target, String type, double dProgress) {
            if (target == null || carrier == null) return;
            double fromX = x, fromY = y;
            setProgress(clamp(progress() + dProgress, 3, 95));
            y = clamp(y + rng.nextInt(25) - 12, 6, 94);
            add(type, carrier, target, possessionTeamId(), fromX, fromY, x, y, 0);
            carrier = target;
        }

        void dribble(double dProgress) {
            if (carrier == null) return;
            double fromX = x, fromY = y;
            setProgress(clamp(progress() + dProgress, 3, 95));
            y = clamp(y + rng.nextInt(13) - 6, 6, 94);
            add("DRIBBLE", carrier, null, possessionTeamId(), fromX, fromY, x, y, 0);
        }

        // ---- terminal resolution ----------------------------------------------

        void resolveTerminal(LiveMatchMinute event) {
            switch (event.getEventType()) {
                case "goal", "shot_saved", "shot_wide", "shot_blocked" -> resolveShot(event);
                case "corner" -> resolveCorner(event);
                case "offside" -> resolveOffside(event);
                case "foul", "yellow_card", "red_card" -> resolveFoul(event);
                default -> { }
            }
        }

        /** The event's team must be in possession; otherwise narrate a turnover. */
        void ensurePossession(long teamId) {
            if (possessionTeamId() == teamId) return;
            PhasePlayer taker = pickDefenderByZone();
            add("INTERCEPTION", taker, null, teamId, x, y, x, y, 500);
            t1HasBall = !t1HasBall;
            carrier = taker != null ? taker : pickByZone(progress());
            buildup(1 + rng.nextInt(2));
        }

        void resolveShot(LiveMatchMinute event) {
            ensurePossession(event.getTeamId());
            PhasePlayer shooter = resolvePlayer(event, possession());
            String commentary = event.getCommentary() == null ? "" : event.getCommentary();
            // Faza B: play type arrives as data on the timeline event; the stable
            // commentary prefixes (playTypePrefix) remain a fallback for events
            // recorded before the field existed.
            String playType = event.getPlayType();
            boolean penalty = "PENALTY".equals(playType) || commentary.startsWith("PENALTY");
            boolean freeKick = "FREE_KICK".equals(playType) || commentary.startsWith("FREE KICK");
            boolean fromCorner = "CORNER".equals(playType);

            if (penalty) {
                setProgress(88); y = 38 + rng.nextInt(25);
                PhasePlayer fouler = pickDefenderByZone();
                add("FOUL", fouler, shooter, defendingTeamId(), x, y, x, y, 700);
                setProgress(89); y = 50;
                add("PENALTY_KICK", shooter, null, possessionTeamId(), x, y, x, y, 1200);
            } else if (freeKick) {
                setProgress(72 + rng.nextInt(8)); y = 25 + rng.nextInt(51);
                PhasePlayer fouler = pickDefenderByZone();
                add("FOUL", fouler, shooter, defendingTeamId(), x, y, x, y, 700);
                add("FREE_KICK", shooter, null, possessionTeamId(), x, y, x, y, 1100);
            } else if (fromCorner) {
                // A corner-flavoured shot: full set-piece routine, ending with
                // the shooter attacking the cross in the box.
                setProgress(99); y = rng.nextBoolean() ? 2 : 98;
                PhasePlayer taker = bestPasser(possession());
                add("CORNER_KICK", taker, null, possessionTeamId(), x, y, x, y, 900);
                double boxX = toAbsoluteX(90), boxY = 44 + rng.nextInt(13);
                add("CROSS", taker, shooter, possessionTeamId(), x, y, boxX, boxY, 0);
                liftLast(2.4 + rng.nextDouble());
                x = boxX; y = boxY;
            } else {
                // The scorer ALWAYS receives the ball before he shoots — a
                // shot must visibly leave HIS boot, never fire itself from
                // wherever the buildup happened to leave the ball.
                boolean shooterHasBall = carrier != null && shooter != null
                        && carrier.id() == shooter.id();
                if (!shooterHasBall) {
                    passTo(shooter != null ? shooter : pickByZone(85), "PASS",
                            Math.max(4, 80 - progress() + rng.nextInt(8)));
                }
                // Shooting range is NON-NEGOTIABLE: if the reception happened
                // deep, he carries the ball to the edge of the box himself —
                // no 40-metre punts. Always forward, never a retreat.
                dribbleTo("SPRINT_DRIBBLE",
                        clamp(Math.max(progress() + 2, 74 + rng.nextInt(12)), 74, 93),
                        clamp(y + rng.nextInt(13) - 6, 22, 78));
            }
            carrier = shooter;

            double goalX = attacksRight() ? 100 : 0;
            // The shot flies straight to its true destination: on target for
            // goal/save/block, or a VARIED miss point — 35% sail over the bar
            // (high arc clearing the 2.35 crossbar at the line), the rest go
            // wide of either post at different widths every time.
            boolean isMiss = "shot_wide".equals(event.getEventType());
            boolean overTheBar = isMiss && rng.nextDouble() < 0.4;
            double aimX = goalX;
            double aimY = 44 + rng.nextInt(13);
            double shotPeak = rng.nextDouble() < 0.25
                    ? 1.4 + rng.nextDouble() : 0.4 + rng.nextDouble() * 0.8;
            if (isMiss) {
                aimX = attacksRight() ? 101 + rng.nextInt(3) : -1 - rng.nextInt(3);
                if (overTheBar) {
                    // Inside the goal mouth laterally — only the ARC beats it.
                    aimY = 45 + rng.nextInt(11);
                    shotPeak = 4.0 + rng.nextDouble() * 1.5;
                } else {
                    // Posts sit at y≈44.5/55.5 — a wide miss shaves the post
                    // by a boot's width, it doesn't hit the corner flag.
                    aimY = rng.nextBoolean() ? 38 + rng.nextInt(6) : 56 + rng.nextInt(6);
                    shotPeak = 0.5 + rng.nextDouble() * 1.7;
                }
            }
            add("SHOT", shooter, null, possessionTeamId(), x, y, aimX, aimY, 0);
            liftLast(shotPeak);

            switch (event.getEventType()) {
                case "goal" -> {
                    x = goalX; y = aimY;
                    add("GOAL", shooter, null, possessionTeamId(), x, y, x, y, 1200);
                    if (terminalIsLast) {
                        // Play flows on: the conceding side restarts from the centre.
                        t1HasBall = !t1HasBall;
                        x = 50; y = 50;
                        carrier = pickByZone(30);
                        add("KICKOFF", carrier, null, possessionTeamId(), x, y, x, y, 700);
                    }
                }
                case "shot_saved" -> {
                    PhasePlayer gk = pick(defenders(), p -> "GK".equals(p.role()));
                    x = attacksRight() ? 96 : 4; y = aimY;
                    add("SAVE", gk, null, defendingTeamId(), x, y, x, y, 800);
                    t1HasBall = !t1HasBall;
                    carrier = gk;
                    // The keeper HOLDS the ball for a beat (claims it), then
                    // distributes — a save never freezes play.
                    add("GK_HOLD", gk, null, possessionTeamId(), x, y, x, y, 900);
                    PhasePlayer outlet = pick(possession(), p -> p.role().startsWith("D"));
                    if (terminalIsLast && gk != null && outlet != null) {
                        double fromX = x, fromY = y;
                        setProgress(clamp(18 + rng.nextInt(14), 10, 40));
                        this.y = clamp(15 + rng.nextInt(71), 10, 90);
                        add("PASS", gk, outlet, possessionTeamId(), fromX, fromY, x, this.y, 0);
                        carrier = outlet;
                        // ...and the new attack rolls on: the save leads into
                        // real circulation, not a freeze-frame.
                        buildup(2 + rng.nextInt(2));
                    }
                }
                case "shot_wide" -> {
                    // The SHOT above already flew to the varied miss point.
                    x = aimX; y = aimY;
                    add("MISS", shooter, null, possessionTeamId(), x, y, x, y, 800);
                    t1HasBall = !t1HasBall;
                    // The ball ran out over the byline — restart with a goal kick
                    // sent long, so the miss reads as continuous play.
                    PhasePlayer keeper = pick(possession(), p -> "GK".equals(p.role()));
                    PhasePlayer target = pick(possession(), p -> p.role().startsWith("M")
                            || p.role().startsWith("D"));
                    if (terminalIsLast && keeper != null && target != null) {
                        setProgress(5); y = 44 + rng.nextInt(13);
                        double fromX = x, fromY = y;
                        setProgress(35 + rng.nextInt(20));
                        y = clamp(20 + rng.nextInt(61), 10, 90);
                        add("GOAL_KICK", keeper, target, possessionTeamId(), fromX, fromY, x, y, 0);
                        liftLast(3.5 + rng.nextDouble() * 1.5);
                        carrier = target;
                        buildup(1 + rng.nextInt(2));
                    } else {
                        carrier = keeper;
                    }
                }
                case "shot_blocked" -> {
                    PhasePlayer blocker = pickDefenderByZone();
                    setProgress(progress() - 4);
                    add("BLOCK", blocker, null, defendingTeamId(), x, y, x, y, 800);
                    if (terminalIsLast && rng.nextBoolean()) {
                        // The block ricochets out of play — throw-in, quick restart.
                        double outY = rng.nextBoolean() ? 0 : 100;
                        add("CLEARANCE", blocker, null, defendingTeamId(),
                                x, y, clamp(x + rng.nextInt(11) - 5, 3, 97), outY, 0);
                        x = clamp(x + rng.nextInt(11) - 5, 3, 97); y = outY;
                        PhasePlayer thrower = pickByZone(progress());
                        add("THROW_IN", thrower, null, possessionTeamId(), x, y, x, y, 800);
                        carrier = thrower;
                    } else {
                        // The attack recovers the rebound and recycles backwards.
                        PhasePlayer recoverer = pickByZone(Math.max(55, progress() - 8));
                        add("REBOUND", recoverer, null, possessionTeamId(), x, y, x, y, 500);
                        carrier = recoverer;
                        passTo(pickByZone(Math.max(20, progress() - 12)), "BACK_PASS",
                                -(6 + rng.nextInt(6)));
                    }
                }
                default -> { }
            }
        }

        void resolveCorner(LiveMatchMinute event) {
            ensurePossession(event.getTeamId());
            // A defender turns the attack behind for a corner.
            setProgress(clamp(Math.max(progress(), 80), 78, 94));
            PhasePlayer defender = pickDefenderByZone();
            double cornerY = rng.nextBoolean() ? 2 : 98;
            add("CLEARANCE", defender, null, defendingTeamId(),
                    x, y, toAbsoluteX(99), cornerY, 0);
            liftLast(1.5 + rng.nextDouble() * 1.2);
            setProgress(99); y = cornerY;
            PhasePlayer taker = bestPasser(possession());
            add("CORNER_KICK", taker, null, possessionTeamId(), x, y, x, y, 900);
            double boxX = toAbsoluteX(90), boxY = 42 + rng.nextInt(17);
            add("CROSS", taker, null, possessionTeamId(), x, y, boxX, boxY, 0);
            liftLast(2.4 + rng.nextDouble());
            x = boxX; y = boxY;
            if (rng.nextBoolean()) {
                PhasePlayer header = pick(possession(), p -> p.role().startsWith("ST")
                        || p.role().startsWith("AM") || p.role().startsWith("DC"));
                carrier = header != null ? header : taker;
                add("HEADER", carrier, null, possessionTeamId(), x, y, x, y, 700);
            } else {
                PhasePlayer clearer = pickDefenderByZone();
                setProgress(60 + rng.nextInt(15));
                add("CLEARANCE", clearer, null, defendingTeamId(), boxX, boxY, x, y, 0);
                liftLast(1.8 + rng.nextDouble() * 1.4);
                carrier = pickByZone(progress());
            }
        }

        void resolveOffside(LiveMatchMinute event) {
            ensurePossession(event.getTeamId());
            PhasePlayer runner = resolvePlayer(event, possession());
            setProgress(clamp(Math.max(progress(), 55), 50, 70));
            double fromX = x, fromY = y;
            // Faza B: geometric narration — the runner is played in BEYOND the
            // defending side's offside line at the moment of the pass, so the
            // flag visibly matches the line the assistants are tracking.
            double lineProgress = 100 - defensiveLineDepth();
            setProgress(clamp(lineProgress + 2 + rng.nextInt(6), 55, 95));
            y = clamp(y + rng.nextInt(21) - 10, 15, 85);
            add("THROUGH_BALL", carrier, runner, possessionTeamId(), fromX, fromY, x, y, 0);
            add("OFFSIDE_FLAG", runner, null, possessionTeamId(), x, y, x, y, 900);
            t1HasBall = !t1HasBall;
            carrier = pick(defenders(), p -> "GK".equals(p.role()));
        }

        void resolveFoul(LiveMatchMinute event) {
            // The fouling side is the event's team; the fouled side has the ball.
            long fouledTeamId = event.getTeamId() == ctx.teamId1() ? ctx.teamId2() : ctx.teamId1();
            ensurePossession(fouledTeamId);
            PhasePlayer fouler = resolvePlayer(event, defenders());
            String card = switch (event.getEventType()) {
                case "yellow_card" -> "YELLOW_CARD";
                case "red_card" -> "RED_CARD";
                default -> null;
            };

            // Legea avantajului: on a plain foul with the move still alive, the
            // referee waves play on — contact, no whistle, the fouled side
            // keeps rolling. Cards always stop play.
            if (card == null && rng.nextDouble() < 0.35) {
                add("FOUL", fouler, carrier, event.getTeamId(), x, y, x, y, 500);
                add("ADVANTAGE", carrier, null, possessionTeamId(), x, y, x, y, 600);
                buildup(2 + rng.nextInt(2));
                return;
            }

            add("FOUL", fouler, carrier, event.getTeamId(), x, y, x, y, 900);
            if (card != null) add(card, fouler, null, event.getTeamId(), x, y, x, y, 1100);

            // Attacking-half fouls restart as a real set piece: the free kick is
            // floated into the box and attacked — caught by the keeper or headed
            // clear — so the whistle leads into play instead of ending it.
            if (progress() > 52) {
                PhasePlayer taker = bestPasser(possession());
                add("FREE_KICK", taker, null, possessionTeamId(), x, y, x, y, 800);
                double boxX = toAbsoluteX(88 + rng.nextInt(5)), boxY = 38 + rng.nextInt(25);
                add("CROSS", taker, null, possessionTeamId(), x, y, boxX, boxY, 0);
                liftLast(2.6 + rng.nextDouble());
                x = boxX; y = boxY;
                if (rng.nextBoolean()) {
                    PhasePlayer header = pick(possession(), p -> p.role().startsWith("ST")
                            || p.role().startsWith("AM") || p.role().startsWith("DC"));
                    carrier = header != null ? header : taker;
                    double gkX = toAbsoluteX(96), gkY = 44 + rng.nextInt(13);
                    add("HEADER", carrier, null, possessionTeamId(), x, y, gkX, gkY, 0);
                    x = gkX; y = gkY;
                    PhasePlayer keeper = pick(defenders(), p -> "GK".equals(p.role()));
                    add("GK_HOLD", keeper, null, defendingTeamId(), x, y, x, y, 800);
                    t1HasBall = !t1HasBall;
                    carrier = keeper;
                } else {
                    PhasePlayer clearer = pickDefenderByZone();
                    setProgress(55 + rng.nextInt(15));
                    add("CLEARANCE", clearer, null, defendingTeamId(), boxX, boxY, x, y, 0);
                    carrier = pickByZone(progress());
                }
            }
        }

        /** Possession-only minutes: the chain dies on a tackle, an interception,
         *  or the ball running out for a throw-in. */
        void endWithTurnover() {
            double roll = rng.nextDouble();
            if (roll < cfg.getThrowInShare()) {
                double outY = rng.nextBoolean() ? 0 : 100;
                add("BALL_OUT", carrier, null, possessionTeamId(), x, y, x, outY, 0);
                y = outY;
                PhasePlayer thrower = pickByZone(progress());
                add("THROW_IN", thrower, null, possessionTeamId(), x, y, x, y, 800);
            } else {
                PhasePlayer taker = pickDefenderByZone();
                add(roll < cfg.getThrowInShare() + 0.4 ? "TACKLE" : "INTERCEPTION",
                        taker, carrier, defendingTeamId(), x, y, x, y, 600);
            }
        }

        // ---- helpers -----------------------------------------------------------

        /** Set the flight arc on the most recently added action (the ball
         *  travels a parabola peaking at this height mid-flight). */
        void liftLast(double peakHeight) {
            List<PhaseAction> actions = phase.getActions();
            if (!actions.isEmpty()) {
                actions.get(actions.size() - 1).setPeakHeight(round(peakHeight));
            }
        }

        /** Units the defending side's last line sits off their own goal line —
         *  it drops deeper as the ball advances toward their goal. */
        double defensiveLineDepth() {
            return clamp(38 - 0.25 * progress(), 12, 38);
        }

        /** Pick a carrier plausibly stationed around the given progress line,
         *  in the CURRENT lane — see the two-arg overload. */
        PhasePlayer pickByZone(double p) {
            return pickByZone(p, y);
        }

        /** Zone AND lane aware: a right-back is never summoned to the left
         *  wing — L-side roles stay off the right lane and vice versa (role
         *  side letters map to low/high y on both attack directions). */
        PhasePlayer pickByZone(double p, double laneY) {
            List<PhasePlayer> squad = possession();
            java.util.function.Predicate<PhasePlayer> zone;
            if (p < 20) zone = pl -> "GK".equals(pl.role()) || pl.role().startsWith("D");
            else if (p < 45) zone = pl -> pl.role().startsWith("D") || pl.role().startsWith("M");
            else if (p < 70) zone = pl -> pl.role().startsWith("M") || pl.role().startsWith("DM")
                    || pl.role().startsWith("AM");
            else zone = pl -> pl.role().startsWith("AM") || pl.role().startsWith("ST")
                    || pl.role().startsWith("M");
            java.util.function.Predicate<PhasePlayer> lane =
                    laneY < 38 ? pl -> !pl.role().endsWith("R")
                  : laneY > 62 ? pl -> !pl.role().endsWith("L")
                  : pl -> true;
            java.util.function.Predicate<PhasePlayer> notCarrier =
                    pl -> carrier == null || pl.id() != carrier.id();
            PhasePlayer picked = pick(squad, zone.and(lane).and(notCarrier));
            if (picked == null) picked = pick(squad, zone.and(notCarrier));
            return picked != null ? picked : pick(squad, pl -> true);
        }

        /** Approximate formation spot of a player, in the possession side's
         *  progress/lane space — used to keep every chosen actor PLAUSIBLY
         *  close to the ball, so the visual layer never has to summon a
         *  defender from 60 metres to make a touch. */
        double nominalProgress(PhasePlayer p) {
            String role = p.role();
            if ("GK".equals(role)) return 4;
            if (role.startsWith("DM")) return 32;
            if (role.startsWith("D")) return 18;
            if (role.startsWith("AM")) return 64;
            if (role.startsWith("M")) return 48;
            return 78;
        }

        /** Absolute y of the player's natural lane (mirrored when attacking
         *  left, matching the frontend's formation mirroring). */
        double laneYOf(PhasePlayer p) {
            double lane = p.role().endsWith("L") ? 22 : p.role().endsWith("R") ? 78 : 50;
            return attacksRight() ? lane : 100 - lane;
        }

        /** Attribute-best candidate DISCOUNTED by distance from the target
         *  spot — a slightly slower runner already in the right zone beats
         *  the fastest man on the far touchline. */
        PhasePlayer bestByNear(java.util.function.ToIntFunction<PhasePlayer> attribute,
                               double targetProgress, double targetY) {
            return possession().stream()
                    .filter(p -> !"GK".equals(p.role()))
                    .max(java.util.Comparator.comparingDouble(p ->
                            attribute.applyAsInt(p) * 1.5
                                    - Math.hypot(nominalProgress(p) - targetProgress,
                                                 laneYOf(p) - targetY) * 0.35))
                    .orElse(null);
        }

        /** A defender plausibly stationed near the CURRENT ball spot: zones
         *  are mirrored (their defence is our attack) and lanes mirrored. */
        PhasePlayer pickDefenderByZone() {
            double theirProgress = 100 - progress();
            java.util.function.Predicate<PhasePlayer> zone;
            if (theirProgress < 20) zone = pl -> pl.role().startsWith("D");
            else if (theirProgress < 45) zone = pl -> pl.role().startsWith("D") || pl.role().startsWith("M");
            else if (theirProgress < 70) zone = pl -> pl.role().startsWith("M") || pl.role().startsWith("AM");
            else zone = pl -> pl.role().startsWith("AM") || pl.role().startsWith("ST")
                    || pl.role().startsWith("M");
            boolean theyAttackRight = !attacksRight();
            java.util.function.Predicate<PhasePlayer> lane =
                    y < 38 ? (theyAttackRight ? pl -> !pl.role().endsWith("R") : pl -> !pl.role().endsWith("L"))
                  : y > 62 ? (theyAttackRight ? pl -> !pl.role().endsWith("L") : pl -> !pl.role().endsWith("R"))
                  : pl -> true;
            PhasePlayer picked = pick(defenders(), zone.and(lane).and(pl -> !"GK".equals(pl.role())));
            if (picked == null) picked = pick(defenders(), zone.and(pl -> !"GK".equals(pl.role())));
            return picked != null ? picked : pick(defenders(), pl -> !"GK".equals(pl.role()));
        }

        PhasePlayer pick(List<PhasePlayer> from, java.util.function.Predicate<PhasePlayer> filter) {
            List<PhasePlayer> eligible = from.stream().filter(filter).toList();
            if (eligible.isEmpty()) return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
            return eligible.get(rng.nextInt(eligible.size()));
        }

        PhasePlayer bestPasser(List<PhasePlayer> from) {
            return from.stream()
                    .filter(p -> !"GK".equals(p.role()))
                    .max(java.util.Comparator.comparingInt(p -> p.passing() + p.vision()))
                    .orElse(carrier);
        }

        /** Use the timeline event's player when they are on the given side. */
        PhasePlayer resolvePlayer(LiveMatchMinute event, List<PhasePlayer> side) {
            if (event.getPlayerId() != 0) {
                for (PhasePlayer p : side) {
                    if (p.id() == event.getPlayerId()) return p;
                }
                // Player known to the timeline but not on-pitch in our view
                // (e.g. subbed data race) — still credit them by identity.
                return new PhasePlayer(event.getPlayerId(), event.getPlayerName(), "ST",
                        10, 10, 10, 10, 10, 10, 10);
            }
            // No identity on the event: a FORWARD is the plausible shooter —
            // never a random fullback firing from his own half.
            PhasePlayer forward = pick(side,
                    p -> p.role().startsWith("ST") || p.role().startsWith("AM"));
            if (forward != null) return forward;
            PhasePlayer mid = pick(side, p -> p.role().startsWith("M"));
            if (mid != null) return mid;
            return pick(side, p -> !"GK".equals(p.role()));
        }

        void add(String type, PhasePlayer actor, PhasePlayer target, long teamId,
                 double fromX, double fromY, double toX, double toY, int fixedDurationMs) {
            if (actor == null) return;
            PhaseAction a = new PhaseAction();
            a.setType(type);
            a.setPlayerId(actor.id());
            a.setPlayerName(actor.name());
            if (target != null) {
                a.setTargetPlayerId(target.id());
                a.setTargetPlayerName(target.name());
            }
            a.setTeamId(teamId);
            a.setX(round(fromX)); a.setY(round(fromY));
            a.setEndX(round(toX)); a.setEndY(round(toY));
            int duration = fixedDurationMs > 0 ? fixedDurationMs
                    : (int) Math.min(cfg.getActionDurationMaxMs(),
                            cfg.getActionDurationBaseMs()
                                    + cfg.getActionDurationPerUnitMs()
                                            * Math.hypot(toX - fromX, toY - fromY));
            a.setDurationMs(Math.max(180, (int) (duration * paceFactor)));

            // Faza B: officials. The referee trails play on the diagonal and
            // walks to the spot for fouls/cards/penalties; each assistant
            // patrols one sideline half tracking that half's offside line.
            boolean atSpot = "FOUL".equals(type) || "YELLOW_CARD".equals(type)
                    || "RED_CARD".equals(type) || "PENALTY_KICK".equals(type);
            double dir = attacksRight() ? 1 : -1;
            a.setRefX(round(clamp(atSpot ? fromX : fromX - dir * 8, 4, 96)));
            a.setRefY(round(clamp(atSpot ? fromY + 2 : fromY + (50 - fromY) * 0.5, 8, 92)));
            double defLine = defensiveLineDepth();
            double restLine = 34;
            double leftLine = attacksRight() ? restLine : defLine;
            double rightLine = attacksRight() ? 100 - defLine : 100 - restLine;
            a.setAssistantBottomX(round(clamp(Math.min(leftLine, 50), 2, 50)));
            a.setAssistantTopX(round(clamp(Math.max(rightLine, 50), 50, 98)));

            phase.getActions().add(a);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
