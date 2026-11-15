package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NationalIdentityLifecycleTest {
    @Test
    void regensKeepTheirClubCultureAndReceiveAFaceAfterTheirIdExists() {
        HumanService service = new HumanService();
        NationService nations = mock(NationService.class);
        CompositeNameGenerator names = mock(CompositeNameGenerator.class);
        when(nations.nationIdForTeam(901L)).thenReturn(12L);
        when(names.generateNameForNation(12L)).thenReturn("Tovan Drazel");
        ReflectionTestUtils.setField(service, "nationService", nations);
        ReflectionTestUtils.setField(service, "compositeNameGenerator", names);
        ReflectionTestUtils.setField(service, "faceGenerator", new FaceGenerator());
        ReflectionTestUtils.setField(service, "competitionService", new CompetitionService());
        TeamFacilities facilities = new TeamFacilities();
        facilities.setYouthAcademyLevel(5);
        List<Human> regens = new ArrayList<>();
        service.collectRegens(facilities, 901, 4, regens);
        Human player = regens.get(0);
        assertEquals("Tovan Drazel", player.getName());
        player.setId(12_345L); // saveAll has now assigned the real id
        service.buildRegenSkillsAndRelations(regens, 4, new ArrayList<>(), new ArrayList<>());
        Human expected = new Human();
        expected.setId(12_345L);
        new FaceGenerator().assignFace(expected, 12);
        assertEquals("branik", player.getSpecies());
        assertEquals(expected.getBaseFaceId(), player.getBaseFaceId());
        assertEquals(expected.getHairStyle(), player.getHairStyle());
        verify(names).generateNameForNation(12L);
    }

    @Test
    void academyIntakeUsesTheNationOfItsActualClub() {
        YouthAcademyService service = new YouthAcademyService();
        NationService nations = mock(NationService.class);
        CompositeNameGenerator names = mock(CompositeNameGenerator.class);
        TeamFacilitiesRepository facilities = mock(TeamFacilitiesRepository.class);
        when(nations.nationIdForTeam(1001L)).thenReturn(13L);
        when(names.generateNameForNation(13L)).thenReturn("Eshai Xorun");
        TeamFacilities academy = new TeamFacilities();
        academy.setYouthAcademyLevel(5);
        when(facilities.findByTeamId(1001L)).thenReturn(academy);
        ReflectionTestUtils.setField(service, "nationService", nations);
        ReflectionTestUtils.setField(service, "compositeNameGenerator", names);
        ReflectionTestUtils.setField(service, "teamFacilitiesRepository", facilities);
        YouthPlayer prospect = service.createProspect(1001, 4, new Random(1), 10);
        assertEquals("Eshai Xorun", prospect.getName());
        assertEquals(1001L, prospect.getTeamId());
        verify(names).generateNameForNation(13L);
        verify(names, never()).generateName(1L);
    }

    @Test
    void initialClubStaffReceiveNationalNamesAndSavedIdBasedFaces() {
        StaffService service = new StaffService();
        NationService nations = mock(NationService.class);
        CompositeNameGenerator names = mock(CompositeNameGenerator.class);
        HumanRepository people = mock(HumanRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(nations.nationIdForTeam(801L)).thenReturn(11L);
        when(names.generateNameForNation(11L)).thenReturn("Maelis Orava");
        Team team = new Team();
        team.setReputation(5000);
        when(teams.findById(801L)).thenReturn(Optional.of(team));
        AtomicLong sequence = new AtomicLong(500);
        List<Human> saved = new ArrayList<>();
        when(people.save(any(Human.class))).thenAnswer(call -> {
            Human person = call.getArgument(0);
            if (person.getId() == 0) {
                person.setId(sequence.incrementAndGet());
                saved.add(person);
            }
            return person;
        });
        ReflectionTestUtils.setField(service, "nationService", nations);
        ReflectionTestUtils.setField(service, "compositeNameGenerator", names);
        ReflectionTestUtils.setField(service, "humanRepository", people);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "faceGenerator", new FaceGenerator());
        service.generateInitialStaff(801, 1);
        assertEquals(7, saved.size());
        assertTrue(saved.stream().allMatch(h -> "veyran".equals(h.getSpecies())));
        assertTrue(saved.stream().allMatch(h -> "Maelis Orava".equals(h.getName())));
        verify(names, times(7)).generateNameForNation(11);
    }
}
