package com.footballmanagergamesimulator.user;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ui-preferences")
public class UserUiPreferenceController {

    private final CurrentUserService currentUserService;
    private final UserUiPreferenceService preferenceService;

    public UserUiPreferenceController(CurrentUserService currentUserService,
                                      UserUiPreferenceService preferenceService) {
        this.currentUserService = currentUserService;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<UserUiPreferenceService.PreferenceView> get(@PathVariable String key) {
        int userId = currentUserService.requireUser().getId();
        return preferenceService.get(userId, key).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{key}")
    public UserUiPreferenceService.PreferenceView save(@PathVariable String key, @RequestBody JsonNode value) {
        return preferenceService.save(currentUserService.requireUser().getId(), key, value);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        preferenceService.delete(currentUserService.requireUser().getId(), key);
        return ResponseEntity.noContent().build();
    }
}
