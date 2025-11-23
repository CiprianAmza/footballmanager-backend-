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

    public Map<String, Integer> getSubstitutionsInTeamByTactic(String tactic) {

        Map<String, Integer> _442 = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);
        Map<String, Integer> _433 = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);
        Map<String, Integer> _343 = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);
        Map<String, Integer> _451 = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);
        Map<String, Integer> _352 = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);

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

    public String getPositionFromIndex(int index) {

        if (index >= 30) return "Substitute";

        // --- Rândul 1 (0-4): Atacanți ---
        if (index <= 4) return "ST";

        // --- Rândul 2 (5-9): Mijlocași Ofensivi ---
        if (index == 5) return "AML"; // Sau ML, depinde cum ai in DB
        if (index == 9) return "AMR"; // Sau MR
        if (index <= 8) return "AMC"; // Sau MC

        // --- Rândul 3 (10-14): Mijlocași Centrali ---
        if (index == 10) return "ML";
        if (index == 14) return "MR";
        if (index <= 13) return "MC";

        // --- Rândul 4 (15-19): Mijlocași Defensivi / Wing Backs ---
        // AICI ERA PROBLEMA: Lipseau 16, 17, 18
        if (index == 15) return "DL"; // WB stanga
        if (index == 19) return "DR"; // WB dreapta
        if (index <= 18) return "MC";

        // --- Rândul 5 (20-24): Fundași ---
        if (index == 20) return "DL";
        if (index == 24) return "DR";
        if (index <= 23) return "DC";

        // --- Rândul 6 (25-29): Portar ---
        if (index == 27) return "GK";

        return "Unknown";
    }


}
