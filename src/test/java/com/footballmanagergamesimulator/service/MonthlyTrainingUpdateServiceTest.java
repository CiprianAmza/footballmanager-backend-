package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TrainingUpdateMarker;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TrainingUpdateMarkerRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonthlyTrainingUpdateServiceTest {

    private static final int SEASON = 3;
    private static final int SEP_1 = 32;

    /** Fully trained (q = 1 on every type), so these tests see the plain monthly mechanics. */
    private final MonthlyTrainingUpdateService service = new MonthlyTrainingUpdateService() {
        @Override
        Map<TrainingType, Double> qByType(Human player, com.footballmanagergamesimulator.model.TeamFacilities facilities,
                                          Map<TrainingType, Double> coaches) {
            Map<TrainingType, Double> full = new java.util.EnumMap<>(TrainingType.class);
            for (TrainingType type : TrainingType.values()) full.put(type, 1.0);
            return full;
        }
    };
    private final List<TrainingUpdateMarker> markers = new ArrayList<>();
    private final Map<Long, PlayerSkills> skills = new HashMap<>();

    @BeforeEach
    void setUp() {
        TrainingUpdateSchedule schedule = new TrainingUpdateSchedule();
        schedule.setUpdateDayOfMonth(1);
        service.schedule = schedule;

        service.markerRepository = mock(TrainingUpdateMarkerRepository.class);
        when(service.markerRepository.findAll()).thenAnswer(invocation -> new ArrayList<>(markers));
        when(service.markerRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<TrainingUpdateMarker> saved = invocation.getArgument(0);
            saved.forEach(marker -> { if (!markers.contains(marker)) markers.add(marker); });
            return saved;
        });

        service.teamRepository = mock(TeamRepository.class);
        when(service.teamRepository.findAll()).thenReturn(List.of(team(1L, 100L), team(2L, 100L), team(3L, 200L)));

        service.counterService = mock(TrainingCounterService.class);
        service.aiTrainingCounters = new AiTrainingCounters(engineConfig());
        service.humanRepository = mock(HumanRepository.class);
        service.playerSkillsRepository = mock(PlayerSkillsRepository.class);
        when(service.playerSkillsRepository.findAllByPlayerIdIn(any()))
                .thenAnswer(invocation -> new ArrayList<>(skills.values()));
        service.competitionService = mock(CompetitionService.class);
        service.matchSimulationOrchestrator = mock(MatchSimulationOrchestrator.class);
        service.trainingQuality = new TrainingQuality();
        service.delegationService = mock(TrainingDelegationService.class);
        service.facilitiesRepository = mock(com.footballmanagergamesimulator.repository.TeamFacilitiesRepository.class);
    }

    private static Team team(long id, long league) {
        Team team = new Team();
        team.setId(id);
        team.setCompetitionId(league);
        return team;
    }

    private Human player(long id, long teamId, String position, int age) {
        Human player = new Human();
        player.setId(id);
        player.setTeamId(teamId);
        player.setPosition(position);
        player.setAge(age);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        PlayerSkills attributes = new PlayerSkills();
        attributes.setPlayerId(id);
        attributes.setPosition(position);
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(attributes, 10));
        skills.put(id, attributes);
        return player;
    }

    private static CompartmentEngineConfig engineConfig() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            new YamlPropertySourceLoader()
                    .load("weights", new ClassPathResource("compartment-scoring-weights-v1.yml"))
                    .forEach(properties::addLast);
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class)).get();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> risen(long playerId) {
        Set<String> result = new java.util.HashSet<>();
        PlayerSkillsService.GETTER_MAP.forEach((name, getter) -> {
            if (getter.apply(skills.get(playerId)) > 10) result.add(name);
        });
        return result;
    }

    @Test
    @DisplayName("""
            GIVEN two leagues updating on the 1st, one of them already updated in September
            WHEN 1 September is checked, the other league is marked updated, and the day is checked again
            THEN first only the not-yet-updated league is due, then none
            """)
    void eachLeagueIsUpdatedOnceAMonth() {
        TrainingUpdateMarker done = new TrainingUpdateMarker();
        done.setLeagueId(200L);
        done.setLastMonthKey(TrainingUpdateSchedule.monthKey(SEASON, SEP_1));
        markers.add(done);

        assertEquals(Set.of(100L), service.dueLeagues(SEASON, SEP_1));

        service.markUpdated(Set.of(100L), SEASON, SEP_1);

        assertTrue(service.dueLeagues(SEASON, SEP_1).isEmpty());
    }

    @Test
    @DisplayName("""
            GIVEN a 17-year-old of a human team who trained only Passing and Vision this month
            WHEN the team's monthly update runs
            THEN only Passing or Vision can have risen, his rating follows his attributes, and the counters are reset
            """)
    void humanTeamGrowsFromItsCountersAndResetsThem() {
        Human youngster = player(11L, 1L, "MC", 17);
        when(service.humanRepository.findAllByTeamIdAndTypeId(1L, TypeNames.PLAYER_TYPE)).thenReturn(List.of(youngster));
        when(service.counterService.counters(1L, 11L)).thenReturn(Map.of("Passing", 6, "Vision", 4));
        when(service.counterService.monthsUntrained(1L, 11L, TrainingUpdateSchedule.monthKey(SEASON, SEP_1)))
                .thenReturn(Map.of());

        for (int month = 0; month < 6; month++) service.updateHumanTeam(1L, SEASON, SEP_1, new Random(month));

        assertTrue(Set.of("Passing", "Vision").containsAll(risen(11L)), "risen=" + risen(11L));
        assertTrue(!risen(11L).isEmpty(), "six months at 17 grow something");
        assertEquals(PlayerSkillsService.computeOverallRating(skills.get(11L)), youngster.getRating(), 0.0);
        verify(service.counterService, org.mockito.Mockito.times(6)).resetCounts(1L);
        verify(service.matchSimulationOrchestrator, org.mockito.Mockito.times(6)).invalidateSquadCaches(1L);
    }

    @Test
    @DisplayName("""
            GIVEN an 18-year-old AI striker
            WHEN the AI monthly update runs for a year
            THEN only attack attributes rise, and he does grow
            """)
    void aiPlayersGrowOnlyInTheirCompartment() {
        Human striker = player(21L, 2L, "ST", 18);
        when(service.humanRepository.findAllByTeamIdInAndTypeIdAndRetiredFalse(anyCollection(), any(Long.class)))
                .thenReturn(List.of(striker));

        Random rng = new Random(5);
        for (int month = 0; month < 12; month++) service.updateAiTeams(List.of(2L), SEASON, rng);

        Set<String> attack = service.aiTrainingCounters.countersFor("ST").keySet();
        assertTrue(attack.containsAll(risen(21L)), "risen=" + risen(21L));
        assertTrue(risen(21L).size() >= 5, "a year at 18 (mean 3 a month) grows plenty: " + risen(21L));
    }

    @Test
    @DisplayName("""
            GIVEN a 17-year-old with professionalism 20, youth facilities 20 and senior 1, and coaches of 20 on
                  Technical and 10 on Mental
            WHEN his training quality per type is computed
            THEN Technical is 1 (all at the maximum), Mental 0.5, and a type nobody coaches is near 0
            """)
    void qComesFromFacilitiesProfessionalismAndTheDelegatedCoach() {
        MonthlyTrainingUpdateService real = new MonthlyTrainingUpdateService();
        real.trainingQuality = new TrainingQuality();
        real.engineConfig = new com.footballmanagergamesimulator.config.MatchEngineConfig();
        real.personalityService = mock(PlayerPersonalityService.class);
        Human youngster = player(31L, 1L, "MC", 17);
        when(real.personalityService.profile(youngster)).thenReturn(new PlayerPersonalityService.Profile(
                "", 20, 10, 10, 10, 10, 10, 10, "", List.of(), "", "", "", "", ""));
        com.footballmanagergamesimulator.model.TeamFacilities facilities = new com.footballmanagergamesimulator.model.TeamFacilities();
        facilities.setYouthTrainingLevel(20);
        facilities.setSeniorTrainingLevel(1);

        Map<TrainingType, Double> q = real.qByType(youngster, facilities,
                Map.of(TrainingType.TECHNICAL, 20.0, TrainingType.MENTAL, 10.0));

        assertEquals(1.0, q.get(TrainingType.TECHNICAL), 1e-9);
        assertEquals(0.5, q.get(TrainingType.MENTAL), 1e-9);
        assertTrue(q.get(TrainingType.TACTICAL) < 0.1, "no coach: " + q.get(TrainingType.TACTICAL));
    }
}
