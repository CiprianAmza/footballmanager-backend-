package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HumanServiceTest {

    @InjectMocks
    private HumanService humanService;

    @Mock
    private HumanRepository humanRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private RoundRepository roundRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testTrainPlayer() {
        Human human = new Human();
        human.setCurrentStatus("Junior");
        human.setRating(50.0);

        TeamFacilities teamFacilities = new TeamFacilities();
        teamFacilities.setYouthTrainingLevel(10L);

        Human result = humanService.trainPlayer(human, teamFacilities, 1);

        assertTrue(result.getRating() >= 50.0); // Ensure rating doesn't decrease
    }

    @Test
    void testRetirePlayers() {
        Human human1 = new Human();
        human1.setAge(35);
        human1.setTypeId(TypeNames.PLAYER_TYPE);

        Human human2 = new Human();
        human2.setAge(33);
        human2.setTypeId(TypeNames.PLAYER_TYPE);

        List<Human> humans = List.of(human1, human2);

        when(humanRepository.findAll()).thenReturn(humans);

        humanService.retirePlayers();

        verify(humanRepository, atMost(1)).delete(human1);
        verify(humanRepository, never()).delete(human2);
    }

    @Test
    void testAddOneYearToAge() {
        Human human1 = new Human();
        human1.setAge(20);

        Human human2 = new Human();
        human2.setAge(25);

        List<Human> humans = List.of(human1, human2);

        when(humanRepository.findAll()).thenReturn(humans);

        humanService.addOneYearToAge();

        assertEquals(21, human1.getAge());
        assertEquals(26, human2.getAge());
        verify(humanRepository, times(2)).save(any(Human.class));
    }

    @Test
    void testAddRegens() {
        TeamFacilities teamFacilities = new TeamFacilities();
        teamFacilities.setYouthAcademyLevel(5L);

        Round round = new Round();
        round.setId(1L);
        round.setSeason(1L);

        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));

        humanService.addRegens(teamFacilities, 1L);

        verify(humanRepository, times(1)).save(any(Human.class));
    }
}
