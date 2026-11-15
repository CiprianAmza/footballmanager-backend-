package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.GoalSlot;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Scorer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class Match2dMapperTest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;
    private static final Set<Long> HOME_XI = Set.of(101L, 102L, 103L);
    private static final Set<Long> AWAY_XI = Set.of(201L, 202L, 203L);

    private final ObjectMapper json = new ObjectMapper();

    private Match2dResult result(String body) throws Exception {
        return json.readValue(body, Match2dResult.class);
    }

    @Test
    void everyEngineGoalBecomesARegularTimeSlotOfItsSideWithScorerAndAssist() throws Exception {
        Match2dResult r = result("""
                {"ref":"1","score":[2,1],"engineVersion":"1","goals":[
                  {"minute":12,"team":0,"playerId":101,"assistId":102,"own":false,"how":null},
                  {"minute":40,"team":1,"playerId":201,"own":false,"how":"header"},
                  {"minute":77,"team":0,"playerId":103,"own":false,"how":"penalty"}]}""");

        List<GoalSlot> slots = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);

        assertThat(slots).hasSize(3);
        assertThat(slots).allMatch(slot -> slot.getPhase() == GoalPhase.REGULAR_TIME && slot.isResolved());
        assertThat(slots.get(0).getTeamId()).isEqualTo(HOME);
        assertThat(slots.get(0).getMinute()).isEqualTo(12);
        assertThat(slots.get(0).getGoalType()).isEqualTo("OPEN_PLAY");
        assertThat(slots.get(0).getScorerId()).isEqualTo(101L);
        assertThat(slots.get(0).getAssistId()).isEqualTo(102L);
        assertThat(slots.get(1).getTeamId()).isEqualTo(AWAY);
        assertThat(slots.get(1).getGoalType()).isEqualTo("HEADER");
        assertThat(slots.get(1).getAssistId()).isNull();
        assertThat(slots.get(2).getGoalType()).isEqualTo("PENALTY");
    }

    @Test
    void howMapsToTheGameGoalTypes() {
        assertThat(Match2dMapper.goalTypeOf("penalty")).isEqualTo("PENALTY");
        assertThat(Match2dMapper.goalTypeOf("free_kick")).isEqualTo("FREE_KICK");
        assertThat(Match2dMapper.goalTypeOf("header")).isEqualTo("HEADER");
        assertThat(Match2dMapper.goalTypeOf(null)).isEqualTo("OPEN_PLAY");
        assertThat(Match2dMapper.goalTypeOf("volley")).isEqualTo("OPEN_PLAY");
    }

    @Test
    void goalMinutesStayInsideRegularTime() throws Exception {
        Match2dResult r = result("""
                {"score":[3,0],"goals":[
                  {"minute":0,"team":0,"playerId":101},
                  {"minute":93,"team":0,"playerId":102},
                  {"team":0,"playerId":103}]}""");

        List<GoalSlot> slots = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);

        assertThat(slots).extracting(GoalSlot::getMinute).containsExactly(1, 90, 1);
    }

    @Test
    void anOwnGoalCountsForTheBenefitingSideAndIsCreditedToTheOpponentWhoScoredIt() throws Exception {
        Match2dResult r = result("""
                {"score":[1,0],"goals":[
                  {"minute":55,"team":0,"playerId":202,"assistId":101,"own":true,"how":null}]}""");

        GoalSlot slot = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI).get(0);

        assertThat(slot.getTeamId()).isEqualTo(HOME);
        assertThat(slot.getGoalType()).isEqualTo(Match2dMapper.OWN_GOAL);
        assertThat(slot.isResolved()).isTrue();
        assertThat(slot.getScorerId()).isEqualTo(202L);
        assertThat(slot.getAssistId()).isNull();
    }

    @Test
    void anOwnGoalWithoutAKnownOpponentBecomesAnOrdinaryUnresolvedGoal() throws Exception {
        Match2dResult r = result("""
                {"score":[2,0],"goals":[
                  {"minute":20,"team":0,"own":true},
                  {"minute":30,"team":0,"playerId":101,"own":true}]}""");

        List<GoalSlot> slots = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);

        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.getGoalType()).isEqualTo("OPEN_PLAY");
            assertThat(slot.isResolved()).isFalse();
        });
    }

    @Test
    void anUnknownScorerIsLeftToTheClassicResolverAndABadAssistIsDropped() throws Exception {
        Match2dResult r = result("""
                {"score":[2,0],"goals":[
                  {"minute":20,"team":0,"playerId":999},
                  {"minute":30,"team":0,"playerId":101,"assistId":201}]}""");

        List<GoalSlot> slots = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);

        assertThat(slots.get(0).isResolved()).isFalse();
        assertThat(slots.get(1).getScorerId()).isEqualTo(101L);
        assertThat(slots.get(1).getAssistId()).isNull();
    }

    @Test
    void aResultIsUsableOnlyWhenItsGoalsAddUpToItsScore() throws Exception {
        assertThat(Match2dMapper.usable(result("""
                {"score":[1,0],"goals":[{"minute":5,"team":0,"playerId":101}]}"""))).isTrue();
        assertThat(Match2dMapper.usable(result("""
                {"score":[2,0],"goals":[{"minute":5,"team":0,"playerId":101}]}"""))).isFalse();
        assertThat(Match2dMapper.usable(result("""
                {"score":[1,0],"goals":[{"minute":5,"team":1,"playerId":201}]}"""))).isFalse();
        assertThat(Match2dMapper.usable(result("{\"score\":[0,0],\"goals\":[]}"))).isTrue();
        assertThat(Match2dMapper.usable(Match2dResult.failed("1", "boom"))).isFalse();
        assertThat(Match2dMapper.usable(result("{\"score\":[1]}"))).isFalse();
    }

    @Test
    void theDecisionIsAValidMatch2dDecisionWithOnlyCollectiveGoals() throws Exception {
        Match2dResult r = result("""
                {"score":[2,1],"engineVersion":"1","goals":[
                  {"minute":1,"team":0,"playerId":101},{"minute":2,"team":0,"playerId":102},
                  {"minute":3,"team":1,"playerId":201}],
                 "summary":{"teams":[{"xg":1.734},{"xg":0.512}]}}""");

        MatchScoringDecision decision = Match2dMapper.decision("CTIM:7", 42L, r, "{\"seed\":42}", 812.5, 790.0);

        assertThat(decision.scoreEngine()).isEqualTo(ScoreEngineKind.MATCH2D_V1);
        assertThat(decision.scoreAlgorithmVersion()).isEqualTo("match2d-1");
        assertThat(decision.seed()).isEqualTo(42L);
        assertThat(decision.homeScore90()).isEqualTo(2);
        assertThat(decision.awayScore90()).isEqualTo(1);
        assertThat(decision.homeCollectiveGoals()).isEqualTo(2);
        assertThat(decision.awayCollectiveGoals()).isEqualTo(1);
        assertThat(decision.homeShooterGoals()).isZero();
        assertThat(decision.homePassingGoals()).isZero();
        assertThat(decision.homeRedCardPlayerId()).isNull();
        assertThat(decision.homeXg()).isEqualTo(1.734);
        assertThat(decision.awayXg()).isEqualTo(0.512);
        assertThat(decision.homePower()).isEqualTo(812.5);
        assertThat(decision.configFingerprint()).isEqualTo(Match2dMapper.sha256("match2d:1"));
        assertThat(decision.inputFingerprint()).isEqualTo(Match2dMapper.sha256("{\"seed\":42}"));
        assertThat(decision.configFingerprint()).matches("[0-9a-f]{64}");
        assertThatCode(() -> KnockoutPlanSplit.regularOnly(2, 1).validateAgainst(decision)).doesNotThrowAnyException();
    }

    @Test
    void xgIsLeftOutWhenTheSummaryLacksIt() throws Exception {
        Match2dResult r = result("{\"score\":[0,0],\"goals\":[],\"summary\":{\"teams\":[{\"xg\":0.4},{}]}}");

        MatchScoringDecision decision = Match2dMapper.decision("CTIM:7", 1L, r, null, 1.0, 1.0);

        assertThat(decision.homeXg()).isNull();
        assertThat(decision.awayXg()).isNull();
    }

    @Test
    void engineRatingsReplaceTheScorerRatingsOfThePlayersItRated() throws Exception {
        Match2dResult r = result("""
                {"score":[0,0],"players":[{"team":0,"id":101,"rating":8.3},{"team":1,"id":201,"rating":5.9}]}""");
        Scorer rated = scorer(101L, 6.5);
        Scorer unrated = scorer(150L, 6.5);

        Match2dMapper.applyRatings(List.of(rated, unrated), Match2dMapper.ratingsById(r));

        assertThat(rated.getRating()).isEqualTo(8.3);
        assertThat(unrated.getRating()).isEqualTo(6.5);
    }

    @Test
    void ownGoalEventsGetTheEngineNameOfThePlayer() {
        Match2dTeam home = new Match2dTeam(HOME, "H", "442", null, null, Map.of(),
                List.of(player(101L, "Home Striker")));
        Match2dTeam away = new Match2dTeam(AWAY, "A", "442", null, null, Map.of(),
                List.of(player(202L, "Away Defender")));
        MatchEvent ownGoal = new MatchEvent();
        ownGoal.setPlayerId(202L);
        ownGoal.setPlayerName("");
        MatchEvent named = new MatchEvent();
        named.setPlayerId(101L);
        named.setPlayerName("Kept");

        Match2dMapper.fillMissingNames(List.of(ownGoal, named), home, away);

        assertThat(ownGoal.getPlayerName()).isEqualTo("Away Defender");
        assertThat(named.getPlayerName()).isEqualTo("Kept");
    }

    @Test
    void engineStatisticsOverwriteTheProjectionKeepingShotsAboveOnTargetAboveGoals() throws Exception {
        MatchStats stats = new MatchStats();
        stats.setHomeGoals(3);   // e.g. two of them own goals: the scorers' side shot only once on target
        stats.setAwayGoals(0);
        stats.setHomeRedCards(1);
        stats.setHomeBigChances(9);
        stats.setHomeCrosses(30);
        Match2dResult r = result("""
                {"score":[3,0],"summary":{"teams":[
                  {"shots":2,"on_target":1,"blocked":5,"saves":4,"corners":6,"offsides":2,"fouls":11,
                   "yellow_cards":2,"free_kicks":9,"passes":400,"passes_ok":340,"tackles":17,
                   "interceptions":8,"poss_pct":56.6,"xg":2.35,"crosses":12,"crosses_ok":20},
                  {"shots":9,"on_target":4,"blocked":2,"saves":0,"corners":3,"passes":300,"passes_ok":210,
                   "poss_pct":43.4,"xg":0.871,"crosses":7,"crosses_ok":2}]},
                 "players":[
                  {"team":0,"id":101,"name":"Best Home","rating":8.9,"motm":false},
                  {"team":0,"id":102,"name":"Named","rating":7.1,"motm":true},
                  {"team":1,"id":201,"name":"Best Away","rating":7.4,"motm":false},
                  {"team":1,"id":202,"name":"Other","rating":6.0,"motm":false}]}""");

        Match2dMapper.overwriteStats(stats, r);

        assertThat(stats.getHomeShotsOnTarget()).isEqualTo(3);
        assertThat(stats.getHomeShots()).isEqualTo(3);
        assertThat(stats.getHomeShotsBlocked()).isZero();
        assertThat(stats.getAwayShots()).isEqualTo(9);
        assertThat(stats.getAwayShotsOnTarget()).isEqualTo(4);
        assertThat(stats.getAwayShotsBlocked()).isEqualTo(2);
        assertThat(stats.getHomeSaves()).isEqualTo(4);          // away put 4 on target, none went in
        assertThat(stats.getAwaySaves()).isZero();              // all three home shots on target were goals
        assertThat(stats.getHomeBigChances()).isEqualTo(3);
        assertThat(stats.getHomeBigChancesMissed()).isZero();
        assertThat(stats.getHomeRedCards()).isZero();
        assertThat(stats.getHomePossession() + stats.getAwayPossession()).isEqualTo(100);
        assertThat(stats.getHomePossession()).isEqualTo(57);
        assertThat(stats.getHomePassAccuracy()).isEqualTo(85);
        assertThat(stats.getAwayPassAccuracy()).isEqualTo(70);
        assertThat(stats.getHomeXg()).isEqualTo(235);
        assertThat(stats.getAwayXg()).isEqualTo(87);
        assertThat(stats.getHomeCrosses()).isEqualTo(12);
        assertThat(stats.getHomeCrossesAccurate()).isEqualTo(12);
        assertThat(stats.getHomeCorners()).isEqualTo(6);
        assertThat(stats.getHomeFouls()).isEqualTo(11);
        assertThat(stats.getHomeYellowCards()).isEqualTo(2);
        assertThat(stats.getHomeManOfTheMatchId()).isEqualTo(102L);
        assertThat(stats.getAwayManOfTheMatchId()).isEqualTo(201L);
        assertThat(stats.getAwayManOfTheMatchRating()).isEqualTo(7.4);
    }

    @Test
    void withoutASummaryTheProjectionIsKept() throws Exception {
        MatchStats stats = new MatchStats();
        stats.setHomeShots(14);
        stats.setHomeRedCards(1);

        Match2dMapper.overwriteStats(stats, result("{\"score\":[0,0]}"));

        assertThat(stats.getHomeShots()).isEqualTo(14);
        assertThat(stats.getHomeRedCards()).isEqualTo(1);
    }

    private static Scorer scorer(long playerId, double rating) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setRating(rating);
        return scorer;
    }

    private static Match2dTeam.Player player(long id, String name) {
        return new Match2dTeam.Player(id, name, 9, "ST", "ST", 70, false, null, null, null, null, false, Map.of());
    }
}
