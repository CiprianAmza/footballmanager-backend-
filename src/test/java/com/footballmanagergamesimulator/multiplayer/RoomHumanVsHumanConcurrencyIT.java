package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.multiplayer.RoomContinueCycleRepository;
import com.footballmanagergamesimulator.multiplayer.RoomContinueVoteRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN_BY_POLICY: opt-in owner-run Spring/H2 human-v-human coverage. */
@SpringBootTest
@EnabledIfSystemProperty(named = "run.multiplayer.it", matches = "true")
class RoomHumanVsHumanConcurrencyIT {
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private GameRoomRepository rooms;
    @Autowired private GameRoomMemberRepository members;
    @Autowired private RoomContinueVoteRepository votes;
    @Autowired private RoomContinueCycleRepository cycles;
    @Autowired private CompetitionTeamInfoMatchRepository fixtures;
    @Autowired private MatchPlanRepository plans;
    @Autowired private MatchPlanService planService;
    @Autowired private LiveMatchSimulationService liveMatches;

    private long homeTeam;
    private long awayTeam;
    private String fixtureKey;
    private CompetitionTeamInfoMatch createdFixture;
    private String fixtureNamespace;
    private final List<User> createdUsers = new ArrayList<>();
    private final List<Human> createdHumans = new ArrayList<>();
    private final List<Team> createdTeams = new ArrayList<>();

    @BeforeEach
    void seed() {
        fixtureNamespace = UUID.randomUUID().toString().replace("-", "");
        votes.deleteAll();
        cycles.deleteAll();
        members.deleteAll();
        rooms.deleteAll();
        plans.deleteAll();
        fixtures.deleteAll();

        Team home = new Team(); home.setName("H2 Home " + fixtureNamespace); home = teams.saveAndFlush(home); createdTeams.add(home);
        Team away = new Team(); away.setName("H2 Away " + fixtureNamespace); away = teams.saveAndFlush(away); createdTeams.add(away);
        homeTeam = home.getId(); awayTeam = away.getId();
        User first = users.saveAndFlush(managerUser(homeTeam, "h2-home-" + fixtureNamespace)); createdUsers.add(first);
        User second = users.saveAndFlush(managerUser(awayTeam, "h2-away-" + fixtureNamespace)); createdUsers.add(second);
        seedManager(homeTeam, "Home manager " + fixtureNamespace);
        seedManager(awayTeam, "Away manager " + fixtureNamespace);

        GameRoom room = new GameRoom();
        room.setHostUserId(first.getId()); room.setPasswordHash("test-only"); room.setStatus(RoomStatus.ACTIVE);
        room = rooms.saveAndFlush(room);
        GameRoomMember hm = new GameRoomMember(); hm.setRoomId(room.getId()); hm.setUserId(first.getId()); hm.setTeamId(homeTeam); hm.setReady(true);
        GameRoomMember am = new GameRoomMember(); am.setRoomId(room.getId()); am.setUserId(second.getId()); am.setTeamId(awayTeam); am.setReady(true);
        members.save(hm); members.saveAndFlush(am);

        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(99); fixture.setSeasonNumber("1"); fixture.setRound(7); fixture.setTeam1Id(homeTeam); fixture.setTeam2Id(awayTeam);
        fixture = fixtures.saveAndFlush(fixture); createdFixture = fixture;
        fixtureKey = MatchPlanService.competitionFixtureKey(fixture.getId());
    }

    @AfterEach
    void cleanupFixtureOnly() {
        // Remove dependants first; never touch the global seed users/people/teams.
        votes.deleteAll();
        cycles.deleteAll();
        members.deleteAll();
        rooms.deleteAll();
        plans.deleteAll();
        if (createdFixture != null) fixtures.delete(createdFixture);
        humans.deleteAll(createdHumans);
        users.deleteAll(createdUsers);
        teams.deleteAll(createdTeams);
    }

    @Test
    void concurrentFixtureRequestsShareOneLiveKeyAndOneCanonicalPlan() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MatchPlan> first = executor.submit(() -> prepare(start));
            Future<MatchPlan> second = executor.submit(() -> prepare(start));
            start.countDown();
            MatchPlan p1 = first.get(15, TimeUnit.SECONDS);
            MatchPlan p2 = second.get(15, TimeUnit.SECONDS);
            ExecutorService sessions = Executors.newFixedThreadPool(2);
            try {
                Future<LiveMatchSession> session1 = sessions.submit(() -> liveMatches.createInteractiveSession(homeTeam, awayTeam, 80, 80, 99, 1, 7, false));
                Future<LiveMatchSession> session2 = sessions.submit(() -> liveMatches.createInteractiveSession(homeTeam, awayTeam, 80, 80, 99, 1, 7, false));
                assertSame(session1.get(15, TimeUnit.SECONDS), session2.get(15, TimeUnit.SECONDS), "one interactive session per fixture key");
            } finally {
                sessions.shutdownNow();
            }
            String liveMatchKey = LiveMatchSimulationService.buildKey(99, 1, 7, homeTeam, awayTeam);
            assertEquals(liveMatchKey, LiveMatchSimulationService.buildKey(99, 1, 7, homeTeam, awayTeam));
            assertEquals(p1.getId(), p2.getId(), "both managers adopt the same plan");
            assertEquals(1, plans.count());
            assertEquals(MatchPlan.Status.PLANNED, plans.findByFixtureKey(fixtureKey).orElseThrow().getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void controllerRejectsSubstitutionForTheAdversaryTeam() {
        var live = mock(com.footballmanagergamesimulator.service.LiveMatchSimulationService.class);
        var session = mock(com.footballmanagergamesimulator.service.LiveMatchSession.class);
        var context = mock(com.footballmanagergamesimulator.user.UserContext.class);
        when(live.getSessionOrRecover("99_1_7_" + homeTeam + "_" + awayTeam)).thenReturn(session);
        when(session.teamIdForPlayer(9001L)).thenReturn(awayTeam);
        when(session.teamIdForPlayer(9002L)).thenReturn(awayTeam);
        when(context.getTeamIdOrNull(null)).thenReturn(homeTeam);

        com.footballmanagergamesimulator.controller.MatchController controller = new com.footballmanagergamesimulator.controller.MatchController();
        ReflectionTestUtils.setField(controller, "liveMatchSimulationService", live);
        ReflectionTestUtils.setField(controller, "userContext", context);
        var body = new com.footballmanagergamesimulator.controller.MatchController.SubstituteRequest();
        body.playerOutId = 9001L; body.playerInId = 9002L;
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.substituteInLiveMatch("99_1_7_" + homeTeam + "_" + awayTeam, body));
    }

    private MatchPlan prepare(CountDownLatch start) throws Exception {
        start.await();
        return planService.prepareLivePlan(fixtureKey, 99, 1, 7, homeTeam, awayTeam,
                lineup(homeTeam), lineup(awayTeam), 1, 0);
    }

    private Lineup lineup(long base) {
        List<Contributor> starters = humans.findAllByTeamIdAndTypeId(base, 1L).stream().limit(11)
                .map(h -> new Contributor(h.getId(), h.getName(), h.getPosition(), h.getRating(), 15, 15, 15, 100, false, false)).toList();
        return new Lineup(starters, List.of());
    }

    private User managerUser(long teamId, String suffix) {
        User user = new User(); user.setUsername(suffix); user.setEmail(suffix + "@test.invalid"); user.setPassword("test"); user.setTeamId(teamId); user.setActive(true); return user;
    }

    private void seedManager(long teamId, String name) {
        Human manager = new Human(); manager.setTeamId(teamId); manager.setTypeId(2); manager.setName(name); manager.setPosition("MANAGER"); manager = humans.saveAndFlush(manager); createdHumans.add(manager);
        long managerId = manager.getId();
        users.findAllByTeamId(teamId).forEach(user -> { user.setManagerId(managerId); users.save(user); });
        for (int i = 0; i < 11; i++) {
            Human player = new Human(); player.setTeamId(teamId); player.setTypeId(1); player.setName(name + " player " + i); player.setPosition(i == 0 ? "GK" : "MC"); player.setFitness(100); player.setRating(10); createdHumans.add(player); humans.save(player);
        }
        humans.flush();
    }
}
