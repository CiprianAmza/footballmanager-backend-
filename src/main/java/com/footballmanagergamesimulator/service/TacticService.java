package com.footballmanagergamesimulator.service;

import io.swagger.models.auth.In;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TacticService {

    public Map<String, Integer> getRoomInTeamByTactic(String tactic) {
        Map<String, Integer> _442 = Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 2);
        Map<String, Integer> _433 = Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 3);
        Map<String, Integer> _343 = Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 3);
        Map<String, Integer> _451 = Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 1);
        Map<String, Integer> _352 = Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 2);


        Map<String, Map<String, Integer>> tacticToTacticFormat = Map.of(
                "442", _442,
                "433", _433,
                "343", _343,
                "451", _451,
                "352", _352);

        return tacticToTacticFormat.getOrDefault(tactic, _442);
    }

    public Integer getValueForTacticDisplay(String position) {

        List<String> positions = List.of("GK", "DL", "DC", "DR", "ML", "MC", "MR", "ST");

        return positions.indexOf(position);
    }

    public List<String> getAllExistingTactics() {

        return List.of("442", "433", "343", "451", "352");
    }
}
