package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the game's memory from growing without end (decided 2026-11-12): results, tables, scorers and players'
 * season statistics stay forever, but per-match detail (set pieces, shots, match ratings, kickoff participants)
 * is kept only for the current and the previous season. Runs at each season transition.
 */
@Service
public class MatchDetailRetentionService {

    /** Seasons of per-match detail kept: the new one and the one just finished. */
    public static final int SEASONS_KEPT = 2;
    private static final int CHUNK = 500;

    private final SetPieceEventRepository setPieceEventRepository;
    private final ShotEventRepository shotEventRepository;
    private final MatchPlayerRatingRepository matchPlayerRatingRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;

    public MatchDetailRetentionService(SetPieceEventRepository setPieceEventRepository,
                                       ShotEventRepository shotEventRepository,
                                       MatchPlayerRatingRepository matchPlayerRatingRepository,
                                       MatchParticipantRepository matchParticipantRepository,
                                       CompetitionTeamInfoMatchRepository fixtureRepository) {
        this.setPieceEventRepository = setPieceEventRepository;
        this.shotEventRepository = shotEventRepository;
        this.matchPlayerRatingRepository = matchPlayerRatingRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.fixtureRepository = fixtureRepository;
    }

    public record Pruned(int setPieces, int shots, int ratings, int participants) {
    }

    /** At the start of {@code newSeason}: drops the per-match detail of every season before the previous one. */
    @Transactional
    public Pruned prune(int newSeason) {
        int firstKept = newSeason - SEASONS_KEPT + 1;
        if (firstKept <= 1) return new Pruned(0, 0, 0, 0);
        int setPieces = setPieceEventRepository.deleteSeasonsBefore(firstKept);
        int shots = shotEventRepository.deleteSeasonsBefore(firstKept);
        int ratings = matchPlayerRatingRepository.deleteSeasonsBefore(firstKept);
        int participants = 0;
        for (int season = 1; season < firstKept; season++) {
            List<String> keys = new ArrayList<>();
            for (CompetitionTeamInfoMatch fixture : fixtureRepository.findAllBySeasonNumber(String.valueOf(season))) {
                keys.add(MatchPlanService.competitionFixtureKey(fixture.getId()));
            }
            for (int i = 0; i < keys.size(); i += CHUNK) {
                participants += matchParticipantRepository.deleteForFixtures(keys.subList(i, Math.min(keys.size(), i + CHUNK)));
            }
        }
        System.out.println("=== Match detail retention (seasons before " + firstKept + "): set pieces=" + setPieces
                + " shots=" + shots + " ratings=" + ratings + " participants=" + participants + " ===");
        return new Pruned(setPieces, shots, ratings, participants);
    }
}
