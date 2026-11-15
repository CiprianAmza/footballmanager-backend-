package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a player's nation. Nation is not stored on {@link com.footballmanagergamesimulator.model.Human};
 * it is derived through {@code Team.competitionId -> Competition.nationId}. The nation ids (0-13) are
 * the {@code nationId} values seeded in {@code BootstrapService.initializeCompetitions()}; their display
 * labels here mirror the competition names in that seed. Nation 0 is the continental layer (LoC / Stars
 * Cup) and is mapped for safety even though club teams resolve to nations 1-13.
 */
@Service
public class NationService {

    /** Static nation metadata: id, display name, and a short flag code for the FE. */
    public record NationInfo(long id, String name, String flagCode) {}

    private static final NationInfo UNKNOWN = new NationInfo(-1, "Unknown", "xx");

    private static final Map<Long, NationInfo> NATIONS = Map.ofEntries(
            Map.entry(0L, new NationInfo(0, "Europe", "eu")),
            Map.entry(1L, new NationInfo(1, "Gallactick", "ga")),
            Map.entry(2L, new NationInfo(2, "Dong", "do")),
            Map.entry(3L, new NationInfo(3, "Khess", "kh")),
            Map.entry(4L, new NationInfo(4, "FootieCup", "fo")),
            Map.entry(5L, new NationInfo(5, "Cards", "ca")),
            Map.entry(6L, new NationInfo(6, "Literature", "li")),
            Map.entry(7L, new NationInfo(7, "Eleven", "el")),
            Map.entry(8L, new NationInfo(8, "Mareval", "ma")),
            Map.entry(9L, new NationInfo(9, "Tervala", "te")),
            Map.entry(10L, new NationInfo(10, "Altzair", "al")),
            Map.entry(11L, new NationInfo(11, "Veyra", "ve")),
            Map.entry(12L, new NationInfo(12, "Branik", "br")),
            Map.entry(13L, new NationInfo(13, "Sileth", "si")));

    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;

    public NationService(TeamRepository teamRepository, CompetitionRepository competitionRepository) {
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
    }

    /** The countries of the world (not the continent), by id. */
    public static List<NationInfo> countries() {
        return NATIONS.values().stream().filter(n -> n.id() > 0)
                .sorted(java.util.Comparator.comparingLong(NationInfo::id)).toList();
    }

    /** Metadata for a nation id, or a safe Unknown fallback. */
    public NationInfo infoFor(long nationId) {
        return NATIONS.getOrDefault(nationId, UNKNOWN);
    }

    /** Resolve a team's nation id via team -> competition -> nationId; 0 if anything is missing. */
    public long nationIdForTeam(Long teamId) {
        if (teamId == null)
            return 0;
        Optional<Team> team = teamRepository.findById(teamId);
        if (team.isEmpty())
            return 0;
        Optional<Competition> competition = competitionRepository.findById(team.get().getCompetitionId());
        return competition.map(Competition::getNationId).orElse(0L);
    }

    /** Nation of every team, resolved with two queries in total (for list endpoints). */
    public Map<Long, NationInfo> infoByTeamId() {
        Map<Long, Long> nationByCompetition = new java.util.HashMap<>();
        competitionRepository.findAll().forEach(c -> nationByCompetition.put(c.getId(), c.getNationId()));
        Map<Long, NationInfo> result = new java.util.HashMap<>();
        teamRepository.findAll().forEach(t ->
                result.put(t.getId(), infoFor(nationByCompetition.getOrDefault(t.getCompetitionId(), 0L))));
        return result;
    }

    /** Convenience: full nation metadata for a team. */
    public NationInfo infoForTeam(Long teamId) {
        return infoFor(nationIdForTeam(teamId));
    }
}
