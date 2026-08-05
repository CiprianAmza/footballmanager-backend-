package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubLegend;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.ClubLegendRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ClubLegendService {

    private final ClubLegendRepository legends;
    private final HumanRepository humans;
    private final ScorerRepository scorers;
    private final GameStateService gameState;

    public ClubLegendService(ClubLegendRepository legends, HumanRepository humans,
                             ScorerRepository scorers, GameStateService gameState) {
        this.legends = legends;
        this.humans = humans;
        this.scorers = scorers;
        this.gameState = gameState;
    }

    @Transactional(readOnly = true)
    public List<ClubLegendView> list(long teamId) {
        if (teamId <= 0) throw invalid("Club is required");
        Map<Long, ScorerRepository.LegacyRecordAggregate> records = recordMap(teamId);
        return legends.findAllByTeamIdOrderByInductedSeasonDescInductedAtDesc(teamId).stream()
                .map(legend -> view(legend, records.get(legend.getPlayerId())))
                .toList();
    }

    @Transactional
    public ClubLegendView induct(long teamId, long playerId, String requestedReason) {
        if (teamId <= 0 || playerId <= 0) throw invalid("Club and player are required");
        Map<Long, ScorerRepository.LegacyRecordAggregate> records = recordMap(teamId);
        Human player = humans.findById(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));
        boolean currentPlayer = Objects.equals(player.getTeamId(), teamId);
        if (!currentPlayer && !records.containsKey(playerId)) {
            throw invalid("Only a current or former player of this club can become a club legend");
        }

        ClubLegend legend = legends.findByTeamIdAndPlayerId(teamId, playerId).orElseGet(ClubLegend::new);
        if (legend.getId() == 0) {
            legend.setTeamId(teamId);
            legend.setPlayerId(playerId);
            legend.setInductedSeason(Math.toIntExact(gameState.currentSeason()));
            legend.setInductedAt(System.currentTimeMillis());
        }
        legend.setPlayerName(player.getName());
        legend.setReason(normalizeReason(requestedReason));
        return view(legends.save(legend), records.get(playerId));
    }

    @Transactional
    public void remove(long teamId, long playerId) {
        legends.deleteByTeamIdAndPlayerId(teamId, playerId);
    }

    private Map<Long, ScorerRepository.LegacyRecordAggregate> recordMap(long teamId) {
        Map<Long, ScorerRepository.LegacyRecordAggregate> result = new HashMap<>();
        scorers.aggregateClubLegacy(teamId).forEach(row -> result.put(safe(row.getPlayerId()), row));
        return result;
    }

    private ClubLegendView view(ClubLegend legend, ScorerRepository.LegacyRecordAggregate record) {
        long appearances = record == null ? 0 : safe(record.getAppearances());
        long ratingCount = record == null ? 0 : safe(record.getRatingCount());
        double averageRating = ratingCount == 0 ? 0 : safe(record.getRatingTotal()) / ratingCount;
        Human player = humans.findById(legend.getPlayerId()).orElse(null);
        String name = player == null || player.getName() == null ? legend.getPlayerName() : player.getName();
        String position = player == null || player.getPosition() == null ? "" : player.getPosition();
        return new ClubLegendView(legend.getId(), legend.getTeamId(), legend.getPlayerId(), name, position,
                legend.getInductedSeason(), legend.getInductedAt(), legend.getReason(), appearances,
                record == null ? 0 : safe(record.getGoals()), record == null ? 0 : safe(record.getAssists()),
                Math.round(averageRating * 100.0) / 100.0);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "Inducted into the club's official hall of fame";
        String clean = reason.trim().replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(clean.length(), 240));
    }

    private long safe(Long value) { return value == null ? 0 : value; }
    private double safe(Double value) { return value == null ? 0 : value; }
    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ClubLegendView(long id, long teamId, long playerId, String playerName, String position,
                                 int inductedSeason, long inductedAt, String reason, long appearances,
                                 long goals, long assists, double averageRating) {}
}
