package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.AdminController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameAdvanceServiceTest {

    private GameAdvanceService service;
    @Mock private UserRepository userRepository;
    @Mock private HumanRepository humanRepository;
    @Mock private CalendarService calendarService;
    @Mock private GameCalendarRepository gameCalendarRepository;
    @Mock private CalendarEventDispatcher calendarEventDispatcher;
    @Mock private InjuryTimelineService injuryTimelineService;
    @Mock private AdminController adminController;
    @Mock private GameLock gameLock;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GameAdvanceService();
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "calendarService", calendarService);
        ReflectionTestUtils.setField(service, "gameCalendarRepository", gameCalendarRepository);
        ReflectionTestUtils.setField(service, "calendarEventDispatcher", calendarEventDispatcher);
        ReflectionTestUtils.setField(service, "injuryTimelineService", injuryTimelineService);
        ReflectionTestUtils.setField(service, "adminController", adminController);
        ReflectionTestUtils.setField(service, "gameLock", gameLock);
    }

    @Test
    void alwaysContinueRequiresAConfiguredHumanManager() {
        when(userRepository.findAll()).thenReturn(List.of());

        assertFalse(service.isAlwaysContinueActive());
    }

    @Test
    void alwaysContinueActivatesWhenEveryEmployedHumanManagerOptedIn() {
        User user = activeUser(1, 10L, 100L);
        Human manager = manager(100L, true);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(humanRepository.findAllById(Set.of(100L))).thenReturn(List.of(manager));

        assertTrue(service.isAlwaysContinueActive());
    }

    @Test
    void alwaysContinueRemainsActiveWhileManagerIsAFreeAgent() {
        User user = activeUser(1, null, 100L);
        Human manager = manager(100L, true);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(humanRepository.findAllById(Set.of(100L))).thenReturn(List.of(manager));

        assertTrue(service.isAlwaysContinueActive());
    }

    @Test
    void oneInteractiveManagerKeepsPausesEnabledInMultiUserSave() {
        User first = activeUser(1, 10L, 100L);
        User second = activeUser(2, 20L, 200L);
        when(userRepository.findAll()).thenReturn(List.of(first, second));
        when(humanRepository.findAllById(Set.of(100L, 200L))).thenReturn(List.of(
                manager(100L, true), manager(200L, false)));

        assertFalse(service.isAlwaysContinueActive());
    }

    @Test
    void alwaysContinueConsumesAwaitingInputEventWithoutPausingCalendar() {
        User user = activeUser(1, 10L, 100L);
        Human manager = manager(100L, true);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(humanRepository.findAllById(Set.of(100L))).thenReturn(List.of(manager));

        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(1);
        calendar.setCurrentDay(1);
        calendar.setCurrentPhase("MORNING");
        CalendarEvent transferWindow = new CalendarEvent();
        transferWindow.setId(55L);
        transferWindow.setSeason(1);
        transferWindow.setDay(1);
        transferWindow.setPhase("MORNING");
        transferWindow.setStatus("PENDING");
        transferWindow.setEventType("TRANSFER_WINDOW_OPEN");

        when(calendarService.getOrCreateCalendar(1)).thenReturn(calendar);
        when(calendarService.getEventsForDayAndPhase(1, 1, "MORNING"))
                .thenReturn(List.of(transferWindow));
        when(calendarService.claimEvent(55L)).thenReturn(true);
        when(calendarEventDispatcher.processEvent(transferWindow, calendar))
                .thenReturn(Map.of("type", "TRANSFER_WINDOW_OPEN", "awaitingInput", true));
        doAnswer(invocation -> {
            calendar.setCurrentDay(2);
            return null;
        }).when(calendarService).advancePhase(calendar);
        when(calendarService.getDateDisplay(any(Integer.class))).thenReturn("2 August");
        when(calendarService.getDayOfWeek(2)).thenReturn("Wednesday");
        when(calendarService.getSeasonPhase(2)).thenReturn("PRE_SEASON");

        Map<String, Object> result = service.advance(1);

        assertEquals(false, result.get("paused"));
        assertEquals(true, result.get("alwaysContinue"));
        assertEquals(2, result.get("day"));
        verify(calendarService).markEventCompleted(55L);
    }

    private User activeUser(int id, Long teamId, long managerId) {
        User user = new User();
        user.setId(id);
        user.setTeamId(teamId);
        user.setManagerId(managerId);
        return user;
    }

    private Human manager(long id, boolean alwaysContinue) {
        Human manager = new Human();
        manager.setId(id);
        manager.setAlwaysContinue(alwaysContinue);
        return manager;
    }
}
