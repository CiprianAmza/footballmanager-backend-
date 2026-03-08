package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CalendarService {

    @Autowired
    GameCalendarRepository gameCalendarRepository;

    @Autowired
    CalendarEventRepository calendarEventRepository;

    // Month definitions: name, start day, number of days
    private static final String[] MONTH_NAMES = {
            "August", "September", "October", "November", "December",
            "January", "February", "March", "April", "May", "June", "July"
    };
    private static final int[] MONTH_START_DAYS = {
            1, 32, 62, 93, 123, 154, 185, 213, 244, 274, 305, 335
    };
    private static final int[] MONTH_LENGTHS = {
            31, 30, 31, 30, 31, 31, 28, 31, 30, 31, 30, 31
    };

    private static final String[] DAYS_OF_WEEK = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };

    public GameCalendar getOrCreateCalendar(int season) {
        List<GameCalendar> calendars = gameCalendarRepository.findBySeason(season);
        if (!calendars.isEmpty()) {
            return calendars.get(0);
        }

        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(season);
        calendar.setCurrentDay(1);
        calendar.setCurrentPhase("MORNING");
        calendar.setSeasonPhase("PRE_SEASON");
        calendar.setTransferWindowOpen(false);
        calendar.setManagerFired(false);
        calendar.setPaused(false);
        return gameCalendarRepository.save(calendar);
    }

    public String getDayOfWeek(int day) {
        int index = day % 7;
        return DAYS_OF_WEEK[index];
    }

    public String getDateDisplay(int day) {
        if (day < 1) day = 1;
        if (day > 365) day = 365;

        for (int i = MONTH_START_DAYS.length - 1; i >= 0; i--) {
            if (day >= MONTH_START_DAYS[i]) {
                int dayOfMonth = day - MONTH_START_DAYS[i] + 1;
                return dayOfMonth + " " + MONTH_NAMES[i];
            }
        }
        return day + " August";
    }

    public String getSeasonPhase(int day) {
        if (day <= 30) return "PRE_SEASON";
        if (day <= 90) return "EARLY_SEASON";
        if (day <= 180) return "MID_SEASON";
        if (day <= 200) return "WINTER_BREAK";
        if (day <= 310) return "LATE_SEASON";
        if (day <= 340) return "END_OF_SEASON";
        return "OFF_SEASON";
    }

    public boolean hasPhase(int season, int day, String phase) {
        List<CalendarEvent> events = calendarEventRepository.findAllBySeasonAndDayAndPhase(season, day, phase);
        return !events.isEmpty();
    }

    public List<CalendarEvent> getEventsForDayAndPhase(int season, int day, String phase) {
        List<CalendarEvent> events = calendarEventRepository.findAllBySeasonAndDayAndPhase(season, day, phase);
        events.sort(Comparator.comparingInt(CalendarEvent::getPriority));
        return events;
    }

    public CalendarEvent getNextPendingEvent(int season) {
        return calendarEventRepository.findFirstBySeasonAndStatusOrderByDayAscPriorityAsc(season, "PENDING");
    }

    public void markEventCompleted(long eventId) {
        CalendarEvent event = calendarEventRepository.findById(eventId).orElse(null);
        if (event != null) {
            event.setStatus("COMPLETED");
            calendarEventRepository.save(event);
        }
    }

    public Map<String, Object> advancePhase(GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        String currentPhase = calendar.getCurrentPhase();
        int currentDay = calendar.getCurrentDay();

        String nextPhase;
        int nextDay = currentDay;

        switch (currentPhase) {
            case "MORNING":
                // Check if there are AFTERNOON events for this day
                if (hasPhase(calendar.getSeason(), currentDay, "AFTERNOON")) {
                    nextPhase = "AFTERNOON";
                } else if (hasPhase(calendar.getSeason(), currentDay, "EVENING")) {
                    nextPhase = "EVENING";
                } else {
                    nextPhase = "MORNING";
                    nextDay = currentDay + 1;
                }
                break;
            case "AFTERNOON":
                if (hasPhase(calendar.getSeason(), currentDay, "EVENING")) {
                    nextPhase = "EVENING";
                } else {
                    nextPhase = "MORNING";
                    nextDay = currentDay + 1;
                }
                break;
            case "EVENING":
                nextPhase = "MORNING";
                nextDay = currentDay + 1;
                break;
            default:
                nextPhase = "MORNING";
                nextDay = currentDay + 1;
                break;
        }

        // Handle season end
        if (nextDay > 365) {
            nextDay = 365;
            nextPhase = "MORNING";
            result.put("seasonEnded", true);
        }

        calendar.setCurrentDay(nextDay);
        calendar.setCurrentPhase(nextPhase);
        calendar.setSeasonPhase(getSeasonPhase(nextDay));

        result.put("day", nextDay);
        result.put("phase", nextPhase);
        result.put("dateDisplay", getDateDisplay(nextDay));
        result.put("dayOfWeek", getDayOfWeek(nextDay));
        result.put("seasonPhase", getSeasonPhase(nextDay));

        return result;
    }
}
