package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Mock
    private CompetitionService competitionService;

    @Mock
    private PlayerSkillsRepository playerSkillsRepository;

    @Mock
    private ScorerLeaderboardRepository scorerLeaderboardRepository;

    @Mock
    private CompositeNameGenerator compositeNameGenerator;

    @Mock
    private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;

    @Mock
    private StaffService staffService;

    @Mock
    private MatchEngineConfig engineConfig;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // FacilityTraining reads the training config; give it real defaults.
        when(engineConfig.getTraining()).thenReturn(new MatchEngineConfig.Training());
    }

    @Test
    void testTrainPlayer() {
        Human human = new Human();
        human.setCurrentStatus("Junior");
        human.setRating(50.0);
        human.setAge(20);

        TeamFacilities teamFacilities = new TeamFacilities();
        teamFacilities.setYouthTrainingLevel(10L);
        teamFacilities.setSeniorTrainingLevel(10L);

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
        verify(humanRepository, times(1)).saveAll(anyList());
    }

    @Test
    void assignShirtNumbers_bestGkGetsOne() {
        List<Human> squad = new ArrayList<>();
        squad.add(playerWithPositionAndRating(1L, "GK", 60));
        squad.add(playerWithPositionAndRating(2L, "GK", 80)); // higher-rated GK
        squad.add(playerWithPositionAndRating(3L, "DC", 70));

        HumanService.assignShirtNumbers(squad);

        assertEquals(1, find(squad, 2L).getShirtNumber(), "best GK gets shirt 1");
        assertNotEquals(1, find(squad, 1L).getShirtNumber(), "backup GK shouldn't take 1");
    }

    @Test
    void assignShirtNumbers_classicPositionMapping() {
        // One player per "classic" position — each should get the canonical
        // shirt for that role (1=GK, 2=DR, 3=DL, 4=DC, 7=MR, 8=MC, 9=ST, 11=ML).
        List<Human> squad = new ArrayList<>();
        squad.add(playerWithPositionAndRating(1L, "GK", 70));
        squad.add(playerWithPositionAndRating(2L, "DR", 70));
        squad.add(playerWithPositionAndRating(3L, "DL", 70));
        squad.add(playerWithPositionAndRating(4L, "DC", 70));
        squad.add(playerWithPositionAndRating(5L, "MR", 70));
        squad.add(playerWithPositionAndRating(6L, "MC", 70));
        squad.add(playerWithPositionAndRating(7L, "ST", 70));
        squad.add(playerWithPositionAndRating(8L, "ML", 70));

        HumanService.assignShirtNumbers(squad);

        assertEquals(1,  find(squad, 1L).getShirtNumber());
        assertEquals(2,  find(squad, 2L).getShirtNumber());
        assertEquals(3,  find(squad, 3L).getShirtNumber());
        assertEquals(4,  find(squad, 4L).getShirtNumber());
        assertEquals(7,  find(squad, 5L).getShirtNumber());
        assertEquals(8,  find(squad, 6L).getShirtNumber());
        assertEquals(9,  find(squad, 7L).getShirtNumber());
        assertEquals(11, find(squad, 8L).getShirtNumber());
    }

    @Test
    void assignShirtNumbers_noDuplicatesInLargeSquad() {
        // Full 22-man squad — every player should walk away with a unique
        // shirt number, regardless of position overlap.
        List<Human> squad = new ArrayList<>();
        String[] positions = {"GK","GK","DL","DL","DR","DR","DC","DC","DC","DC",
                              "ML","ML","MR","MR","MC","MC","MC","MC","ST","ST","ST","ST"};
        for (int i = 0; i < positions.length; i++) {
            squad.add(playerWithPositionAndRating(100L + i, positions[i], 60 + i));
        }

        HumanService.assignShirtNumbers(squad);

        Set<Integer> seen = new HashSet<>();
        for (Human p : squad) {
            assertTrue(p.getShirtNumber() > 0, "every player should get a positive shirt");
            assertTrue(seen.add(p.getShirtNumber()),
                    "duplicate shirt " + p.getShirtNumber() + " for " + p.getName());
        }
    }

    @Test
    void assignShirtNumbers_backfillPreservesExisting() {
        // Mix of players with shirts already + players with 0 — only the
        // 0-shirts should be assigned, and they must not clash with locked
        // shirts.
        List<Human> squad = new ArrayList<>();
        Human locked = playerWithPositionAndRating(1L, "GK", 80);
        locked.setShirtNumber(7);  // weird but locked
        squad.add(locked);
        squad.add(playerWithPositionAndRating(2L, "DR", 70));   // shirt 0
        squad.add(playerWithPositionAndRating(3L, "DL", 70));   // shirt 0

        HumanService.assignShirtNumbers(squad);

        assertEquals(7, find(squad, 1L).getShirtNumber(), "locked shirt preserved");
        int newDR = find(squad, 2L).getShirtNumber();
        int newDL = find(squad, 3L).getShirtNumber();
        assertTrue(newDR > 0 && newDR != 7, "DR avoids locked shirt");
        assertTrue(newDL > 0 && newDL != 7, "DL avoids locked shirt");
        assertNotEquals(newDR, newDL, "no duplicate among backfilled players");
    }

    @Test
    void assignShirtNumbers_emptyOrNull_noThrow() {
        // Smoke test — defensive against weird inputs from callers.
        assertDoesNotThrow(() -> HumanService.assignShirtNumbers(null));
        assertDoesNotThrow(() -> HumanService.assignShirtNumbers(new ArrayList<>()));
    }

    private static Human playerWithPositionAndRating(long id, String position, double rating) {
        Human h = new Human();
        h.setId(id);
        h.setName(position + "_" + id);
        h.setPosition(position);
        h.setRating(rating);
        return h;
    }

    private static Human find(List<Human> squad, long id) {
        return squad.stream().filter(h -> h.getId() == id).findFirst().orElseThrow();
    }

    @Test
    void testAddRegens() {
        TeamFacilities teamFacilities = new TeamFacilities();
        teamFacilities.setYouthAcademyLevel(5L);

        Round round = new Round();
        round.setId(1L);
        round.setSeason(1L);

        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
        // Regens are now persisted in a single batched saveAll (insert-batching enabled).
        when(humanRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        humanService.addRegens(teamFacilities, 1L);

        verify(humanRepository, atLeast(1)).saveAll(anyList());
    }
}
