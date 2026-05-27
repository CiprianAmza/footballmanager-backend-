package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Manager job offers — listing currently available openings and accepting
 * an offer. URLs keep the {@code /competition/...} prefix so the Angular
 * frontend needs no changes.
 */
@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class JobOfferController {

    private final JobOfferService jobOfferService;
    private final UserContext userContext;

    public JobOfferController(JobOfferService jobOfferService, UserContext userContext) {
        this.jobOfferService = jobOfferService;
        this.userContext = userContext;
    }

    @GetMapping("/availableJobs")
    public List<Map<String, Object>> getAvailableJobs() {
        return jobOfferService.getAvailableJobs();
    }

    @PostMapping("/acceptJob")
    public String acceptJob(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        User currentUser = userContext.getUserOrNull(request);
        return jobOfferService.acceptJob(currentUser, body.get("teamId"));
    }
}
