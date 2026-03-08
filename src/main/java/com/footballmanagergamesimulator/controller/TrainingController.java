package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.repository.TrainingScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/training")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TrainingController {

    @Autowired
    TrainingScheduleRepository trainingScheduleRepository;

    @GetMapping("/schedule/{teamId}")
    public List<TrainingSchedule> getSchedule(@PathVariable(name = "teamId") long teamId) {
        return trainingScheduleRepository.findAllByTeamId(teamId);
    }

    @PostMapping("/schedule/{teamId}")
    public List<TrainingSchedule> saveSchedule(@PathVariable(name = "teamId") long teamId,
                                               @RequestBody List<TrainingSchedule> schedules) {
        // Remove existing schedule for this team
        List<TrainingSchedule> existing = trainingScheduleRepository.findAllByTeamId(teamId);
        trainingScheduleRepository.deleteAll(existing);

        // Save new schedule with correct teamId
        for (TrainingSchedule schedule : schedules) {
            schedule.setId(0); // reset id so JPA generates new ones
            schedule.setTeamId(teamId);
        }
        return trainingScheduleRepository.saveAll(schedules);
    }

    @GetMapping("/defaultSchedule")
    public List<TrainingSchedule> getDefaultSchedule() {
        return buildDefaultSchedule(0);
    }

    public static List<TrainingSchedule> buildDefaultSchedule(long teamId) {
        List<TrainingSchedule> schedule = new ArrayList<>();

        // Monday: Physical + General + Extra
        schedule.add(createEntry(teamId, 0, 0, "Physical", "Endurance", 80));
        schedule.add(createEntry(teamId, 0, 1, "General", "Possession", 60));
        schedule.add(createEntry(teamId, 0, 2, "General", "Team Bonding", 10));

        // Tuesday: Tactical + Tactical + Rest
        schedule.add(createEntry(teamId, 1, 0, "Tactical", "Def. Shadow", 50));
        schedule.add(createEntry(teamId, 1, 1, "Tactical", "Att. Movement", 50));
        schedule.add(createEntry(teamId, 1, 2, "Rest", "Rest", 0));

        // Wednesday: Physical + General + Extra
        schedule.add(createEntry(teamId, 2, 0, "Physical", "Quickness", 70));
        schedule.add(createEntry(teamId, 2, 1, "General", "Defending", 65));
        schedule.add(createEntry(teamId, 2, 2, "General", "Community", 10));

        // Thursday: Match + Match + Rest
        schedule.add(createEntry(teamId, 3, 0, "Match", "Match Tactics", 40));
        schedule.add(createEntry(teamId, 3, 1, "Match", "Set Pieces", 30));
        schedule.add(createEntry(teamId, 3, 2, "Rest", "Rest", 0));

        // Friday: Physical + Match + Rest
        schedule.add(createEntry(teamId, 4, 0, "Physical", "Recovery", 20));
        schedule.add(createEntry(teamId, 4, 1, "Match", "Match Prev.", 10));
        schedule.add(createEntry(teamId, 4, 2, "Rest", "Rest", 0));

        // Saturday: Match Day (1 session)
        schedule.add(createEntry(teamId, 5, 0, "Match", "MATCH DAY", 100));

        // Sunday: Recovery + Rest + Rest
        schedule.add(createEntry(teamId, 6, 0, "Physical", "Recovery", 20));
        schedule.add(createEntry(teamId, 6, 1, "Rest", "Rest", 0));
        schedule.add(createEntry(teamId, 6, 2, "Rest", "Rest", 0));

        return schedule;
    }

    private static TrainingSchedule createEntry(long teamId, int day, int slot, String type, String name, int intensity) {
        TrainingSchedule ts = new TrainingSchedule();
        ts.setTeamId(teamId);
        ts.setDayOfWeek(day);
        ts.setSessionSlot(slot);
        ts.setSessionType(type);
        ts.setSessionName(name);
        ts.setIntensity(intensity);
        return ts;
    }
}
