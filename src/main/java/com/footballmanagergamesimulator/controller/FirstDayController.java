package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.press.FirstDayService;
import com.footballmanagergamesimulator.service.squad.LeaguePredictionService;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** What is left of a manager's first day at his club (design DESIGN_START_CARIERA §6). */
@RestController
@RequestMapping("/first-day")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class FirstDayController {

    /** The steps whose page needs the press's league prediction. */
    private static final Set<String> NEEDS_PREDICTION = Set.of("BOARD", "SQUAD");

    private final FirstDayService firstDays;
    private final LeaguePredictionService predictions;

    public FirstDayController(FirstDayService firstDays, LeaguePredictionService predictions) {
        this.firstDays = firstDays;
        this.predictions = predictions;
    }

    /** While the board or the squad meeting is still ahead, the league prediction starts being worked out now. */
    @GetMapping("/{teamId}")
    public FirstDayService.Status status(@PathVariable long teamId) {
        FirstDayService.Status status = firstDays.status(teamId);
        if (status.pending() && status.steps().stream().anyMatch(s -> !s.done() && NEEDS_PREDICTION.contains(s.key())))
            predictions.prepareLeagueOf(teamId);
        return status;
    }
}
