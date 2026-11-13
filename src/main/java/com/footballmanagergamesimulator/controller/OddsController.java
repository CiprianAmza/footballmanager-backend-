package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.odds.TitleOddsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** League and cup chances (design DESIGN_MEDIA_SI_COMPETITII §4). Public information: no club check. */
@RestController
@RequestMapping("/odds")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class OddsController {

    private final TitleOddsService odds;

    public OddsController(TitleOddsService odds) {
        this.odds = odds;
    }

    /** Title, top 3, League of Champions and relegation chances for a league. */
    @GetMapping("/league/{competitionId}")
    public ResponseEntity<?> league(@PathVariable long competitionId) {
        try {
            return ResponseEntity.ok(odds.leagueOdds(competitionId));
        } catch (IllegalArgumentException notALeague) {
            return ResponseEntity.badRequest().body(Map.of("error", notALeague.getMessage()));
        }
    }

    /** The title race: each team's chances after every stored matchday of this season. */
    @GetMapping("/league/{competitionId}/history")
    public List<TitleOddsService.HistoryPoint> history(@PathVariable long competitionId) {
        return odds.history(competitionId);
    }

    /** Chances of winning a national cup and of reaching its final. */
    @GetMapping("/cup/{competitionId}")
    public ResponseEntity<?> cup(@PathVariable long competitionId) {
        try {
            return ResponseEntity.ok(odds.cupOdds(competitionId));
        } catch (IllegalArgumentException notACup) {
            return ResponseEntity.badRequest().body(Map.of("error", notACup.getMessage()));
        }
    }
}
