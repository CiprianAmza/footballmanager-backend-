package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.press.FirstDayService;
import org.springframework.web.bind.annotation.*;

/** What is left of a manager's first day at his club (design DESIGN_START_CARIERA §6). */
@RestController
@RequestMapping("/first-day")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class FirstDayController {

    private final FirstDayService firstDays;

    public FirstDayController(FirstDayService firstDays) {
        this.firstDays = firstDays;
    }

    @GetMapping("/{teamId}")
    public FirstDayService.Status status(@PathVariable long teamId) {
        return firstDays.status(teamId);
    }
}
