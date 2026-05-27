package com.footballmanagergamesimulator.service;

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

    // --- Stamina model tuning (Faza 1) ---
    // Per-minute stamina drain for a "default" player (stamina=10, naturalFitness=10)
    // at a position with multiplier 1.0 lands around 0.35 stamina/min after
    // recovery, i.e. ~31 points over 90 minutes — close to the friendly-match
    // fitness loss range (7-14 ratio after the post-match dampening).
    static final double STAMINA_BASE_COST = 0.5;
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

        LiveMatchSession session = new LiveMatchSession(
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
        double typeRoll = random.nextDouble();
        if (!"MISS".equals(outcome) && typeRoll < 0.15) return "PENALTY";
        if (typeRoll < 0.35) return "FREE_KICK";
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
        double expected = 0.5 + rawRatio * 3.0;        // 0.5→2.0 ; 1.0→3.5 ; 0.25→1.25
        int floor = (int) Math.floor(expected);
        double frac = expected - floor;
        int extra = random.nextDouble() < frac ? 1 : 0;
        return Math.max(0, Math.min(4, floor + extra));
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
                    // Pace tilts the shot toward quicker attackers: pace 20 →
                    // ×1.2, pace 10 → ×1.0, pace 1 → ~×0.82.
                    paceFactor = 0.8 + 0.4 * (st.pace / 20.0);
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
            // pace 20 → 0.2 (rarely fouls); pace 10 → 0.7; pace 1 → 1.15.
            weights[i] = Math.max(0.1, 1.2 - pace / 20.0);
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
            double startCondition = Math.max(20.0, Math.min(100.0, p.getFitness()));
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
        for (PlayerMatchState s : states.values()) {
            if (!s.isOnPitch) continue;
            s.minutesPlayed++;
            double posMult = positionStaminaMultiplier(s.position);
            // Stamina attribute scales cost: stamina=20 → cost × 0.2 (barely
            // tires); stamina=10 → × 0.7; stamina=5 → × 0.95.
            double attrMult = Math.max(0.15, 1.2 - s.staminaAttr / 20.0);
            double cost = STAMINA_BASE_COST * posMult * attrMult;

            double teamTempo = team1Ids.contains(s.playerId) ? tempoMult1
                             : team2Ids.contains(s.playerId) ? tempoMult2 : 1.0;
            double extra = cost * (teamTempo - 1.0);
            // Fast players absorb high-tempo cost better: pace 20 → 50% of the
            // extra cost waived; pace 10 → 25%; pace 1 → ~2%. Low-tempo
            // discount applies uniformly (no pace effect when extra < 0).
            if (extra > 0) extra *= (1.0 - 0.5 * (s.pace / 20.0));
            cost += extra;

            // Natural fitness gives a small per-minute recovery — helps elite
            // athletes sustain late in the match.
            double recovery = (s.naturalFitness / 20.0) * 0.15;
            double netLoss = Math.max(0.0, cost - recovery);
            s.currentStamina = Math.max(0.0, s.currentStamina - netLoss);
        }
    }

    /** "Low" / "Standard" / "High" → 0.85 / 1.0 / 1.25. Null-safe. */
    static double tempoMultiplier(String tempo) {
        if ("High".equalsIgnoreCase(tempo)) return 1.25;
        if ("Low".equalsIgnoreCase(tempo)) return 0.85;
        return 1.0;
    }

    /** Attack-chance multiplier for a team based on how many of its players
     *  are on the pitch. 11+ = no penalty; each missing man trims attacking
     *  output sharply once a team is down to 9. */
    static double manAdvantageAttackMultiplier(int onPitch) {
        if (onPitch >= 11) return 1.0;
        if (onPitch == 10) return 0.7;
        if (onPitch == 9)  return 0.5;
        return 0.35;
    }

    private double positionStaminaMultiplier(String pos) {
        if (pos == null) return 1.0;
        return switch (pos) {
            case "GK" -> 0.4;
            case "DC", "DL", "DR" -> 0.75;
            case "DM" -> 1.0;
            case "MC", "ML", "MR" -> 1.15;
            case "AMC", "AML", "AMR" -> 1.05;
            case "ST" -> 0.85;
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
        return 0.5 + 0.5 * (s / 100.0);
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
        if (min < 35) return null;
        int diff = ownScore - oppScore;
        if (min >= 75 && diff <= -2) return SubReason.OFFENSIVE;
        if (min >= 80 && diff >= 1) return SubReason.DEFENSIVE;

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
        if (min < 35) return 0;
        if (min < 55) return 60;
        if (min < 70) return 70;
        if (min < 80) return 78;
        return 85;
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
            double drop = Math.max(0.0, s.startFitness - s.currentStamina);
            // Dampen — the in-match stamina value is "right now"; the
            // multi-day recovery between matches softens that.
            double fitnessLoss = drop * 0.7;
            double newFitness = Math.max(20.0, p.getFitness() - fitnessLoss);
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
        LiveMatchSession session = new LiveMatchSession(
                teamId1, teamId2, power1, power2,
                competitionId, season, round, generateGoalAnimations);
        String key = buildKey(competitionId, season, round, teamId1, teamId2);
        liveMatchSessions.put(key, session);
        // Also seed liveMatchCache with the initial snapshot so legacy
        // /match/live/{key} reads return the kickoff state.
        liveMatchCache.put(key, session.snapshot());
        return session;
    }

    /**
     * Per-match state container that exposes an explicit, minute-by-minute
     * advance API. The constructor performs all the one-shot setup (squad
     * load, stamina init, big-chance scheduling, kickoff event) and leaves
     * {@code currentMinute = 0}. Each {@link #advanceUntil(int)} call ticks
     * the engine forward; when {@code currentMinute} hits {@code totalMinutes}
     * the session emits the full-time event and persists match events +
     * post-match fitness.
     *
     * <p>Non-static so it can call the outer helpers ({@code pickWeightedAttacker},
     * {@code applyStaminaTick}, {@code performSub}, etc.) without plumbing
     * the service reference through every call site.
     */
    public class LiveMatchSession {
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
        final Random random;
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

        /** Mark the session as committed — called by CompetitionController after
         *  all post-match work has run. Idempotent. */
        public synchronized void markCommitted() {
            this.committed = true;
        }

        // ---- Deferred context for /commit (stashed by simulateMatchday) ----
        double deferredTeamPower1, deferredTeamPower2;
        String deferredTactic1, deferredTactic2;
        com.footballmanagergamesimulator.model.PersonalizedTactic deferredPersonalizedTactic1;
        com.footballmanagergamesimulator.model.PersonalizedTactic deferredPersonalizedTactic2;
        boolean deferredKnockout;

        public void setDeferredContext(double teamPower1, double teamPower2,
                                       String tactic1, String tactic2,
                                       com.footballmanagergamesimulator.model.PersonalizedTactic pt1,
                                       com.footballmanagergamesimulator.model.PersonalizedTactic pt2,
                                       boolean knockout) {
            this.deferredTeamPower1 = teamPower1;
            this.deferredTeamPower2 = teamPower2;
            this.deferredTactic1 = tactic1;
            this.deferredTactic2 = tactic2;
            this.deferredPersonalizedTactic1 = pt1;
            this.deferredPersonalizedTactic2 = pt2;
            this.deferredKnockout = knockout;
        }

        public double getDeferredTeamPower1() { return deferredTeamPower1; }
        public double getDeferredTeamPower2() { return deferredTeamPower2; }
        public String getDeferredTactic1() { return deferredTactic1; }
        public String getDeferredTactic2() { return deferredTactic2; }
        public com.footballmanagergamesimulator.model.PersonalizedTactic getDeferredPersonalizedTactic1() { return deferredPersonalizedTactic1; }
        public com.footballmanagergamesimulator.model.PersonalizedTactic getDeferredPersonalizedTactic2() { return deferredPersonalizedTactic2; }
        public boolean isDeferredKnockout() { return deferredKnockout; }

        /** Mutators for /commit's knockout extra-time decider. */
        public synchronized void bumpHomeScore() { this.homeScore++; }
        public synchronized void bumpAwayScore() { this.awayScore++; }

        /** Snapshot the LiveMatchData DTO that's currently cached so /commit's
         *  callers can pass it to persistLiveMatchStats. */
        public LiveMatchData asLiveMatchData() { return snapshot(); }

        LiveMatchSession(long teamId1, long teamId2,
                         double power1, double power2,
                         long competitionId, int season, int round,
                         boolean generateGoalAnimations) {
            this.teamId1 = teamId1;
            this.teamId2 = teamId2;
            this.competitionId = competitionId;
            this.season = season;
            this.round = round;
            this.generateGoalAnimations = generateGoalAnimations;
            this.random = new Random();

            this.homeTeamName = teamRepository.findNameById(teamId1);
            this.awayTeamName = teamRepository.findNameById(teamId2);
            this.competitionName = competitionRepository.findNameById(competitionId);

            this.team1Outfield = getOutfieldPlayers(teamId1);
            this.team2Outfield = getOutfieldPlayers(teamId2);
            this.team1All = humanRepository.findAllByTeamIdAndTypeId(teamId1, 1L).stream()
                    .filter(h -> !h.isRetired()).collect(Collectors.toList());
            this.team2All = humanRepository.findAllByTeamIdAndTypeId(teamId2, 1L).stream()
                    .filter(h -> !h.isRetired()).collect(Collectors.toList());

            this.matchStates = new HashMap<>();
            Map<Long, PlayerSkills> skillsCache = loadSkillsCache(team1All, team2All);
            initializeMatchStates(matchStates, team1All, skillsCache);
            initializeMatchStates(matchStates, team2All, skillsCache);
            this.team1Ids = team1All.stream().map(Human::getId).collect(Collectors.toSet());
            this.team2Ids = team2All.stream().map(Human::getId).collect(Collectors.toSet());

            this.goalAnimations = generateGoalAnimations ? new LinkedHashMap<>() : null;

            double totalPower = power1 + power2;
            double t1Poss = totalPower > 0 ? power1 / totalPower : 0.5;
            this.team1PossChance = Math.min(0.65, t1Poss + 0.03);
            double rawRatio1 = totalPower > 0 ? power1 / totalPower : 0.5;
            double rawRatio2 = 1.0 - rawRatio1;
            this.team1AttackChance = 0.04 + Math.min(0.14, rawRatio1 * 0.18);
            this.team2AttackChance = 0.04 + Math.min(0.14, rawRatio2 * 0.18);

            int team1BigChances = computeBigChances(rawRatio1, random);
            int team2BigChances = computeBigChances(rawRatio2, random);
            this.team1BigChanceMinutes = pickRandomMinutes(team1BigChances, 5, 89, random);
            this.team2BigChanceMinutes = pickRandomMinutes(team2BigChances, 5, 89, random);
            team2BigChanceMinutes.removeAll(team1BigChanceMinutes);

            this.firstHalfStoppage = random.nextInt(6);
            this.secondHalfStoppage = random.nextInt(6);
            this.halfTimeMinute = 45 + firstHalfStoppage;
            this.totalMinutes = 90 + firstHalfStoppage + secondHalfStoppage;

            // Kickoff event — emitted once at session creation so the timeline
            // is non-empty even before the first advance.
            timeline.add(createMinuteEvent(1, 0, 0, "kickoff",
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
            goalAnimationService.setMatchStoppage(firstHalfStoppage);
            try {
                while (currentMinute < endMinute) {
                    currentMinute++;
                    tickOneMinute(currentMinute);
                }
            } finally {
                goalAnimationService.clearMatchStoppage();
            }

            if (currentMinute >= totalMinutes && !finished) {
                finished = true;
                timeline.add(createMinuteEvent(totalMinutes, homeScore, awayScore, "full_time",
                        "FULL TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                        0, null, 0, null));
                if (!dbEvents.isEmpty()) matchEventRepository.saveAll(dbEvents);
                persistPostMatchFitness(matchStates, team1All, team2All);
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
            List<Human> attackers = filterOnPitch(team1HasBall ? team1Outfield : team2Outfield, matchStates);
            if (attackers.isEmpty()) attackers = team1HasBall ? team1Outfield : team2Outfield;
            List<Human> allDefenders = filterOnPitch(team1HasBall ? team2All : team1All, matchStates);
            if (allDefenders.isEmpty()) allDefenders = team1HasBall ? team2All : team1All;

            double roll = random.nextDouble();
            double currentAttackChance;
            if (forcedAttack) {
                currentAttackChance = 1.0;
            } else {
                double base = team1HasBall ? team1AttackChance : team2AttackChance;
                int attackerOnPitch = team1HasBall ? team1OnPitch : team2OnPitch;
                currentAttackChance = base * manAdvantageAttackMultiplier(attackerOnPitch);
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
            double tempoMult1 = tempoMultiplier(deferredPersonalizedTactic1 != null ? deferredPersonalizedTactic1.getTempo() : null);
            double tempoMult2 = tempoMultiplier(deferredPersonalizedTactic2 != null ? deferredPersonalizedTactic2.getTempo() : null);
            applyStaminaTick(matchStates, team1Ids, team2Ids, tempoMult1, tempoMult2);
            if (min % STAMINA_SNAPSHOT_INTERVAL == 0 || min == totalMinutes) {
                staminaSnapshots.add(captureStaminaSnapshot(min, matchStates, team1Ids, team2Ids));
            }

            // Half time
            if (min == halfTimeMinute) {
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "half_time",
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
            Human attacker = pickWeightedAttacker(attackers, matchStates, random);
            if (team1HasBall) homeShots++; else awayShots++;

            double attackRoll = random.nextDouble();
            double goalCutoff    = forcedAttack ? 0.30 : 0.17;
            double saveCutoff    = forcedAttack ? 0.60 : 0.42;
            double missCutoff    = forcedAttack ? 0.85 : 0.70;
            double blockedCutoff = forcedAttack ? 0.95 : 0.87;

            if (attackRoll < goalCutoff) {
                // GOAL — pick the play type first so commentary + animation agree
                String playType = pickAttackPlayType("GOAL", random);
                if (team1HasBall) { homeScore++; homeShotsOnTarget++; }
                else { awayScore++; awayShotsOnTarget++; }

                String goalDesc = GOAL_DESCRIPTIONS[random.nextInt(GOAL_DESCRIPTIONS.length)];
                String prefix = playTypePrefix(playType, "GOAL");
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "goal",
                        prefix + "GOAL! " + attacker.getName() + "! " + goalDesc,
                        attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "goal", attacker.getId(), attacker.getName(), attackingTeamId,
                        (prefix.isEmpty() ? "" : prefix.trim() + " ") + goalDesc));

                Human goalAssister = null;
                if (random.nextDouble() < 0.7 && attackers.size() > 1) {
                    goalAssister = pickDifferentPlayer(attackers, attacker, random);
                    if (goalAssister != null) {
                        dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                min, "assist", goalAssister.getId(), goalAssister.getName(), attackingTeamId, "Assist"));
                    }
                }

                if (goalAnimations != null) {
                    GoalAnimationData anim = buildAttackAnimation(
                            filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                            filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                            attacker, goalAssister,
                            attackingTeamId, team1HasBall ? teamId2 : teamId1,
                            teamId1, min, "GOAL", playType);
                    if (anim != null) goalAnimations.put(min, anim);
                }

            } else if (attackRoll < saveCutoff) {
                String playType = pickAttackPlayType("SAVE", random);
                if (team1HasBall) homeShotsOnTarget++; else awayShotsOnTarget++;
                String saveDesc = SAVE_DESCRIPTIONS[random.nextInt(SAVE_DESCRIPTIONS.length)];
                String prefix = playTypePrefix(playType, "SAVE");
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_saved",
                        prefix + attacker.getName() + " " + saveDesc,
                        attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
                // Persist save events so the Match Events summary can show
                // "penalty saved" / "free kick saved" alongside goals + cards.
                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "shot_saved", attacker.getId(), attacker.getName(), attackingTeamId,
                        (prefix.isEmpty() ? "Shot saved" : prefix.trim())));

                boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.25);
                if (goalAnimations != null && shouldAnimate) {
                    GoalAnimationData anim = buildAttackAnimation(
                            filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                            filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                            attacker, null,
                            attackingTeamId, team1HasBall ? teamId2 : teamId1,
                            teamId1, min, "SAVE", playType);
                    if (anim != null) goalAnimations.put(min, anim);
                }

            } else if (attackRoll < missCutoff) {
                String playType = pickAttackPlayType("MISS", random);
                String missDesc = MISS_DESCRIPTIONS[random.nextInt(MISS_DESCRIPTIONS.length)];
                String prefix = playTypePrefix(playType, "MISS");
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_wide",
                        prefix + attacker.getName() + " " + missDesc,
                        attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "shot_wide", attacker.getId(), attacker.getName(), attackingTeamId,
                        (prefix.isEmpty() ? "Shot wide" : prefix.trim())));

                boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.15);
                if (goalAnimations != null && shouldAnimate) {
                    GoalAnimationData anim = buildAttackAnimation(
                            filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                            filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                            attacker, null,
                            attackingTeamId, team1HasBall ? teamId2 : teamId1,
                            teamId1, min, "MISS", playType);
                    if (anim != null) goalAnimations.put(min, anim);
                }

            } else if (attackRoll < blockedCutoff) {
                String blockDesc = BLOCK_DESCRIPTIONS[random.nextInt(BLOCK_DESCRIPTIONS.length)];
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_blocked",
                        attacker.getName() + blockDesc,
                        attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

            } else {
                // Corner
                if (team1HasBall) homeCorners++; else awayCorners++;
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "corner",
                        "Corner kick for " + attackingTeamName + ".",
                        0, null, attackingTeamId, attackingTeamName));

                if (random.nextDouble() < 0.08 && !attackers.isEmpty()) {
                    Human header = pickWeightedAttacker(attackers, matchStates, random);
                    if (team1HasBall) { homeScore++; homeShotsOnTarget++; homeShots++; }
                    else { awayScore++; awayShotsOnTarget++; awayShots++; }

                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "goal",
                            "GOAL! " + header.getName() + " rises highest and heads it in from the corner!",
                            header.getId(), header.getName(), attackingTeamId, attackingTeamName));
                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "goal", header.getId(), header.getName(), attackingTeamId, "Header from corner"));

                    if (goalAnimations != null) {
                        GoalAnimationData anim = goalAnimationService.generate(
                                filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                                filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
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
                String template = POSSESSION_COMMENTARY[random.nextInt(POSSESSION_COMMENTARY.length)];
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "commentary",
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
            Human fouler = pickFouler(allDefenders, matchStates, random);
            if (fouler == null) fouler = allDefenders.get(random.nextInt(allDefenders.size()));
            if (team1HasBall) awayFouls++; else homeFouls++;
            long foulerTeamId = team1HasBall ? teamId2 : teamId1;
            String foulerTeamName = team1HasBall ? awayTeamName : homeTeamName;
            double cardRoll = random.nextDouble();
            if (cardRoll < 0.22) {
                if (team1HasBall) awayYellowCards++; else homeYellowCards++;
                String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "yellow_card",
                        fouler.getName() + " " + foulDesc + " Yellow card!",
                        fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "yellow_card", fouler.getId(), fouler.getName(), foulerTeamId, "Yellow card"));
            } else if (cardRoll < 0.24) {
                if (team1HasBall) awayRedCards++; else homeRedCards++;
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "red_card",
                        "RED CARD! " + fouler.getName() + " is sent off for a terrible challenge!",
                        fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "red_card", fouler.getId(), fouler.getName(), foulerTeamId, "Red card"));
                PlayerMatchState rcState = matchStates.get(fouler.getId());
                if (rcState != null) rcState.isOnPitch = false;
            } else if (min % 4 == 0) {
                String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "foul",
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
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "offside",
                        offside.getName() + " is caught offside. Free kick to the defence.",
                        offside.getId(), offside.getName(), attackingTeamId, attackingTeamName));
            }
        }

        /** Generic "build-up play" commentary fallback; every 7 minutes so the
         *  feed picks up some chatter even on quiet stretches. */
        private void tickBuildupBranch(int min, long attackingTeamId, String attackingTeamName) {
            if (min % 7 == 0) {
                String template = BUILDUP_COMMENTARY[random.nextInt(BUILDUP_COMMENTARY.length)];
                timeline.add(createMinuteEvent(min, homeScore, awayScore, "commentary",
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

            SubReason reason = decideSubReason(min, ownScore, oppScore, matchStates, teamIds);
            if (reason == null) return;

            SubSwap swap = performSub(matchStates, squad, reason);
            if (swap == null && reason != SubReason.FATIGUE) {
                swap = performSub(matchStates, squad, SubReason.FATIGUE);
                if (swap != null) reason = SubReason.FATIGUE;
            }
            if (swap == null) return;

            if (isHome) { homeSubsUsed++; homeLastSubMin = min; }
            else        { awaySubsUsed++; awayLastSubMin = min; }

            String commentary = buildSubCommentary(teamName, swap, reason);
            timeline.add(createMinuteEvent(min, homeScore, awayScore, "substitution",
                    commentary, swap.off.getId(), swap.off.getName(), teamId, teamName));
            dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
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
            Human offHuman = findById(isHome ? team1All : team2All, off.playerId);
            Human onHuman  = findById(isHome ? team1All : team2All, on.playerId);
            if (offHuman != null && onHuman != null) {
                SubSwap swap = new SubSwap(offHuman, onHuman);
                String teamName = isHome ? homeTeamName : awayTeamName;
                long teamId = isHome ? teamId1 : teamId2;
                String commentary = buildSubCommentary(teamName, swap, SubReason.MANUAL);
                int eventMinute = Math.max(1, lastSubAtMinute > 0 ? lastSubAtMinute : currentMinute);

                LiveMatchMinute subEvent = createMinuteEvent(eventMinute, homeScore, awayScore, "substitution",
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

                dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
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
}
