package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.ApiError;
import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.FixtureCoordinates;
import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.ProvenanceEnvelope;
import com.footballmanagergamesimulator.analytics.FixtureIdentity;
import com.footballmanagergamesimulator.analytics.MatchProvenanceService;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Additive API v2 for Phase 0 provenance/parity. A brand-new controller mapped at
 * {@code /api/v2/matches}; it does not touch any v1 ({@code /match}, {@code /stats})
 * endpoint. Automatically authenticated via {@code anyRequest().authenticated()};
 * the explicit {@code getUserOrNull} check is the principal-scoping gate. Every
 * response is the provenance envelope — the underlying result/stats aggregates are
 * never re-shaped or re-emitted here.
 */
@RestController
@RequestMapping("/api/v2/matches")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class MatchProvenanceV2Controller {

    private final MatchProvenanceService provenanceService;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final CurrentUserService currentUserService;

    public MatchProvenanceV2Controller(MatchProvenanceService provenanceService,
                                       CompetitionTeamInfoMatchRepository fixtureRepository,
                                       CurrentUserService currentUserService) {
        this.provenanceService = provenanceService;
        this.fixtureRepository = fixtureRepository;
        this.currentUserService = currentUserService;
    }

    /** Frontend-facing, addressed by the same fixture tuple v1 uses. */
    @GetMapping("/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public ResponseEntity<?> provenanceByTuple(@PathVariable long competitionId,
                                               @PathVariable int season,
                                               @PathVariable long round,
                                               @PathVariable long teamId1,
                                               @PathVariable long teamId2) {
        User user = currentUserService.getUserOrNull();
        if (user == null) {
            return unauthenticated();
        }
        Optional<CompetitionTeamInfoMatch> fixtureOpt =
                resolveFixture(competitionId, season, round, teamId1, teamId2);
        if (fixtureOpt.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(envelopeFor(fixtureOpt.get()));
    }

    /** Direct-by-key access for tools/tests; key form is {@code CTIM:<matchRowId>}. */
    @GetMapping("/by-key/{fixtureKey}")
    public ResponseEntity<?> provenanceByKey(@PathVariable String fixtureKey) {
        User user = currentUserService.getUserOrNull();
        if (user == null) {
            return unauthenticated();
        }
        long matchRowId = FixtureIdentity.matchRowId(fixtureKey);
        if (matchRowId < 0) {
            return ResponseEntity.badRequest()
                    .body(new ApiError("INVALID_FIXTURE_KEY", "Unsupported fixture key: " + fixtureKey));
        }
        Optional<CompetitionTeamInfoMatch> fixtureOpt = fixtureRepository.findById(matchRowId);
        if (fixtureOpt.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(envelopeFor(fixtureOpt.get()));
    }

    private ProvenanceEnvelope envelopeFor(CompetitionTeamInfoMatch fixture) {
        String fixtureKey = FixtureIdentity.competitionFixtureKey(fixture.getId());
        FixtureCoordinates coordinates = new FixtureCoordinates(
                fixture.getCompetitionId(), parseSeason(fixture.getSeasonNumber()),
                (int) fixture.getRound(), fixture.getTeam1Id(), fixture.getTeam2Id());
        return provenanceService.readEnvelope(fixtureKey, coordinates);
    }

    private Optional<CompetitionTeamInfoMatch> resolveFixture(long competitionId, int season,
                                                              long round, long teamId1, long teamId2) {
        List<CompetitionTeamInfoMatch> candidates = fixtureRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(competitionId, round, String.valueOf(season));
        Optional<CompetitionTeamInfoMatch> exact = candidates.stream()
                .filter(c -> c.getTeam1Id() == teamId1 && c.getTeam2Id() == teamId2)
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return candidates.stream()
                .filter(c -> c.getTeam1Id() == teamId2 && c.getTeam2Id() == teamId1)
                .findFirst();
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401)
                .body(new ApiError("UNAUTHENTICATED", "Authentication required"));
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404)
                .body(new ApiError("FIXTURE_NOT_FOUND", "No fixture for the supplied identity"));
    }

    private static int parseSeason(String season) {
        if (season == null) {
            return 0;
        }
        try {
            return Integer.parseInt(season.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
