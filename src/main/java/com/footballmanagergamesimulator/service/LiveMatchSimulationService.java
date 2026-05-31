package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.LiveMatchData.PlayerStaminaInfo;
import com.footballmanagergamesimulator.frontend.LiveMatchData.StaminaSnapshot;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class LiveMatchSimulationService {

    // Package-private so LiveMatchSession (sibling file) can read repos via
    // its `svc` reference without forcing public getters.
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    MatchEventRepository matchEventRepository;
    @Autowired
    GoalAnimationService goalAnimationService;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    MatchEngineConfig engineConfig;

    // Per-minute stamina drain for a "default" player (stamina=10, naturalFitness=10)
    // at a position with multiplier 1.0 lands around 0.35 stamina/min after
    // recovery, i.e. ~31 points over 90 minutes — close to the friendly-match
    // fitness loss range (7-14 ratio after the post-match dampening).
    // Base cost now sourced from MatchEngineConfig.stamina.baseCostPerMinute.
    static final int STAMINA_SNAPSHOT_INTERVAL = 5;

    private final Map<String, LiveMatchData> liveMatchCache = new ConcurrentHashMap<>();

    // Commentary templates
    static final String[] POSSESSION_COMMENTARY = {
            "%s controlling the tempo in midfield.",
            "%s keeping the ball well, probing for an opening.",
            "Patient build-up play from %s.",
            "%s passing it around confidently.",
            "Good movement off the ball by %s players.",
            "%s dominating possession in the middle third.",
            "Neat interplay between the %s midfielders.",
            "%s looking composed on the ball."
    };

    static final String[] BUILDUP_COMMENTARY = {
            "%s working the ball down the right flank.",
            "A switch of play from %s, moving it to the left side.",
            "%s pushing forward with intent.",
            "Good pressing from %s, forcing a turnover.",
            "The ball recycled back to the %s defence.",
            "%s looking to play on the counter-attack.",
            "A long ball forward by %s, but it's dealt with.",
            "%s patiently waiting for the right moment to strike."
    };

    static final String[] GOAL_DESCRIPTIONS = {
            "A clinical finish into the bottom corner!",
            "Powerful header from close range!",
            "A stunning long-range strike that flies into the top corner!",
            "Cool and composed finish, slotted past the keeper!",
            "Tap in from close range after a great cross!",
            "A brilliant volley that leaves the goalkeeper no chance!",
            "A curling effort that bends into the far post!",
            "One-on-one with the keeper, and it's buried!",
            "A free kick that dips over the wall and in!",
            "A penalty, confidently dispatched!"
    };

    static final String[] SAVE_DESCRIPTIONS = {
            "shoots but the goalkeeper makes a brilliant save!",
            "forces a good save from the keeper, diving to his right!",
            "fires a powerful shot, but it's straight at the goalkeeper!",
            "tries to curl one in, but the keeper is equal to it!",
            "heads towards goal, but the goalkeeper tips it over the bar!"
    };

    static final String[] MISS_DESCRIPTIONS = {
            "fires wide of the far post!",
            "blazes it over the crossbar!",
            "drags the shot wide from a good position.",
            "hits it just past the post! So close!",
            "skies the shot from inside the box.",
            "scuffs the shot and it rolls harmlessly wide."
    };

    static final String[] BLOCK_DESCRIPTIONS = {
            "'s shot is blocked by a brave defensive challenge!",
            " tries to shoot but it's blocked at the last second!",
            " lets fly but it deflects off a defender!",
            "'s effort is charged down by the defence!"
    };

    static final String[] FOUL_DESCRIPTIONS = {
            "brings down the opponent with a late challenge.",
            "commits a cynical foul to stop the counter-attack.",
            "clips the attacker's heels. Free kick.",
            "goes in too hard and concedes a foul.",
            "slides in and takes the man instead of the ball."
    };

    // ==================== PUBLIC API ====================

    public LiveMatchData simulateLiveMatch(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round) {
        return simulateLiveMatch(teamId1, teamId2, power1, power2, competitionId, season, round, true);
    }

    /**
     * Simulate a complete match in one call (legacy path — used everywhere
     * except the upcoming interactive endpoints). Internally creates a
     * {@link LiveMatchSession}, advances it straight to full time, builds the
     * DTO, and caches both the session and the result so the interactive
     * /state and /substitute endpoints can later read mid-match snapshots
     * (when those land in Session 2 of Faza 3).
     */
    public LiveMatchData simulateLiveMatch(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round,
            boolean generateGoalAnimations) {

        LiveMatchSession session = new LiveMatchSession(this,
                teamId1, teamId2, power1, power2,
                competitionId, season, round, generateGoalAnimations);
        session.advanceUntil(session.totalMinutes);

        LiveMatchData data = session.buildResult();
        String key = buildKey(competitionId, season, round, teamId1, teamId2);
        liveMatchCache.put(key, data);
        liveMatchSessions.put(key, session);
        return data;
    }


    public LiveMatchData getLiveMatchData(String key) {
        return liveMatchCache.get(key);
    }

    public LiveMatchData getLiveMatchData(long competitionId, int season, int round, long teamId1, long teamId2) {
        return liveMatchCache.get(buildKey(competitionId, season, round, teamId1, teamId2));
    }

    public static String buildKey(long competitionId, int season, int round, long teamId1, long teamId2) {
        return competitionId + "_" + season + "_" + round + "_" + teamId1 + "_" + teamId2;
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Pick a play type (open play / penalty / free kick) for a given outcome.
     * Centralised so the GOAL / SAVE / MISS branches can use the same type
     * for both the commentary prefix AND the animation. Penalty only fires
     * for non-MISS outcomes; MISS shots can still be free-kick attempts.
     *
     * <p>Distribution (non-miss): ~15% penalty, ~20% free kick, ~65% open play.
     * <p>Distribution (miss): ~35% free kick, ~65% open play.
     */
    String pickAttackPlayType(String outcome, Random random) {
        MatchEngineConfig.Live lv = engineConfig.getLive();
        double typeRoll = random.nextDouble();
        if (!"MISS".equals(outcome) && typeRoll < lv.getPenaltyShare()) return "PENALTY";
        if (typeRoll < lv.getFreeKickShare()) return "FREE_KICK";
        return "OPEN_PLAY";
    }

    /** Build the goal animation that matches the given play type + outcome. */
    GoalAnimationData buildAttackAnimation(
            List<Human> attackingAll, List<Human> defendingAll,
            Human attacker, Human assister,
            long attackingTeamId, long defendingTeamId,
            long homeTeamId, int minute, String outcome, String playType) {
        if ("PENALTY".equals(playType)) {
            return goalAnimationService.generatePenalty(
                    attackingAll, defendingAll, attacker,
                    attackingTeamId, defendingTeamId, homeTeamId, minute,
                    "GOAL".equals(outcome));
        }
        if ("FREE_KICK".equals(playType)) {
            return goalAnimationService.generateFreeKick(
                    attackingAll, defendingAll, attacker,
                    attackingTeamId, defendingTeamId, homeTeamId, minute, outcome);
        }
        return goalAnimationService.generate(
                attackingAll, defendingAll, attacker, assister,
                attackingTeamId, defendingTeamId, homeTeamId, minute, outcome);
    }

    /** Build the "PENALTY!" / "FREE KICK!" / "" prefix for a commentary line. */
    String playTypePrefix(String playType, String outcome) {
        if ("PENALTY".equals(playType)) {
            return switch (outcome) {
                case "GOAL" -> "PENALTY! ";
                case "SAVE" -> "PENALTY SAVED! ";
                case "MISS" -> "PENALTY MISSED! ";
                default     -> "PENALTY! ";
            };
        }
        if ("FREE_KICK".equals(playType)) {
            return switch (outcome) {
                case "GOAL" -> "FREE KICK GOAL! ";
                case "SAVE" -> "FREE KICK SAVED! ";
                case "MISS" -> "FREE KICK MISSED! ";
                default     -> "FREE KICK! ";
            };
        }
        return "";
    }

    /**
     * Big chances allocated to a team per match — biased by their share of total
     * power. A 75% share averages ~2.75 big chances; a 25% share averages ~1.25;
     * 50/50 averages ~2. Capped at 4 to stay close to real-world rates and to
     * stop goal counts ballooning above ~3/match average.
     */
    int computeBigChances(double rawRatio, Random random) {
        MatchEngineConfig.Live lv = engineConfig.getLive();
        double expected = lv.getBigChancesBaseline() + rawRatio * lv.getBigChancesScale();
        int floor = (int) Math.floor(expected);
        double frac = expected - floor;
        int extra = random.nextDouble() < frac ? 1 : 0;
        return Math.max(0, Math.min(lv.getBigChancesHardCap(), floor + extra));
    }

    /** Pick {@code count} distinct minutes in [minMinute, maxMinute]. */
    Set<Integer> pickRandomMinutes(int count, int minMinute, int maxMinute, Random random) {
        Set<Integer> minutes = new LinkedHashSet<>();
        int range = maxMinute - minMinute + 1;
        int guard = 0;
        while (minutes.size() < count && guard++ < count * 10) {
            minutes.add(minMinute + random.nextInt(range));
        }
        return minutes;
    }

    List<Human> getOutfieldPlayers(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, 1L).stream()
                .filter(h -> !h.isRetired())
                .filter(h -> !"GK".equals(h.getPosition()))
                .collect(Collectors.toList());
    }

    /**
     * Pick an attacker weighted by rating and position. When a stamina state
     * map is available, weight is also scaled by current stamina — a tired
     * striker (40% fitness) is roughly half as likely to be picked as he would
     * be fresh, which both spreads goals around and makes late-match misses
     * land on fatigued players more often.
     */
    Human pickWeightedAttacker(List<Human> players,
                                       Map<Long, PlayerMatchState> states,
                                       Random random) {
        if (players.isEmpty()) return null;
        if (players.size() == 1) return players.get(0);

        double totalWeight = 0;
        double[] weights = new double[players.size()];
        for (int i = 0; i < players.size(); i++) {
            Human p = players.get(i);
            double posWeight = getPositionWeight(p.getPosition());
            double stamFactor = 1.0;
            double paceFactor = 1.0;
            if (states != null) {
                PlayerMatchState st = states.get(p.getId());
                if (st != null) {
                    stamFactor = staminaFactor(st.currentStamina);
                    // Pace tilts the shot toward quicker attackers (config-controlled curve).
                    MatchEngineConfig.Live lv = engineConfig.getLive();
                    paceFactor = lv.getAttackerPaceFloor() + lv.getAttackerPaceRange() * (st.pace / 20.0);
                }
            }
            weights[i] = p.getRating() * posWeight * stamFactor * paceFactor;
            totalWeight += weights[i];
        }

        if (totalWeight <= 0) return players.get(random.nextInt(players.size()));
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < players.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) return players.get(i);
        }
        return players.get(players.size() - 1);
    }

    private double getPositionWeight(String position) {
        if (position == null) return 1.0;
        return switch (position) {
            case "ST" -> 3.0;
            case "AMC", "AML", "AMR" -> 2.0;
            case "MC", "ML", "MR" -> 1.2;
            case "DC", "DL", "DR", "DM" -> 0.4;
            default -> 1.0;
        };
    }

    /** Pick the defender who commits the foul. Weighted inverse to pace —
     *  a slow defender is more likely to mistime a challenge than a quick one
     *  who can recover cleanly. Falls back to uniform when no states given. */
    Human pickFouler(List<Human> defenders,
                              Map<Long, PlayerMatchState> states,
                              Random random) {
        if (defenders.isEmpty()) return null;
        if (defenders.size() == 1) return defenders.get(0);
        if (states == null) return defenders.get(random.nextInt(defenders.size()));

        double total = 0;
        double[] weights = new double[defenders.size()];
        for (int i = 0; i < defenders.size(); i++) {
            PlayerMatchState st = states.get(defenders.get(i).getId());
            int pace = (st != null) ? st.pace : 10;
            // pace 20 → low weight (rarely fouls); pace 1 → high weight. Base from config.
            weights[i] = Math.max(0.1, engineConfig.getLive().getFoulerPaceInverseBase() - pace / 20.0);
            total += weights[i];
        }
        if (total <= 0) return defenders.get(random.nextInt(defenders.size()));
        double roll = random.nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < defenders.size(); i++) {
            cum += weights[i];
            if (roll < cum) return defenders.get(i);
        }
        return defenders.get(defenders.size() - 1);
    }

    Human pickDifferentPlayer(List<Human> players, Human exclude, Random random) {
        List<Human> candidates = players.stream()
                .filter(p -> p.getId() != exclude.getId())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    LiveMatchMinute createMinuteEvent(int minute, int homeScore, int awayScore,
                                               String eventType, String commentary,
                                               long playerId, String playerName,
                                               long teamId, String teamName) {
        LiveMatchMinute event = new LiveMatchMinute();
        event.setMinute(minute);
        event.setHomeScore(homeScore);
        event.setAwayScore(awayScore);
        event.setEventType(eventType);
        event.setCommentary(commentary);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setTeamId(teamId);
        event.setTeamName(teamName);
        return event;
    }

    MatchEvent buildMatchEvent(long competitionId, int season, int round,
                                        long teamId1, long teamId2,
                                        int minute, String eventType,
                                        long playerId, String playerName,
                                        long teamId, String details) {
        MatchEvent event = new MatchEvent();
        event.setCompetitionId(competitionId);
        event.setSeasonNumber(season);
        event.setRoundNumber(round);
        event.setTeamId1(teamId1);
        event.setTeamId2(teamId2);
        event.setMinute(minute);
        event.setEventType(eventType);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setTeamId(teamId);
        event.setDetails(details);
        return event;
    }

    // ==================== STAMINA MODEL (Faza 1) ====================

    /**
     * Batch-load PlayerSkills for both squads so we read the DB once instead
     * of N times in {@link #initializeMatchStates}.
     */
    Map<Long, PlayerSkills> loadSkillsCache(List<Human> teamA, List<Human> teamB) {
        List<Long> ids = new ArrayList<>(teamA.size() + teamB.size());
        for (Human p : teamA) ids.add(p.getId());
        for (Human p : teamB) ids.add(p.getId());
        if (ids.isEmpty()) return Collections.emptyMap();
        return playerSkillsRepository.findAllByPlayerIdIn(ids).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, ps -> ps, (a, b) -> a));
    }

    /**
     * Seed PlayerMatchState entries for a squad. Picks the starting XI as:
     * top-rated GK + top 10 outfield by rating. Squads with &lt; 11 players
     * have everyone on the pitch.
     */
    void initializeMatchStates(Map<Long, PlayerMatchState> states,
                                       List<Human> squad,
                                       Map<Long, PlayerSkills> skillsCache) {
        Set<Long> startingXi = chooseStartingEleven(squad);
        for (Human p : squad) {
            PlayerMatchState s = new PlayerMatchState();
            s.playerId = p.getId();
            s.position = p.getPosition();
            s.name = p.getName();
            // Start from current fitness, clamped — a knackered player still
            // shows up but with reduced stamina ceiling.
            MatchEngineConfig.Stamina stCfg = engineConfig.getStamina();
            double startCondition = Math.max(stCfg.getStartStaminaMin(),
                    Math.min(stCfg.getStartStaminaMax(), p.getFitness()));
            s.startFitness = p.getFitness();
            s.currentStamina = startCondition;
            s.minutesPlayed = 0;
            s.isOnPitch = startingXi.contains(p.getId());
            PlayerSkills ps = skillsCache.get(p.getId());
            s.staminaAttr = ps != null ? Math.max(1, ps.getStamina()) : 10;
            s.naturalFitness = ps != null ? Math.max(1, ps.getNaturalFitness()) : 10;
            s.pace = ps != null ? Math.max(1, ps.getPace()) : 10;
            states.put(p.getId(), s);
        }
    }

    private Set<Long> chooseStartingEleven(List<Human> squad) {
        Set<Long> picked = new HashSet<>();
        if (squad.size() <= 11) {
            for (Human p : squad) picked.add(p.getId());
            return picked;
        }
        Human gk = squad.stream()
                .filter(p -> "GK".equals(p.getPosition()))
                .max(Comparator.comparingDouble(Human::getRating))
                .orElse(null);
        if (gk != null) picked.add(gk.getId());
        squad.stream()
                .filter(p -> !"GK".equals(p.getPosition()))
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .limit(11 - picked.size())
                .forEach(p -> picked.add(p.getId()));
        return picked;
    }

    /** Apply one minute of stamina drain to every on-pitch player. Tempo
     *  multipliers per team modulate cost — High tempo drains faster, Low
     *  drains slower; the extra cost imposed by High tempo is discounted for
     *  pacy players (so a quick striker survives a high-tempo match better
     *  than a plodder at the same tempo). */
    void applyStaminaTick(Map<Long, PlayerMatchState> states,
                                   Set<Long> team1Ids, Set<Long> team2Ids,
                                   double tempoMult1, double tempoMult2) {
        MatchEngineConfig.Stamina stCfg = engineConfig.getStamina();
        double baseCost = stCfg.getBaseCostPerMinute();
        double minAttrMult = stCfg.getMinAttributeMultiplier();
        double paceDiscount = stCfg.getPaceDiscountOnTempo();
        double recoveryMax = stCfg.getNaturalFitnessRecoveryMax();
        for (PlayerMatchState s : states.values()) {
            if (!s.isOnPitch) continue;
            s.minutesPlayed++;
            double posMult = positionStaminaMultiplier(s.position);
            // Stamina attribute scales cost: high stamina → cost barely tires.
            double attrMult = Math.max(minAttrMult, 1.2 - s.staminaAttr / 20.0);
            double cost = baseCost * posMult * attrMult;

            double teamTempo = team1Ids.contains(s.playerId) ? tempoMult1
                             : team2Ids.contains(s.playerId) ? tempoMult2 : 1.0;
            double extra = cost * (teamTempo - 1.0);
            // Fast players absorb high-tempo cost better (config-controlled discount).
            if (extra > 0) extra *= (1.0 - paceDiscount * (s.pace / 20.0));
            cost += extra;

            // Natural fitness gives a small per-minute recovery.
            double recovery = (s.naturalFitness / 20.0) * recoveryMax;
            double netLoss = Math.max(0.0, cost - recovery);
            s.currentStamina = Math.max(0.0, s.currentStamina - netLoss);
        }
    }

    /** "Low" / "Standard" / "High" → configured multipliers. Null-safe. */
    double tempoMultiplier(String tempo) {
        MatchEngineConfig.Tactic t = engineConfig.getTactic();
        if ("High".equalsIgnoreCase(tempo)) return t.getTempoHighMultiplier();
        if ("Low".equalsIgnoreCase(tempo)) return t.getTempoLowMultiplier();
        return 1.0;
    }

    /** Attack-chance multiplier for a team based on how many of its players
     *  are on the pitch. 11+ = no penalty; each missing man trims attacking
     *  output sharply once a team is down to 9. */
    double manAdvantageAttackMultiplier(int onPitch) {
        MatchEngineConfig.Tactic t = engineConfig.getTactic();
        if (onPitch >= 11) return t.getManAdvantageAttackEleven();
        if (onPitch == 10) return t.getManAdvantageAttackTen();
        if (onPitch == 9)  return t.getManAdvantageAttackNine();
        return t.getManAdvantageAttackEight();
    }

    private double positionStaminaMultiplier(String pos) {
        if (pos == null) return 1.0;
        MatchEngineConfig.Stamina st = engineConfig.getStamina();
        return switch (pos) {
            case "GK" -> st.getPositionGoalkeeper();
            case "DC", "DL", "DR" -> st.getPositionDefender();
            case "DM" -> st.getPositionDefMid();
            case "MC", "ML", "MR" -> st.getPositionMidfielder();
            case "AMC", "AML", "AMR" -> st.getPositionAttMid();
            case "ST" -> st.getPositionStriker();
            default -> 1.0;
        };
    }

    /**
     * Map a stamina value (0..100) onto a weight multiplier (0.5..1.0). Even
     * an exhausted player keeps half his "natural" chance — we don't want a
     * 35-fitness striker to never be picked at all, just less often.
     */
    private double staminaFactor(double currentStamina) {
        double s = Math.max(0.0, Math.min(100.0, currentStamina));
        MatchEngineConfig.Stamina st = engineConfig.getStamina();
        return st.getStaminaPickFloor() + st.getStaminaPickRange() * (s / 100.0);
    }

    /** Drop bench players from a pool. Returns a new list. */
    List<Human> filterOnPitch(List<Human> pool, Map<Long, PlayerMatchState> states) {
        if (states == null || states.isEmpty()) return pool;
        List<Human> out = new ArrayList<>(pool.size());
        for (Human h : pool) {
            PlayerMatchState s = states.get(h.getId());
            if (s == null || s.isOnPitch) out.add(h);
        }
        return out;
    }

    /**
     * Swap an on-pitch player for one from the bench. The picking strategy
     * depends on the reason:
     * <ul>
     *   <li>FATIGUE — most fatigued non-GK off; freshest same-group bench player on.</li>
     *   <li>OFFENSIVE — lowest-rated defensive starter off; best attacking bench player on.</li>
     *   <li>DEFENSIVE — most-fatigued attacking starter off; freshest defender on.</li>
     * </ul>
     * Returns null when no eligible pair exists.
     */
    SubSwap performSub(Map<Long, PlayerMatchState> states, List<Human> squad, SubReason reason) {
        Set<Long> squadIds = squad.stream().map(Human::getId).collect(Collectors.toSet());

        PlayerMatchState off;
        PlayerMatchState on;
        switch (reason) {
            case OFFENSIVE -> {
                off = pickFromPitch(states, squadIds, true, this::isDefensivePosition,
                        Comparator.comparingDouble(this::playerRating));
                if (off == null) return null;
                on = pickFromBench(states, squadIds, true, this::isAttackingPosition,
                        Comparator.comparingDouble((PlayerMatchState s) -> playerRating(s)).reversed());
                if (on == null) return null;
            }
            case DEFENSIVE -> {
                off = pickFromPitch(states, squadIds, true, this::isAttackingPosition,
                        Comparator.comparingDouble(s -> s.currentStamina));
                if (off == null) return null;
                on = pickFromBench(states, squadIds, true, this::isDefensivePosition,
                        Comparator.comparingDouble((PlayerMatchState s) -> s.currentStamina).reversed());
                if (on == null) return null;
            }
            case FATIGUE -> {
                off = pickFromPitch(states, squadIds, true, p -> true,
                        Comparator.comparingDouble(s -> s.currentStamina));
                if (off == null) return null;
                String targetGroup = positionGroup(off.position);
                on = pickFromBench(states, squadIds, true,
                        pos -> targetGroup.equals(positionGroup(pos)),
                        Comparator.comparingDouble((PlayerMatchState s) -> s.currentStamina).reversed());
                if (on == null) {
                    // Fallback — no same-group sub available, take the freshest
                    // non-GK bench player rather than skipping the swap entirely.
                    on = pickFromBench(states, squadIds, true, p -> true,
                            Comparator.comparingDouble((PlayerMatchState s) -> s.currentStamina).reversed());
                }
                if (on == null) return null;
            }
            default -> { return null; }
        }

        off.isOnPitch = false;
        on.isOnPitch = true;

        Human offHuman = findById(squad, off.playerId);
        Human onHuman = findById(squad, on.playerId);
        if (offHuman == null || onHuman == null) return null;
        return new SubSwap(offHuman, onHuman);
    }

    /**
     * Pick the highest-priority on-pitch state matching a position filter. The
     * non-GK guard is opt-in via excludeGk.
     */
    private PlayerMatchState pickFromPitch(Map<Long, PlayerMatchState> states, Set<Long> squadIds,
                                           boolean excludeGk,
                                           java.util.function.Predicate<String> positionMatch,
                                           Comparator<PlayerMatchState> ranking) {
        return states.values().stream()
                .filter(s -> squadIds.contains(s.playerId))
                .filter(s -> s.isOnPitch)
                .filter(s -> !excludeGk || !"GK".equals(s.position))
                .filter(s -> positionMatch.test(s.position))
                .min(ranking)
                .orElse(null);
    }

    private PlayerMatchState pickFromBench(Map<Long, PlayerMatchState> states, Set<Long> squadIds,
                                           boolean excludeGk,
                                           java.util.function.Predicate<String> positionMatch,
                                           Comparator<PlayerMatchState> ranking) {
        return states.values().stream()
                .filter(s -> squadIds.contains(s.playerId))
                .filter(s -> !s.isOnPitch)
                .filter(s -> excludeGk ? !"GK".equals(s.position) : true)
                .filter(s -> positionMatch.test(s.position))
                .min(ranking)
                .orElse(null);
    }

    private double playerRating(PlayerMatchState s) {
        // We don't carry the rating on the state, but stamina + position is a
        // close enough proxy when comparing similar roles; for cross-role
        // comparisons the SubReason already restricts the candidate pool.
        // Use currentStamina as the secondary sort key for offensive picks
        // (lower stamina defender → off; higher stamina attacker → on).
        return s.currentStamina;
    }

    private boolean isDefensivePosition(String pos) {
        if (pos == null) return false;
        return pos.startsWith("D") || "DM".equals(pos);
    }

    private boolean isAttackingPosition(String pos) {
        if (pos == null) return false;
        return "ST".equals(pos) || pos.startsWith("AM");
    }

    /**
     * Decide what kind of substitution the AI should attempt this minute. The
     * thresholds mean: don't sub before min 35, then progressively lower the
     * "tired enough to come off" bar as the match wears on. OFFENSIVE and
     * DEFENSIVE only fire late.
     */
    SubReason decideSubReason(int min, int ownScore, int oppScore,
                                       Map<Long, PlayerMatchState> states,
                                       Set<Long> teamIds) {
        MatchEngineConfig.Live lv = engineConfig.getLive();
        if (min < lv.getSubEarliestMinute()) return null;
        int diff = ownScore - oppScore;
        if (min >= lv.getSubOffensiveMinute() && diff <= lv.getSubOffensiveGoalDiff()) return SubReason.OFFENSIVE;
        if (min >= lv.getSubDefensiveMinute() && diff >= lv.getSubDefensiveGoalDiff()) return SubReason.DEFENSIVE;

        double threshold = staminaSubThreshold(min);
        boolean anyTired = states.values().stream()
                .filter(s -> teamIds.contains(s.playerId))
                .filter(s -> s.isOnPitch && !"GK".equals(s.position))
                .anyMatch(s -> s.currentStamina < threshold);
        return anyTired ? SubReason.FATIGUE : null;
    }

    /**
     * Stamina value below which a fatigue sub is considered. Tighter early
     * (only very tired players come off), looser late (managers happily sub
     * anyone slowed down in the final 10 minutes).
     */
    private double staminaSubThreshold(int min) {
        MatchEngineConfig.Live lv = engineConfig.getLive();
        if (min < lv.getSubEarliestMinute()) return 0;
        if (min < lv.getSubMinuteBoundary2()) return lv.getSubStaminaPhase2();
        if (min < lv.getSubMinuteBoundary3()) return lv.getSubStaminaPhase3();
        if (min < lv.getSubMinuteBoundary4()) return lv.getSubStaminaPhase4();
        return lv.getSubStaminaPhase5();
    }

    /** Build a short, reason-flavoured commentary line for a substitution. */
    String buildSubCommentary(String teamName, SubSwap swap, SubReason reason) {
        return switch (reason) {
            case OFFENSIVE -> teamName + " push for it — " + swap.on.getName()
                    + " comes on for " + swap.off.getName() + ".";
            case DEFENSIVE -> teamName + " shore things up: " + swap.off.getName()
                    + " off, " + swap.on.getName() + " on.";
            case FATIGUE -> swap.off.getName() + " looks spent. "
                    + swap.on.getName() + " replaces him for " + teamName + ".";
            case MANUAL -> "Manager's call for " + teamName + ": "
                    + swap.off.getName() + " comes off, " + swap.on.getName() + " comes on.";
        };
    }

    Human findById(List<Human> squad, long id) {
        for (Human h : squad) if (h.getId() == id) return h;
        return null;
    }

    private String positionGroup(String pos) {
        if (pos == null) return "?";
        if ("GK".equals(pos)) return "GK";
        if (pos.startsWith("D")) return "D";
        if (pos.startsWith("AM") || pos.startsWith("M")) return "M";
        return "A";
    }

    StaminaSnapshot captureStaminaSnapshot(int minute,
                                                   Map<Long, PlayerMatchState> states,
                                                   Set<Long> homeIds,
                                                   Set<Long> awayIds) {
        StaminaSnapshot snap = new StaminaSnapshot();
        snap.setMinute(minute);
        snap.setHomePlayers(buildPlayerStaminaList(states, homeIds));
        snap.setAwayPlayers(buildPlayerStaminaList(states, awayIds));
        return snap;
    }

    private List<PlayerStaminaInfo> buildPlayerStaminaList(Map<Long, PlayerMatchState> states,
                                                            Set<Long> teamIds) {
        List<PlayerStaminaInfo> out = new ArrayList<>();
        for (PlayerMatchState s : states.values()) {
            if (!teamIds.contains(s.playerId)) continue;
            if (!s.isOnPitch && s.minutesPlayed == 0) continue; // skip never-used bench
            PlayerStaminaInfo info = new PlayerStaminaInfo();
            info.setPlayerId(s.playerId);
            info.setName(s.name);
            info.setPosition(s.position);
            info.setStamina((int) Math.round(s.currentStamina));
            info.setMinutesPlayed(s.minutesPlayed);
            info.setOnPitch(s.isOnPitch);
            info.setYellowCardMinute(s.yellowCardMinute);
            info.setRedCardMinute(s.redCardMinute);
            out.add(info);
        }
        return out;
    }

    /**
     * Write the post-match condition back to Human.fitness. Players who never
     * came on are left untouched. The drop is proportional to stamina burned,
     * dampened so a single match doesn't drain a player below 20%.
     */
    void persistPostMatchFitness(Map<Long, PlayerMatchState> states,
                                         List<Human> teamA, List<Human> teamB) {
        Map<Long, Human> byId = new HashMap<>();
        for (Human p : teamA) byId.put(p.getId(), p);
        for (Human p : teamB) byId.put(p.getId(), p);

        List<Human> toSave = new ArrayList<>();
        for (PlayerMatchState s : states.values()) {
            if (s.minutesPlayed == 0) continue;
            Human p = byId.get(s.playerId);
            if (p == null) continue;
            MatchEngineConfig.Stamina stCfg = engineConfig.getStamina();
            double drop = Math.max(0.0, s.startFitness - s.currentStamina);
            // Dampen — the in-match stamina value is "right now"; the
            // multi-day recovery between matches softens that.
            double fitnessLoss = drop * stCfg.getPostMatchDampening();
            double newFitness = Math.max(stCfg.getPostMatchFloor(), p.getFitness() - fitnessLoss);
            p.setFitness(newFitness);
            toSave.add(p);
        }
        if (!toSave.isEmpty()) humanRepository.saveAll(toSave);
    }

    // ==================== INNER TYPES ====================

    /** Per-player state tracked for the duration of a single live match. */
    static class PlayerMatchState {
        long playerId;
        String position;
        String name;
        double startFitness;
        double currentStamina;
        int minutesPlayed;
        boolean isOnPitch;
        int staminaAttr;       // 1-20
        int naturalFitness;    // 1-20
        int pace;              // 1-20
        /** Minute when this player picked up a yellow card; 0 = never. */
        int yellowCardMinute;
        /** Minute when this player was sent off with a red; 0 = never. */
        int redCardMinute;
    }

    /** Result of a substitution: the player who came off and the player who came on. */
    static class SubSwap {
        final Human off;
        final Human on;
        SubSwap(Human off, Human on) { this.off = off; this.on = on; }
    }

    /** Why the AI is making this substitution — drives picking strategy. */
    enum SubReason { FATIGUE, OFFENSIVE, DEFENSIVE, MANUAL }

    /** Thrown by {@link LiveMatchSession#applyUserSub} when the request is
     *  invalid (subs exhausted, players not on pitch/bench, GK swap rules).
     *  Controller layer maps this to a 400 with the message. */
    public static class InvalidSubstitutionException extends RuntimeException {
        public InvalidSubstitutionException(String msg) { super(msg); }
    }

    // ==================== LIVE MATCH SESSION (tickable engine) ====================

    /** Cache of in-flight sessions keyed by match key. Session 2 wires the
     *  /advance + /substitute endpoints against this map. */
    private final Map<String, LiveMatchSession> liveMatchSessions = new ConcurrentHashMap<>();

    public LiveMatchSession getSession(String key) {
        return liveMatchSessions.get(key);
    }

    /**
     * Find a session for a given (competition, season, matchday, teamId)
     * tuple — used by GameAdvanceService to detect live matches WITHOUT
     * relying on the CompetitionTeamInfoDetail row (which doesn't exist yet
     * for interactive matches, since the detail is written on /commit).
     */
    public LiveMatchSession findSessionForTeam(long competitionId, int season, int matchday, long teamId) {
        for (LiveMatchSession s : liveMatchSessions.values()) {
            if (s.getCompetitionId() == competitionId
                    && s.getSeason() == season
                    && s.getRound() == matchday
                    && (s.getTeamId1() == teamId || s.getTeamId2() == teamId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Find any uncommitted live session involving the given team — used by the
     * day-advance safety net to detect a match the user abandoned (e.g. by
     * refreshing the browser mid-match). If something turns up, the advance
     * pauses with the live-match signal so the frontend resumes the modal
     * instead of silently rolling past it with no result.
     */
    public LiveMatchSession findAnyUncommittedSessionForTeam(long teamId) {
        for (LiveMatchSession s : liveMatchSessions.values()) {
            if (s.isCommitted()) continue;
            if (s.getTeamId1() == teamId || s.getTeamId2() == teamId) return s;
        }
        return null;
    }

    /**
     * Build a session for interactive (deferred-commit) mode. The session is
     * created with all setup done (squads, stamina init, big-chance schedule,
     * kickoff event) but the engine has NOT advanced — currentMinute is 0.
     *
     * <p>The frontend polls {@code /advance} to drive ticks. When it sees
     * {@code finished=true}, it calls {@code /commit} to run the post-match
     * work (scorers, standings, suspensions, news, post-match PC).
     */
    public LiveMatchSession createInteractiveSession(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round,
            boolean generateGoalAnimations) {
        return createInteractiveSession(teamId1, teamId2, power1, power2,
                competitionId, season, round, generateGoalAnimations, null);
    }

    /**
     * Two-axis variant: when {@code matchup} is non-null the live engine derives possession + attack
     * chances from the attack-vs-defense matchup (production model) instead of the scalar power ratio.
     */
    public LiveMatchSession createInteractiveSession(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round,
            boolean generateGoalAnimations,
            TacticalScoreService.Matchup matchup) {
        return createInteractiveSession(teamId1, teamId2, power1, power2,
                competitionId, season, round, generateGoalAnimations, matchup, -1, -1);
    }

    /**
     * Engine-unification variant: the live narration is pinned to a predetermined
     * scoreline ({@code targetHomeGoals}/{@code targetAwayGoals}) — the exact
     * result the instant two-axis engine produces for this matchup. The session
     * pre-schedules that many goal minutes per team and forces/caps goals to
     * those minutes, so the live final score equals the instant one while every
     * other live element (possession, shots, cards, subs, commentary) stays live.
     * Pass {@code -1, -1} for legacy stochastic mode (no pinning).
     */
    public LiveMatchSession createInteractiveSession(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round,
            boolean generateGoalAnimations,
            TacticalScoreService.Matchup matchup,
            int targetHomeGoals, int targetAwayGoals) {
        LiveMatchSession session = new LiveMatchSession(this,
                teamId1, teamId2, power1, power2,
                competitionId, season, round, generateGoalAnimations, matchup, new Random(),
                targetHomeGoals, targetAwayGoals);
        String key = buildKey(competitionId, season, round, teamId1, teamId2);
        liveMatchSessions.put(key, session);
        // Also seed liveMatchCache with the initial snapshot so legacy
        // /match/live/{key} reads return the kickoff state.
        liveMatchCache.put(key, session.snapshot());
        return session;
    }

}
