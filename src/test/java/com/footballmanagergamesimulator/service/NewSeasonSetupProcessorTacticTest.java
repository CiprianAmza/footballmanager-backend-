package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewSeasonSetupProcessorTacticTest {

    @Test
    void newSeasonKeepsHumanAndHandAuthoredTacticsButClearsOrdinaryAiTactics() {
        NewSeasonSetupProcessor processor = new NewSeasonSetupProcessor();
        TeamRepository teams = mock(TeamRepository.class);
        PersonalizedTacticRepository tactics = mock(PersonalizedTacticRepository.class);
        UserContext users = mock(UserContext.class);
        ReflectionTestUtils.setField(processor, "teamRepository", teams);
        ReflectionTestUtils.setField(processor, "personalizedTacticRepository", tactics);
        ReflectionTestUtils.setField(processor, "userContext", users);

        Team inazuma = team(87L, "Inazuma Japan");
        Team athletic = team(66L, "Athletic Sohatu");
        Team human = team(12L, "Human FC");
        Team ordinaryAi = team(44L, "Ordinary AI");
        PersonalizedTactic inazumaTactic = tactic(87L, "31411");
        PersonalizedTactic athleticTactic = tactic(66L, "442");
        PersonalizedTactic humanTactic = tactic(12L, "433");
        PersonalizedTactic ordinaryAiTactic = tactic(44L, "4231");

        when(users.getAllHumanTeamIds()).thenReturn(List.of(human.getId()));
        when(teams.findAll()).thenReturn(List.of(inazuma, athletic, human, ordinaryAi));
        when(tactics.findAll()).thenReturn(List.of(
                inazumaTactic, athleticTactic, humanTactic, ordinaryAiTactic));

        processor.clearAiPersonalizedTactics();

        verify(tactics).deleteAll(List.of(ordinaryAiTactic));
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private PersonalizedTactic tactic(long teamId, String formation) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setTeamId(teamId);
        tactic.setTactic(formation);
        return tactic;
    }
}
