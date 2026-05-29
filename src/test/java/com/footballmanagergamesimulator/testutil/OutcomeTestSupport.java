package com.footballmanagergamesimulator.testutil;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * DB-backed helpers shared by the outcome-simulation tests: parsing the
 * {@code -Dteam.ids} property, computing a team's power, and loading a list of
 * teams by id in a deterministic order.
 *
 * <p>{@code @Component} so Spring picks it up when test classes scan the
 * {@code testutil} package (same pattern as {@link TestTeamFactory}).
 */
@Component
public class OutcomeTestSupport {

    @Autowired private TeamRepository teamRepo;
    @Autowired private HumanRepository humanRepo;

    /** Parse {@code "1, 5,8,  12 "} → {@code [1, 5, 8, 12]}. Whitespace tolerated. */
    public static List<Long> parseTeamIds(String input) {
        List<Long> ids = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid -Dteam.ids — '" + trimmed + "' is not an integer. "
                                + "Expected comma-separated team IDs (e.g. \"1,5,8,12\"). Input was: \"" + input + "\"");
            }
        }
        return ids;
    }

    /** Sum of top-11 non-retired player ratings for a team. */
    public double computeTeamPower(long teamId) {
        List<Human> players = humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        players.sort(Comparator.comparingDouble(Human::getRating).reversed());
        double sum = 0;
        int count = 0;
        for (Human p : players) {
            if (p.isRetired()) continue;
            sum += p.getRating();
            if (++count == 11) break;
        }
        return sum;
    }

    /** Load the named teams + sort by id (deduped) for deterministic simulation order. */
    public List<TeamSetup> loadTeamsByIds(List<Long> teamIds) {
        TreeSet<Long> sortedIds = new TreeSet<>(teamIds);
        List<TeamSetup> out = new ArrayList<>(sortedIds.size());
        for (long id : sortedIds) {
            String name = teamRepo.findNameById(id);
            if (name == null) {
                throw new IllegalArgumentException(
                        "Team ID " + id + " not found in DB. Check -Dteam.ids — IDs must reference existing Team rows.");
            }
            out.add(new TeamSetup(id, name, computeTeamPower(id)));
        }
        return out;
    }
}
