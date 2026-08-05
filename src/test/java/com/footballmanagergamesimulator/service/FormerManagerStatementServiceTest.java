package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormerManagerStatementServiceTest {

    @Test
    void formerCoachCommentsButCurrentCoachIsExcluded() {
        ManagerHistoryRepository history = mock(ManagerHistoryRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FormerManagerStatementService service = new FormerManagerStatementService(history, humans, inbox);

        ManagerHistory formerSeason = season(30, "Old Coach", 86, 4, 38, 24, "League");
        ManagerHistory currentSeason = season(31, "Current Coach", 86, 5, 38, 28, "Cup");
        Human formerCoach = new Human(); formerCoach.setId(30); formerCoach.setName("Old Coach");
        formerCoach.setTeamId(99L); formerCoach.setTacticStyle("4-3-3 possession");
        Human currentCoach = new Human(); currentCoach.setId(31); currentCoach.setName("Current Coach");
        currentCoach.setTeamId(86L);
        when(history.findAllByTeamId(86)).thenReturn(List.of(formerSeason, currentSeason));
        when(humans.findAllById(any())).thenReturn(List.of(formerCoach, currentCoach));

        service.publishPostMatchStatement(86, "Sherlock FC", 90, "Rivals", 0, 3,
                "First Division", 6, 14);

        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("MEDIA_FORMER_MANAGER");
        assertThat(captor.getValue().getTitle()).contains("Old Coach").doesNotContain("Current Coach");
        assertThat(captor.getValue().getContent()).contains("Former Sherlock FC manager")
                .contains("personal analysis").contains("4-3-3 possession");
    }

    private ManagerHistory season(long managerId, String name, long teamId, int season,
                                  int games, int wins, String trophies) {
        ManagerHistory row = new ManagerHistory();
        row.setManagerId(managerId); row.setManagerName(name); row.setTeamId(teamId);
        row.setSeasonNumber(season); row.setGamesPlayed(games); row.setWins(wins);
        row.setTrophiesWon(trophies);
        return row;
    }
}
