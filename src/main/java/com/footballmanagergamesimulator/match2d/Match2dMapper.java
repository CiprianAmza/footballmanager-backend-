package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.GoalSlot;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Scorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure mapping from a result of the 2D match engine onto the game's canonical match records:
 * the scoring decision, the regular-time goal slots, the player ratings and the team
 * statistics. No I/O; everything the game does not get from the engine stays as the
 * classic pipeline produces it.
 */
public final class Match2dMapper {

    /** Goal type of an own goal: credited to the opponent who put it in, never to a scorer tally. */
    public static final String OWN_GOAL = "OWN_GOAL";
    static final int FIRST_MINUTE = 1;
    static final int LAST_MINUTE = 90;

    private Match2dMapper() {
    }

    /**
     * Whether a result can decide a fixture: it was played, has a two-sided non-negative
     * score, and lists exactly as many goals for each side (team 0 or 1) as that score.
     */
    public static boolean usable(Match2dResult result) {
        if (result == null || result.isFailed()) return false;
        List<Integer> score = result.score();
        if (score == null || score.size() != 2 || score.get(0) == null || score.get(1) == null
                || score.get(0) < 0 || score.get(1) < 0) {
            return false;
        }
        int[] counted = new int[2];
        for (Match2dResult.Goal goal : result.goals() == null ? List.<Match2dResult.Goal>of() : result.goals()) {
            if (goal == null || goal.team() == null || (goal.team() != 0 && goal.team() != 1)) return false;
            counted[goal.team()]++;
        }
        return counted[0] == score.get(0) && counted[1] == score.get(1);
    }

    public static int homeGoals(Match2dResult result) {
        return result.score().get(0);
    }

    public static int awayGoals(Match2dResult result) {
        return result.score().get(1);
    }

    /** The goal minute shown by the game: the engine's, kept inside regular time. */
    static int minuteOf(Integer minute) {
        if (minute == null) return FIRST_MINUTE;
        return Math.max(FIRST_MINUTE, Math.min(LAST_MINUTE, minute));
    }

    /** {@code penalty}, {@code free_kick}, {@code header}, anything else open play. */
    static String goalTypeOf(String how) {
        if (how == null) return "OPEN_PLAY";
        return switch (how) {
            case "penalty" -> "PENALTY";
            case "free_kick" -> "FREE_KICK";
            case "header" -> "HEADER";
            default -> "OPEN_PLAY";
        };
    }

    /**
     * One regular-time goal slot per engine goal, in the engine's order. {@code team} 0/1 is the
     * side the goal counts for. A goal whose scorer is a starter of that side (or, for an own
     * goal, of the other side) comes already resolved; any other goal is left unresolved, for
     * the classic resolver to pick its scorer from the eleven. An own goal without a known
     * player becomes an ordinary goal, so no player of the benefiting side is shown as its
     * author. The assist is kept only when it is another starter of the scoring side.
     */
    public static List<GoalSlot> regularTimeSlots(Match2dResult result, long homeTeamId, long awayTeamId,
                                                  Set<Long> homePlayers, Set<Long> awayPlayers) {
        List<GoalSlot> slots = new ArrayList<>();
        if (result.goals() == null) return slots;
        for (Match2dResult.Goal goal : result.goals()) {
            boolean home = goal.team() == 0;
            long teamId = home ? homeTeamId : awayTeamId;
            Set<Long> own = home ? homePlayers : awayPlayers;
            Set<Long> other = home ? awayPlayers : homePlayers;
            int minute = minuteOf(goal.minute());
            Long playerId = goal.playerId();
            if (goal.own()) {
                if (playerId != null && other.contains(playerId)) {
                    GoalSlot slot = new GoalSlot(teamId, minute, GoalPhase.REGULAR_TIME, OWN_GOAL);
                    slot.resolve(playerId, null);
                    slots.add(slot);
                } else {
                    slots.add(new GoalSlot(teamId, minute, GoalPhase.REGULAR_TIME, "OPEN_PLAY"));
                }
                continue;
            }
            GoalSlot slot = new GoalSlot(teamId, minute, GoalPhase.REGULAR_TIME, goalTypeOf(goal.how()));
            if (playerId != null && own.contains(playerId)) {
                Long assistId = goal.assistId();
                boolean assistValid = assistId != null && !assistId.equals(playerId) && own.contains(assistId);
                slot.resolve(playerId, assistValid ? assistId : null);
            }
            slots.add(slot);
        }
        return slots;
    }

    /**
     * The durable scoring decision of a 2D match: the engine's regular-time score, with no
     * SHOOTER, PASSING STYLE or red-card component (every goal is collective). xG comes from
     * the engine's summary; the fingerprints name the engine version and the exact request.
     */
    public static MatchScoringDecision decision(String fixtureKey, long seed, Match2dResult result,
                                                String requestJson, double homePower, double awayPower) {
        Double[] xg = expectedGoals(result);
        return new MatchScoringDecision(fixtureKey, seed, ScoreEngineKind.MATCH2D_V1,
                ScoreEngineKind.MATCH2D_V1.algorithmVersion(),
                sha256("match2d:" + result.engineVersion()), sha256(requestJson == null ? "" : requestJson),
                homeGoals(result), awayGoals(result), homePower, awayPower, xg[0], xg[1]);
    }

    /** {@code [home, away]} xG from {@code summary.teams}, or both {@code null} when either is unusable. */
    static Double[] expectedGoals(Match2dResult result) {
        JsonNode teams = teams(result);
        if (teams == null) return new Double[]{null, null};
        JsonNode home = teams.get(0).path("xg");
        JsonNode away = teams.get(1).path("xg");
        if (!home.isNumber() || !away.isNumber()) return new Double[]{null, null};
        double h = home.asDouble();
        double a = away.asDouble();
        if (!Double.isFinite(h) || !Double.isFinite(a) || h < 0 || a < 0) return new Double[]{null, null};
        return new Double[]{h, a};
    }

    /** Engine match rating by player id (the database id sent in the line-up). */
    public static Map<Long, Double> ratingsById(Match2dResult result) {
        Map<Long, Double> ratings = new HashMap<>();
        if (result.players() == null) return ratings;
        for (Match2dResult.PlayerLine line : result.players()) {
            if (line != null && line.id() != null && line.rating() != null && Double.isFinite(line.rating())) {
                ratings.put(line.id(), line.rating());
            }
        }
        return ratings;
    }

    /** Replaces each scorer row's rating with the engine's, where the engine rated that player. */
    public static void applyRatings(List<Scorer> scorers, Map<Long, Double> ratings) {
        if (scorers == null) return;
        for (Scorer scorer : scorers) {
            Double rating = ratings.get(scorer.getPlayerId());
            if (rating != null) scorer.setRating(rating);
        }
    }

    /**
     * Goal events whose scorer is not on his own side's sheet (own goals) carry no name;
     * the engine's name for that player is filled in.
     */
    public static void fillMissingNames(List<MatchEvent> events, Match2dTeam home, Match2dTeam away) {
        if (events == null) return;
        Map<Long, String> names = new HashMap<>();
        for (Match2dTeam team : List.of(home, away)) {
            for (Match2dTeam.Player player : team.players()) names.put(player.id(), player.name());
        }
        for (MatchEvent event : events) {
            if ((event.getPlayerName() == null || event.getPlayerName().isBlank())
                    && names.containsKey(event.getPlayerId())) {
                event.setPlayerName(names.get(event.getPlayerId()));
            }
        }
    }

    /** The ids of the players the team fielded. */
    public static Set<Long> playerIds(Match2dTeam team) {
        return team.players().stream().map(Match2dTeam.Player::id).collect(Collectors.toSet());
    }

    /**
     * Overwrites the statistics the engine measured; the rest stays as given. The goals
     * already on {@code stats} (regular plus extra time) are kept, and the shot counts are
     * raised where needed so that {@code shots >= onTarget >= goals}; blocked shots, saves and
     * big chances are kept inside the same bounds. The 2D engine shows no red cards.
     */
    public static void overwriteStats(MatchStats stats, Match2dResult result) {
        JsonNode teams = teams(result);
        if (teams == null) return;
        JsonNode h = teams.get(0);
        JsonNode a = teams.get(1);
        int homeGoals = stats.getHomeGoals();
        int awayGoals = stats.getAwayGoals();

        int homeOnTarget = Math.max(homeGoals, count(h, "on_target", stats.getHomeShotsOnTarget()));
        int awayOnTarget = Math.max(awayGoals, count(a, "on_target", stats.getAwayShotsOnTarget()));
        int homeShots = Math.max(homeOnTarget, count(h, "shots", stats.getHomeShots()));
        int awayShots = Math.max(awayOnTarget, count(a, "shots", stats.getAwayShots()));
        stats.setHomeShots(homeShots);
        stats.setAwayShots(awayShots);
        stats.setHomeShotsOnTarget(homeOnTarget);
        stats.setAwayShotsOnTarget(awayOnTarget);
        stats.setHomeShotsBlocked(Math.min(count(h, "blocked", stats.getHomeShotsBlocked()), homeShots - homeOnTarget));
        stats.setAwayShotsBlocked(Math.min(count(a, "blocked", stats.getAwayShotsBlocked()), awayShots - awayOnTarget));
        // Saves of a side are the shots on its goal that did not go in.
        stats.setHomeSaves(Math.min(count(h, "saves", awayOnTarget - awayGoals), Math.max(0, awayOnTarget - awayGoals)));
        stats.setAwaySaves(Math.min(count(a, "saves", homeOnTarget - homeGoals), Math.max(0, homeOnTarget - homeGoals)));

        int homeBig = Math.min(stats.getHomeBigChances(), homeShots);
        int awayBig = Math.min(stats.getAwayBigChances(), awayShots);
        stats.setHomeBigChances(homeBig);
        stats.setAwayBigChances(awayBig);
        stats.setHomeBigChancesMissed(Math.max(0, homeBig - homeGoals));
        stats.setAwayBigChancesMissed(Math.max(0, awayBig - awayGoals));

        stats.setHomeCorners(count(h, "corners", stats.getHomeCorners()));
        stats.setAwayCorners(count(a, "corners", stats.getAwayCorners()));
        stats.setHomeOffsides(count(h, "offsides", stats.getHomeOffsides()));
        stats.setAwayOffsides(count(a, "offsides", stats.getAwayOffsides()));
        stats.setHomeFouls(count(h, "fouls", stats.getHomeFouls()));
        stats.setAwayFouls(count(a, "fouls", stats.getAwayFouls()));
        stats.setHomeFreeKicks(count(h, "free_kicks", stats.getHomeFreeKicks()));
        stats.setAwayFreeKicks(count(a, "free_kicks", stats.getAwayFreeKicks()));
        stats.setHomeYellowCards(count(h, "yellow_cards", stats.getHomeYellowCards()));
        stats.setAwayYellowCards(count(a, "yellow_cards", stats.getAwayYellowCards()));
        stats.setHomeRedCards(0);
        stats.setAwayRedCards(0);

        int homePasses = count(h, "passes", stats.getHomePasses());
        int awayPasses = count(a, "passes", stats.getAwayPasses());
        stats.setHomePasses(homePasses);
        stats.setAwayPasses(awayPasses);
        stats.setHomePassAccuracy(accuracy(h, homePasses, stats.getHomePassAccuracy()));
        stats.setAwayPassAccuracy(accuracy(a, awayPasses, stats.getAwayPassAccuracy()));
        stats.setHomeTackles(count(h, "tackles", stats.getHomeTackles()));
        stats.setAwayTackles(count(a, "tackles", stats.getAwayTackles()));
        stats.setHomeInterceptions(count(h, "interceptions", stats.getHomeInterceptions()));
        stats.setAwayInterceptions(count(a, "interceptions", stats.getAwayInterceptions()));

        JsonNode possession = h.path("poss_pct");
        if (possession.isNumber() && Double.isFinite(possession.asDouble())) {
            int home = (int) Math.round(Math.max(0, Math.min(100, possession.asDouble())));
            stats.setHomePossession(home);
            stats.setAwayPossession(100 - home);
        }

        Double[] xg = expectedGoals(result);
        if (xg[0] != null) {
            stats.setHomeXg(hundredths(xg[0]));
            stats.setAwayXg(hundredths(xg[1]));
        }

        int homeCrosses = count(h, "crosses", stats.getHomeCrosses());
        int awayCrosses = count(a, "crosses", stats.getAwayCrosses());
        stats.setHomeCrosses(homeCrosses);
        stats.setAwayCrosses(awayCrosses);
        stats.setHomeCrossesAccurate(Math.min(homeCrosses, count(h, "crosses_ok", stats.getHomeCrossesAccurate())));
        stats.setAwayCrossesAccurate(Math.min(awayCrosses, count(a, "crosses_ok", stats.getAwayCrossesAccurate())));

        applyManOfTheMatch(stats, result);
    }

    /** Per side: the player the engine named man of the match, else that side's best-rated player. */
    private static void applyManOfTheMatch(MatchStats stats, Match2dResult result) {
        if (result.players() == null) return;
        for (int side = 0; side < 2; side++) {
            Match2dResult.PlayerLine best = null;
            for (Match2dResult.PlayerLine line : result.players()) {
                if (line == null || line.team() == null || line.team() != side || line.id() == null
                        || line.rating() == null) continue;
                if (best == null || (line.motm() && !best.motm())
                        || (line.motm() == best.motm() && line.rating() > best.rating())) {
                    best = line;
                }
            }
            if (best == null) continue;
            if (side == 0) {
                stats.setHomeManOfTheMatchId(best.id());
                stats.setHomeManOfTheMatchName(best.name());
                stats.setHomeManOfTheMatchRating(best.rating());
            } else {
                stats.setAwayManOfTheMatchId(best.id());
                stats.setAwayManOfTheMatchName(best.name());
                stats.setAwayManOfTheMatchRating(best.rating());
            }
        }
    }

    private static JsonNode teams(Match2dResult result) {
        if (result == null || result.summary() == null) return null;
        JsonNode teams = result.summary().path("teams");
        if (!teams.isArray() || teams.size() < 2 || !teams.get(0).isObject() || !teams.get(1).isObject()) {
            return null;
        }
        return teams;
    }

    /** A non-negative counter from the engine, else {@code fallback}. */
    private static int count(JsonNode team, String key, int fallback) {
        JsonNode value = team.path(key);
        if (!value.isNumber()) return Math.max(0, fallback);
        return Math.max(0, value.asInt());
    }

    private static int accuracy(JsonNode team, int passes, int fallback) {
        JsonNode ok = team.path("passes_ok");
        if (passes <= 0 || !ok.isNumber()) return fallback;
        return (int) Math.round(Math.max(0, Math.min(100, 100.0 * ok.asInt() / passes)));
    }

    private static int hundredths(double xg) {
        return (int) Math.max(0, Math.round(xg * 100.0));
    }

    /** Lowercase hex SHA-256 of the UTF-8 text. */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
