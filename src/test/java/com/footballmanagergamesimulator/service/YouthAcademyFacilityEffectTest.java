package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouthAcademyFacilityEffectTest {

    @Test
    void academyLevelImprovesTheGeneratedIntakeInsteadOfBeingCosmetic() {
        YouthAcademyService service = new YouthAcademyService();
        TeamFacilitiesRepository facilities = mock(TeamFacilitiesRepository.class);
        CompositeNameGenerator names = mock(CompositeNameGenerator.class);
        ReflectionTestUtils.setField(service, "teamFacilitiesRepository", facilities);
        ReflectionTestUtils.setField(service, "compositeNameGenerator", names);
        when(names.generateName(1L)).thenReturn("Prospect");
        when(facilities.findByTeamId(1L)).thenReturn(facilities(1));
        when(facilities.findByTeamId(2L)).thenReturn(facilities(10));

        YouthPlayer basic = service.createProspect(1, 4, new Random(1234), 10);
        YouthPlayer elite = service.createProspect(2, 4, new Random(1234), 10);

        assertTrue(elite.getPotentialAbility() > basic.getPotentialAbility());
        assertTrue(elite.getPotentialAbility() <= 99);
    }

    @Test
    void cannotReleaseAProspectOwnedByAnotherAcademy() {
        YouthAcademyService service = new YouthAcademyService();
        YouthPlayerRepository players = mock(YouthPlayerRepository.class);
        ReflectionTestUtils.setField(service, "youthPlayerRepository", players);
        YouthPlayer player = new YouthPlayer();
        player.setId(7);
        player.setTeamId(99);
        player.setStatus("IN_ACADEMY");
        when(players.findById(7L)).thenReturn(Optional.of(player));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.releaseYouthPlayer(7, 1));
        assertEquals("Youth player does not belong to this academy", error.getMessage());
    }

    private TeamFacilities facilities(int level) {
        TeamFacilities value = new TeamFacilities();
        value.setYouthAcademyLevel(level);
        value.setYouthTrainingLevel(level);
        return value;
    }
}
