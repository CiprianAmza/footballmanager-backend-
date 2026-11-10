package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.GameLock;
import com.footballmanagergamesimulator.service.MemoryCheckpointService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/game/checkpoints")
public class GameCheckpointController {
    private final MemoryCheckpointService checkpoints;
    private final GameLock gameLock;

    public GameCheckpointController(MemoryCheckpointService checkpoints, GameLock gameLock) {
        this.checkpoints = checkpoints;
        this.gameLock = gameLock;
    }

    @GetMapping
    public MemoryCheckpointService.Status status() {
        return checkpoints.status();
    }

    @PostMapping
    public ResponseEntity<?> saveNow() {
        gameLock.lock();
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(checkpoints.capture(true));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
        } finally {
            gameLock.unlock();
        }
    }

    @PutMapping("/interval")
    public ResponseEntity<?> setInterval(@RequestBody Map<String, Object> payload) {
        Object value = payload.get("matchdays");
        if (!(value instanceof Number number)) {
            return ResponseEntity.badRequest().body(Map.of("error", "matchdays must be a number"));
        }
        try {
            return ResponseEntity.ok(checkpoints.setInterval(number.intValue()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
        }
    }
}
