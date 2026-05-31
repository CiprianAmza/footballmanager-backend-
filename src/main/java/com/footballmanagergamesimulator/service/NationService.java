package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves a player's nation. Nation is not stored on {@link com.footballmanagergamesimulator.model.Human};
 * it is derived through {@code Team.competitionId -> Competition.nationId}. The 8 nations (ids 0-7) are
 * the {@code nationId} values seeded in {@code BootstrapService.initializeCompetitions()}; their display
 * labels here mirror the competition names in that seed. Nation 0 is the continental layer (LoC / Stars
 * Cup) and is mapped for safety even though club teams resolve to nations 1-7.
 */
@Service
public class NationService {

    /** Static nation metadata: id, display name, and a short flag code for the FE. */
    public record NationInfo(long id, String name, String flagCode) {}

    private static final NationInfo UNKNOWN = new NationInfo(-1, "Unknown", "xx");

    private static final Map<Long, NationInfo> NATIONS = Map.of(
            0L, new NationInfo(0, "Europe", "eu"),
            1L, new NationInfo(1, "Gallactick", "ga"),
            2L, new NationInfo(2, "Dong", "do"),
            3L, new NationInfo(3, "Khess", "kh"),
            4L, new NationInfo(4, "FootieCup", "fo"),
            5L, new NationInfo(5, "Cards", "ca"),
            6L, new NationInfo(6, "Literature", "li"),
            7L, new NationInfo(7, "Eleven", "el"));

    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;

    public NationService(TeamRepository teamRepository, CompetitionRepository competitionRepository) {
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
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

    /** Convenience: full nation metadata for a team. */
    public NationInfo infoForTeam(Long teamId) {
        return infoFor(nationIdForTeam(teamId));
    }
}
