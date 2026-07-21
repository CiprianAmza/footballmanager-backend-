package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewSeasonPlayerReadinessServiceTest {

    @Mock HumanRepository humanRepository;
    @InjectMocks NewSeasonPlayerReadinessService service;

    @Test
    void resetsEveryActiveClubPlayerToEightyMoraleAndFitness() {
        Human tired = player(31, 42);
        Human fullyFit = player(100, 96);
        List<Human> activeTeamPlayers = List.of(tired, fullyFit);
        when(humanRepository.findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNull(TypeNames.PLAYER_TYPE))
                .thenReturn(activeTeamPlayers);

        assertEquals(2, service.resetActiveTeamPlayers());

        for (Human player : activeTeamPlayers) {
            assertEquals(80.0, player.getMorale());
            assertEquals(80.0, player.getFitness());
        }
        verify(humanRepository).saveAll(activeTeamPlayers);
    }

    private static Human player(double fitness, double morale) {
        Human player = new Human();
        player.setFitness(fitness);
        player.setMorale(morale);
        return player;
    }
}
