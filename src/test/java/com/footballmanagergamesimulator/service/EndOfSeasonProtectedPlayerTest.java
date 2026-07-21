package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndOfSeasonProtectedPlayerTest {

    @Test
    void protectedPlayerRetiresInsteadOfEnteringFreeAgencyWhenContractEnds() {
        EndOfSeasonProcessor processor = new EndOfSeasonProcessor();
        HumanRepository humans = mock(HumanRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        ManagerInboxRepository inboxes = mock(ManagerInboxRepository.class);
        UserContext users = mock(UserContext.class);
        TransferOfferLifecycleService offers = mock(TransferOfferLifecycleService.class);
        ScorerLeaderboardSyncService leaderboard = mock(ScorerLeaderboardSyncService.class);

        Human player = new Human();
        player.setId(10);
        player.setTypeId(1);
        player.setTeamId(3L);
        player.setName("Saviola");
        player.setPosition("AMC");
        player.setRating(300);
        player.setWage(2_000);
        player.setContractEndSeason(6);
        player.setWillNeverLeave(true);

        Team team = new Team();
        team.setId(3);
        team.setSalaryBudget(20_000);

        when(humans.findAllByTypeId(1L)).thenReturn(List.of(player));
        when(users.getAllHumanTeamIds()).thenReturn(List.of(3L));
        when(teams.findById(3L)).thenReturn(Optional.of(team));

        ReflectionTestUtils.setField(processor, "humanRepository", humans);
        ReflectionTestUtils.setField(processor, "teamRepository", teams);
        ReflectionTestUtils.setField(processor, "managerInboxRepository", inboxes);
        ReflectionTestUtils.setField(processor, "userContext", users);
        ReflectionTestUtils.setField(processor, "transferOfferLifecycleService", offers);
        ReflectionTestUtils.setField(processor, "scorerLeaderboardSyncService", leaderboard);

        processor.handleContractExpiries(6);

        assertTrue(player.isRetired());
        assertNull(player.getTeamId());
        assertEquals("Retired", player.getCurrentStatus());
        assertEquals(0, player.getContractEndSeason());
        assertEquals(18_000, team.getSalaryBudget());
        verify(inboxes).save(any(ManagerInbox.class));
        verify(offers).removeActiveOffersForPlayer(player.getId());
        verify(leaderboard).trackNewPlayer(player);
    }
}
