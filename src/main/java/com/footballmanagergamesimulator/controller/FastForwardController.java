package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.FastForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/game/fast-forward")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class FastForwardController {

    public record FastForwardRequest(int seasons, int chunkDays) {}

    @Autowired private FastForwardService fastForwardService;

    @PostMapping
    public FastForwardService.FastForwardStatus start(@RequestBody FastForwardRequest request) {
        return fastForwardService.start(request.seasons(), request.chunkDays());
    }

    @GetMapping
    public FastForwardService.FastForwardStatus status() {
        return fastForwardService.getStatus();
    }

    @DeleteMapping("/{jobId}")
    public FastForwardService.FastForwardStatus cancel(@PathVariable String jobId) {
        return fastForwardService.cancel(jobId);
    }
}
