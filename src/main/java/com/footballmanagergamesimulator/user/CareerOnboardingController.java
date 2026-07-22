package com.footballmanagergamesimulator.user;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/career")
public class CareerOnboardingController {

    private final CurrentUserService currentUserService;
    private final CareerOnboardingService onboardingService;

    public CareerOnboardingController(CurrentUserService currentUserService,
                                      CareerOnboardingService onboardingService) {
        this.currentUserService = currentUserService;
        this.onboardingService = onboardingService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return onboardingService.status(currentUserService.requireUser());
    }

    @PostMapping("/manager/setup")
    public ResponseEntity<Map<String, Object>> setupManager(@Valid @RequestBody ManagerSetupRequest request) {
        return ResponseEntity.ok(onboardingService.setupManager(currentUserService.requireUser(), request));
    }

    @PostMapping("/chairman/setup")
    public ResponseEntity<Map<String, Object>> setupChairman() {
        return ResponseEntity.ok(onboardingService.setupChairman(currentUserService.requireUser()));
    }
}
