package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinimumSquadServiceTest {

    private MinimumSquadService service;
    private TeamRepository teams;
    private HumanRepository humans;
    private YouthPlayerRepository youthPlayers;
    private ManagerInboxRepository inbox;
    private YouthAcademyService academy;

    @BeforeEach
    void setUp() {
        service = new MinimumSquadService();
        teams = mock(TeamRepository.class);
        humans = mock(HumanRepository.class);
        youthPlayers = mock(YouthPlayerRepository.class);
        inbox = mock(ManagerInboxRepository.class);
        academy = mock(YouthAcademyService.class);

        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "youthPlayerRepository", youthPlayers);
        ReflectionTestUtils.setField(service, "playerSkillsRepository", mock(PlayerSkillsRepository.class));
        ReflectionTestUtils.setField(service, "historicalRelationRepository",
                mock(TeamPlayerHistoricalRelationRepository.class));
        ReflectionTestUtils.setField(service, "managerInboxRepository", inbox);
        ReflectionTestUtils.setField(service, "youthAcademyService", academy);
        ReflectionTestUtils.setField(service, "competitionService", new CompetitionService());
        StaffService staff = mock(StaffService.class);
        when(staff.getHOYDQuality(anyLong())).thenReturn(10);
        ReflectionTestUtils.setField(service, "staffService", staff);
        UserContext userContext = mock(UserContext.class);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(1L));
        ReflectionTestUtils.setField(service, "userContext", userContext);

        AtomicLong idSequence = new AtomicLong(1_000);
        when(humans.saveAll(any())).thenAnswer(invocation -> {
            List<Human> saved = new ArrayList<>();
            for (Human player : (Iterable<Human>) invocation.getArgument(0)) {
                if (player.getId() == 0) player.setId(idSequence.incrementAndGet());
                saved.add(player);
            }
            return saved;
        });
    }

    @Test
    void promotesBestExistingProspectsAndGeneratesOnlyTheRemainingDeficit() {
        Team humanTeam = team(1);
        Team aiTeam = team(2);
        Team completeTeam = team(3);
        when(teams.findAll()).thenReturn(List.of(humanTeam, aiTeam, completeTeam));

        List<Human> currentPlayers = new ArrayList<>();
        addPlayers(currentPlayers, humanTeam.getId(), 16);
        addPlayers(currentPlayers, aiTeam.getId(), 17);
        addPlayers(currentPlayers, completeTeam.getId(), 18);
        when(humans.findAllByTypeId(1)).thenReturn(currentPlayers);

        YouthPlayer stronger = prospect(11, humanTeam.getId(), "Stronger", 52, 70);
        YouthPlayer weaker = prospect(10, humanTeam.getId(), "Weaker", 40, 80);
        when(youthPlayers.findAll()).thenReturn(List.of(weaker, stronger));
        YouthPlayer generated = prospect(0, aiTeam.getId(), "Generated", 35, 65);
        when(academy.createProspect(anyLong(), anyInt(), any(), anyInt()))
                .thenReturn(generated);

        MinimumSquadService.CompletionSummary summary = service.ensureMinimumSquads(14);

        assertEquals(3, summary.promotedPlayers());
        assertEquals(1, summary.generatedProspects());
        assertEquals(2, summary.affectedTeams());
        assertEquals(18, summary.minimumSquadSize());
        assertTrue(humanTeam.getSalaryBudget() > 0);
        assertTrue(aiTeam.getSalaryBudget() > 0);

        ArgumentCaptor<Iterable<YouthPlayer>> promotedCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(youthPlayers).saveAll(promotedCaptor.capture());
        List<YouthPlayer> promoted = new ArrayList<>();
        promotedCaptor.getValue().forEach(promoted::add);
        assertEquals(List.of("Stronger", "Weaker", "Generated"),
                promoted.stream().map(YouthPlayer::getName).toList());
        assertTrue(promoted.stream().allMatch(player -> "PROMOTED".equals(player.getStatus())));
        assertTrue(promoted.stream().allMatch(player -> player.getPlayerId() > 0));

        ArgumentCaptor<Iterable<ManagerInbox>> inboxCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(inbox).saveAll(inboxCaptor.capture());
        List<ManagerInbox> notifications = new ArrayList<>();
        inboxCaptor.getValue().forEach(notifications::add);
        assertEquals(1, notifications.size());
        assertEquals(humanTeam.getId(), notifications.get(0).getTeamId());
    }

    private Team team(long id) {
        Team team = new Team();
        team.setId(id);
        return team;
    }

    private void addPlayers(List<Human> collector, long teamId, int count) {
        for (int index = 0; index < count; index++) {
            Human player = new Human();
            player.setId(teamId * 100 + index);
            player.setTypeId(1);
            player.setTeamId(teamId);
            player.setShirtNumber(index + 1);
            collector.add(player);
        }
    }

    private YouthPlayer prospect(long id, long teamId, String name,
                                 int currentAbility, int potentialAbility) {
        YouthPlayer prospect = new YouthPlayer();
        prospect.setId(id);
        prospect.setTeamId(teamId);
        prospect.setName(name);
        prospect.setAge(17);
        prospect.setPosition("MC");
        prospect.setCurrentAbility(currentAbility);
        prospect.setPotentialAbility(potentialAbility);
        prospect.setStatus("IN_ACADEMY");
        return prospect;
    }
}
