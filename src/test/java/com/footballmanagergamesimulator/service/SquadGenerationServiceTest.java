package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SquadGenerationService}. The repos are mocked so the
 * service runs end-to-end (build → save → relation → skills → recompute →
 * shirt-assign → batch-save) and we assert on the resulting in-memory squad
 * and on what was persisted, without standing up Spring or a real DB.
 */
class SquadGenerationServiceTest {

    private SquadGenerationService service;
    private HumanRepository humanRepository;
    private TeamPlayerHistoricalRelationRepository historicalRepo;
    private PlayerSkillsRepository playerSkillsRepository;
    private CompetitionService competitionService;
    private CompositeNameGenerator nameGen;

    @BeforeEach
    void setUp() throws Exception {
        service = new SquadGenerationService();
        humanRepository = mock(HumanRepository.class);
        historicalRepo = mock(TeamPlayerHistoricalRelationRepository.class);
        playerSkillsRepository = mock(PlayerSkillsRepository.class);
        competitionService = mock(CompetitionService.class);
        nameGen = mock(CompositeNameGenerator.class);

        inject("humanRepository", humanRepository);
        inject("teamPlayerHistoricalRelationRepository", historicalRepo);
        inject("playerSkillsRepository", playerSkillsRepository);
        inject("competitionService", competitionService);
        inject("compositeNameGenerator", nameGen);

        // Default name: unique per call so the squad has distinct names.
        AtomicLong nameCounter = new AtomicLong();
        when(nameGen.generateName(anyLong())).thenAnswer(inv -> "Player_" + nameCounter.incrementAndGet());

        // Assign a fresh ID to every saved Human so the service's two-pass
        // save (first for the ID, then in batch with shirt numbers) behaves
        // like the real JPA repo.
        AtomicLong idCounter = new AtomicLong();
        when(humanRepository.save(any(Human.class))).thenAnswer(inv -> {
            Human h = inv.getArgument(0);
            if (h.getId() == 0) h.setId(idCounter.incrementAndGet());
            return h;
        });
    }

    @Test
    void generateInitialSquad_producesTwentyTwoPlayersWithExpectedPositions() {
        List<Human> squad = service.generateInitialSquad(team(1L, 5L), facilities(10L), 1, 70, new Random(0));

        assertEquals(22, squad.size(), "squad size should be 22");
        assertEquals(2, countByPosition(squad, "GK"));
        assertEquals(2, countByPosition(squad, "DL"));
        assertEquals(2, countByPosition(squad, "DR"));
        assertEquals(4, countByPosition(squad, "DC"));
        assertEquals(2, countByPosition(squad, "ML"));
        assertEquals(2, countByPosition(squad, "MR"));
        assertEquals(4, countByPosition(squad, "MC"));
        assertEquals(4, countByPosition(squad, "ST"));
    }

    @Test
    void generateInitialSquad_setsMoraleFromCaller() {
        List<Human> squad70 = service.generateInitialSquad(team(1L, 5L), facilities(10L), 1, 70, new Random(0));
        List<Human> squad100 = service.generateInitialSquad(team(2L, 5L), facilities(10L), 1, 100, new Random(0));

        assertTrue(squad70.stream().allMatch(h -> h.getMorale() == 70), "all 70-morale squad should be 70");
        assertTrue(squad100.stream().allMatch(h -> h.getMorale() == 100), "all 100-morale squad should be 100");
    }

    @Test
    void generateInitialSquad_assignsUniqueShirtNumbers() {
        List<Human> squad = service.generateInitialSquad(team(1L, 5L), facilities(10L), 1, 70, new Random(42));

        // Best GK should wear shirt 1 — sanity check that assignShirtNumbers
        // ran. Then no duplicates across the full squad.
        boolean someoneWearsOne = squad.stream().anyMatch(h -> h.getShirtNumber() == 1);
        assertTrue(someoneWearsOne, "someone (the best GK) should wear shirt 1");

        java.util.Set<Integer> shirts = new java.util.HashSet<>();
        for (Human h : squad) {
            assertTrue(h.getShirtNumber() > 0, "every player gets a positive shirt");
            assertTrue(shirts.add(h.getShirtNumber()), "duplicate shirt " + h.getShirtNumber());
        }
    }

    @Test
    void generateInitialSquad_persistsHistoricalRelationAndSkillsPerPlayer() {
        service.generateInitialSquad(team(1L, 5L), facilities(10L), 1, 70, new Random(0));

        verify(historicalRepo, times(22)).save(any(TeamPlayerHistoricalRelation.class));
        verify(playerSkillsRepository, times(22)).save(any(PlayerSkills.class));
        // 22 first-pass saves (one per player to get the ID) + 1 batched
        // saveAll(squad) after shirt-number assignment.
        verify(humanRepository, times(22)).save(any(Human.class));
        verify(humanRepository, times(1)).saveAll(any());
    }

    @Test
    void generateInitialSquad_nullFacilities_fallsBackToDefaultReputation() {
        // No exception. Team reputation (5 in the fixture) drives target rating
        // on the 1-300 scale: targetRating = 25 + 5/10000 * 250 ≈ 25, then
        // ±25 spread per player. Facilities are no longer consulted for rating.
        List<Human> squad = service.generateInitialSquad(team(1L, 5L), null, 1, 70, new Random(0));
        assertEquals(22, squad.size());
    }

    @Test
    void generateInitialSquad_recomputesRatingFromSkills() {
        // The mocked generateSkills leaves attributes at default (0), so
        // PlayerSkillsService.computeOverallRating floors near 1 — well below
        // the initial-seed random window. We assert the post-generation
        // rating is well under that window, proving the recompute path ran.
        doNothing().when(competitionService).generateSkills(any(PlayerSkills.class), anyDouble());

        List<Human> squad = service.generateInitialSquad(team(1L, 5L), facilities(10L), 1, 70, new Random(0));

        for (Human h : squad) {
            assertTrue(h.getRating() < 10.0,
                    "recomputed rating from empty skills should floor near 1; got " + h.getRating());
        }
    }

    // ---------------- fixtures ----------------

    private static Team team(long id, long competitionId) {
        Team t = new Team();
        t.setId(id);
        t.setCompetitionId(competitionId);
        t.setName("Team " + id);
        return t;
    }

    private static TeamFacilities facilities(long seniorTrainingLevel) {
        TeamFacilities f = new TeamFacilities();
        f.setSeniorTrainingLevel(seniorTrainingLevel);
        return f;
    }

    private static long countByPosition(List<Human> squad, String position) {
        return squad.stream().filter(h -> position.equals(h.getPosition())).count();
    }

    private void inject(String fieldName, Object value) throws Exception {
        var field = SquadGenerationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
