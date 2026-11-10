package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gives every competition a stable reputation, including worlds created before the field existed. */
@Service
public class CompetitionReputationService {
    private final CompetitionRepository competitions;
    private final TeamRepository teams;

    public CompetitionReputationService(CompetitionRepository competitions, TeamRepository teams) {
        this.competitions = competitions;
        this.teams = teams;
    }

    @Transactional
    public void ensureReputations() {
        List<Competition> all = competitions.findAll();
        if (all.isEmpty()) return;

        Map<Long, Integer> strongestClubByLeague = new HashMap<>();
        for (Team team : teams.findAll()) {
            strongestClubByLeague.merge(team.getCompetitionId(), team.getReputation(), Math::max);
        }
        Map<Long, Integer> topLeagueByNation = new HashMap<>();
        for (Competition competition : all) {
            if (competition.isTopFlight()) {
                topLeagueByNation.merge(competition.getNationId(),
                        Math.min(10_000, strongestClubByLeague.getOrDefault(competition.getId(), 5_000)),
                        Math::max);
            }
        }

        List<Competition> changed = new ArrayList<>();
        for (Competition competition : all) {
            int reputation;
            if (competition.getTypeId() == Competition.LEAGUE_OF_CHAMPIONS) {
                reputation = 13_000;
            } else if (competition.getTypeId() == Competition.STARS_CUP) {
                reputation = 9_000;
            } else if (competition.getTypeId() == Competition.CUP) {
                // Recompute existing cups too: the former 75% value was persisted
                // in saves and would otherwise keep their rankings inflated.
                reputation = Math.round(topLeagueByNation.getOrDefault(competition.getNationId(), 5_000) * 0.60f);
            } else if (competition.getReputation() != null && competition.getReputation() > 0) {
                reputation = Math.min(10_000, competition.getReputation());
            } else if (competition.isLeague() || competition.getTypeId() == 3) {
                reputation = Math.min(10_000, strongestClubByLeague.getOrDefault(competition.getId(),
                        Math.round(topLeagueByNation.getOrDefault(competition.getNationId(), 5_000)
                                * (competition.isBelowTopFlight() || competition.getTypeId() == 3 ? 0.65f : 1f))));
            } else {
                int topLeague = topLeagueByNation.getOrDefault(competition.getNationId(), 5_000);
                reputation = Math.round(topLeague * (competition.getTypeId() == Competition.SUPER_CUP
                        ? 0.25f : 0.75f));
            }
            if (!Integer.valueOf(reputation).equals(competition.getReputation())) {
                competition.setReputation(reputation);
                changed.add(competition);
            }
        }
        if (!changed.isEmpty()) competitions.saveAll(changed);
    }

    /** Fallback only for unusual imports that have not run the startup backfill yet. */
    public static int reputationOf(Competition competition) {
        if (competition.getTypeId() == Competition.LEAGUE_OF_CHAMPIONS) return 13_000;
        if (competition.getTypeId() == Competition.STARS_CUP) return 9_000;
        if (competition.getReputation() != null && competition.getReputation() > 0)
            return Math.min(10_000, competition.getReputation());
        if (competition.isBelowTopFlight() || competition.getTypeId() == 3) return 6_500;
        if (competition.getTypeId() == Competition.SUPER_CUP) return 2_500;
        if (competition.getTypeId() == Competition.CUP) return 6_000;
        return 10_000;
    }
}
