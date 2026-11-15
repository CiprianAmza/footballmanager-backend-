package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldExpansionServiceTest {
    private WorldExpansionService service;
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
    private final TeamFacilitiesRepository facilities = mock(TeamFacilitiesRepository.class);
    private final StadiumRepository stadiums = mock(StadiumRepository.class);
    private final TrainingScheduleRepository training = mock(TrainingScheduleRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final CompositeNameGenerator names = mock(CompositeNameGenerator.class);
    private final FaceGenerator faces = mock(FaceGenerator.class);
    private final TacticService tactics = mock(TacticService.class);
    private final SquadGenerationService squads = mock(SquadGenerationService.class);
    private final StaffService staff = mock(StaffService.class);
    private final FixtureSchedulingService scheduling = mock(FixtureSchedulingService.class);
    private final CupBracketService brackets = mock(CupBracketService.class);
    private final ScorerLeaderboardSyncService scorers = mock(ScorerLeaderboardSyncService.class);
    private final Map<Long, Competition> competitionRows = new LinkedHashMap<>();
    private final Map<Long, Team> teamRows = new LinkedHashMap<>();
    private final Map<Long, Stadium> stadiumRows = new LinkedHashMap<>();
    private final Map<Long, TeamFacilities> facilityRows = new LinkedHashMap<>();
    private final Map<Long, Human> humanRows = new LinkedHashMap<>();
    private final List<CompetitionTeamInfo> entryRows = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new WorldExpansionService();
        Map<String, Object> dependencies = Map.ofEntries(
                Map.entry("competitions", competitions), Map.entry("teams", teams), Map.entry("entries", entries),
                Map.entry("facilities", facilities), Map.entry("stadiums", stadiums), Map.entry("training", training),
                Map.entry("humans", humans), Map.entry("calendars", calendars), Map.entry("names", names),
                Map.entry("faces", faces), Map.entry("tactics", tactics), Map.entry("squads", squads),
                Map.entry("staff", staff), Map.entry("scheduling", scheduling), Map.entry("brackets", brackets),
                Map.entry("scorers", scorers));
        dependencies.forEach((field, value) -> ReflectionTestUtils.setField(service, field, value));
        when(competitions.findAll()).thenAnswer(call -> new ArrayList<>(competitionRows.values()));
        when(competitions.save(any())).thenAnswer(call -> {
            Competition value = call.getArgument(0); if (value.getId() == 0) value.setId(nextId.incrementAndGet());
            competitionRows.put(value.getId(), value); return value;
        });
        when(teams.findAll()).thenAnswer(call -> new ArrayList<>(teamRows.values()));
        when(teams.save(any())).thenAnswer(call -> {
            Team value = call.getArgument(0); if (value.getId() == 0) value.setId(nextId.incrementAndGet());
            teamRows.put(value.getId(), value); return value;
        });
        when(stadiums.save(any())).thenAnswer(call -> {
            Stadium value = call.getArgument(0); value.setId(nextId.incrementAndGet());
            stadiumRows.put(value.getTeamId(), value); return value;
        });
        when(facilities.save(any())).thenAnswer(call -> {
            TeamFacilities value = call.getArgument(0); facilityRows.put(value.getTeamId(), value); return value;
        });
        when(facilities.findByTeamId(anyLong())).thenAnswer(call -> facilityRows.get(call.<Long>getArgument(0)));
        when(entries.save(any())).thenAnswer(call -> {
            CompetitionTeamInfo value = call.getArgument(0); entryRows.add(value); return value;
        });
        when(names.generateNameForNation(anyLong())).thenAnswer(call -> "Manager " + nextId.incrementAndGet());
        when(tactics.buildManagerTacticKit(anyInt(), any())).thenReturn(new String[]{"Balanced", "442"});
        when(humans.save(any())).thenAnswer(call -> {
            Human value = call.getArgument(0); if (value.getId() == 0) value.setId(nextId.incrementAndGet());
            humanRows.put(value.getId(), value); return value;
        });
        when(humans.findAllByTeamId(anyLong())).thenAnswer(call -> humanRows.values().stream()
                .filter(h -> Objects.equals(h.getTeamId(), call.<Long>getArgument(0))).toList());
        when(humans.findAllByTeamIdAndTypeId(anyLong(), anyLong())).thenAnswer(call -> humanRows.values().stream()
                .filter(h -> Objects.equals(h.getTeamId(), call.<Long>getArgument(0))
                        && h.getTypeId() == call.<Long>getArgument(1)).toList());
        when(squads.generateInitialSquad(any(), any(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            Team team = call.getArgument(0);
            List<Human> result = new ArrayList<>();
            List<String> positions = List.of("GK", "GK", "DL", "WBL", "DR", "WBR", "DC", "DC", "DC", "DC",
                    "ML", "AML", "MR", "AMR", "MC", "MC", "DM", "AMC", "ST", "ST", "ST", "ST");
            for (String position : positions) {
                Human player = new Human(); player.setId(nextId.incrementAndGet()); player.setTeamId(team.getId());
                player.setTypeId(TypeNames.PLAYER_TYPE); player.setPosition(position);
                player.setName("Generated " + player.getId()); player.setWage(1000);
                humanRows.put(player.getId(), player); result.add(player);
            }
            return result;
        });
    }

    @Test
    void importsCompleteClubsAndNativeReferencesWithoutTouchingExistingWorld() {
        Team old = new Team(); old.setId(7); old.setName("Existing club"); old.setTotalFinances(12345);
        teamRows.put(old.getId(), old);
        Round round = new Round(); round.setSeason(4); round.setRound(1);

        var imported = service.importIntoExistingWorld(round);

        assertEquals(36, imported.addedTeams().size());
        assertEquals(9, imported.addedCompetitions().size());
        assertEquals(4, imported.firstSeason());
        assertEquals(37, teamRows.size());
        assertEquals(12345, old.getTotalFinances());
        assertEquals("Existing club", old.getName());
        assertEquals(792, humanRows.values().stream().filter(h -> h.getTypeId() == 1).count());
        assertEquals(36, humanRows.values().stream().filter(h -> h.getTypeId() == 4).count());
        assertEquals(72, entryRows.size());
        for (Team team : imported.addedTeams()) {
            Competition league = competitionRows.get(team.getCompetitionId());
            assertTrue(league.isTopFlight());
            assertTrue(WorldExpansionCatalog.NATION_IDS.contains(league.getNationId()));
            Stadium stadium = stadiumRows.get(team.getId());
            assertEquals(stadium.getId(), team.getStadiumId());
            assertEquals(stadium.getStadiumName(), team.getStadiumName());
            assertEquals(stadium.getCapacity(), team.getStadiumCapacity());
            assertTrue(team.getSalaryBudget() > 0);
            assertEquals(22, humanRows.values().stream().filter(h -> h.getTypeId() == 1
                    && Objects.equals(h.getTeamId(), team.getId())).count());
        }
        for (var reference : WorldExpansionCatalog.REFERENCE_PLAYERS) {
            Human player = humanRows.values().stream().filter(h -> reference.name().equals(h.getName())).findFirst().orElseThrow();
            assertEquals(reference.position(), player.getPosition());
            assertEquals(reference.club(), teamRows.get(player.getTeamId()).getName());
            assertEquals(reference.hairColor(), player.getHairColor());
            assertEquals(reference.brows(), player.getBrowShape());
            assertEquals(reference.eyes(), player.getEyeShape());
            assertEquals(reference.nose(), player.getNoseShape());
            assertEquals(reference.mouth(), player.getMouthShape());
        }
        verify(squads, times(36)).generateInitialSquad(any(), any(), eq(4), eq(70), any());
        verify(staff, times(36)).generateInitialStaff(anyLong(), eq(4));
        verify(scorers, times(792)).trackNewPlayer(any());
        verify(scheduling, times(3)).getFixturesForRound(anyString(), eq("1"), eq(4));
        verify(scheduling, times(9)).addDomesticCompetitionCalendar(anyLong(), eq(4));
        verify(brackets, times(3)).generateBracket(anyLong(), eq(4));
        verify(scheduling, never()).generateSeasonCalendar(anyInt());
    }

    @Test
    void restartPreservesRenamedClubsTransfersAndLeagueEdits() {
        Round round = new Round();
        service.importIntoExistingWorld(round);
        Team edited = teamRows.values().iterator().next();
        edited.setName("User renamed this club"); edited.setCompetitionId(999); edited.setTotalFinances(5);
        competitionRows.values().iterator().next().setName("User renamed this league");
        clearInvocations(squads, staff, humans, teams, competitions, scheduling, brackets, entries);

        var repeated = service.importIntoExistingWorld(round);

        assertFalse(repeated.changed());
        assertEquals(36, teamRows.size());
        assertEquals(9, competitionRows.size());
        assertEquals(5, edited.getTotalFinances());
        verify(squads, never()).generateInitialSquad(any(), any(), anyInt(), anyInt(), any());
        verify(teams, never()).save(any());
        verify(humans, never()).save(any());
        verify(entries, never()).save(any());
        verifyNoInteractions(scheduling, brackets);
    }

    @Test
    void midseasonImportRegistersNextSeasonAndDoesNotRewriteFixturesOrCalendar() {
        Round round = new Round(); round.setSeason(6); round.setRound(20);
        GameCalendar calendar = new GameCalendar(); calendar.setSeason(6); calendar.setCurrentDay(150);
        when(calendars.findBySeason(6)).thenReturn(List.of(calendar));

        var imported = service.importIntoExistingWorld(round);

        assertEquals(7, imported.firstSeason());
        assertEquals(36, imported.addedTeams().size());
        assertTrue(entryRows.stream().allMatch(e -> e.getSeasonNumber() == 7));
        verifyNoInteractions(scheduling, brackets);
    }
}
