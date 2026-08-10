package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Single source of display metadata for every competition surface. */
@Service
public class CompetitionMetadataService {

    private final NationService nationService;

    public CompetitionMetadataService(NationService nationService) {
        this.nationService = nationService;
    }

    public Map<String, Object> metadata(Competition competition) {
        NationService.NationInfo nation = nationService.infoFor(competition.getNationId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", kind(competition));
        view.put("categoryLabel", categoryLabel(competition));
        view.put("scopeLabel", competition.getNationId() == 0 ? "Continental" : "Domestic");
        view.put("formatLabel", formatLabel(competition));
        view.put("tierLabel", competition.isLeague() ? "Tier " + Math.max(1, competition.getTier()) : null);
        view.put("nationName", nation.name());
        view.put("nationFlagCode", nation.flagCode());
        view.put("shortCode", shortCode(competition.getName()));
        view.put("description", description(competition));
        return view;
    }

    public String kind(Competition competition) {
        if (competition.isLeague()) return "LEAGUE";
        if (competition.getTypeId() == Competition.CUP) return "KNOCKOUT";
        if (competition.getTypeId() == Competition.SUPER_CUP) return "SHOWCASE";
        return "MULTI_STAGE";
    }

    private String categoryLabel(Competition competition) {
        return switch (kind(competition)) {
            case "LEAGUE" -> "League competition";
            case "KNOCKOUT" -> "Knockout competition";
            case "SHOWCASE" -> "Season showcase";
            default -> "Continental club competition";
        };
    }

    private String formatLabel(Competition competition) {
        return switch (kind(competition)) {
            case "LEAGUE" -> "Round-robin table";
            case "KNOCKOUT" -> "Knockout bracket";
            case "SHOWCASE" -> "Single-match final";
            default -> "Qualifying, groups and knockout";
        };
    }

    private String description(Competition competition) {
        String level = competition.isLeague() ? " at tier " + Math.max(1, competition.getTier()) : "";
        return switch (kind(competition)) {
            case "LEAGUE" -> "Domestic season competition" + level + " with standings and qualification outcomes.";
            case "KNOCKOUT" -> "Domestic elimination tournament with a staged route to the final.";
            case "SHOWCASE" -> "A short-form trophy decided in a final.";
            default -> "Cross-border competition with multiple entry stages and progression routes.";
        };
    }

    private String shortCode(String name) {
        if (name == null || name.isBlank()) return "COMP";
        StringBuilder code = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.length() < 3 && code.length() > 0) continue;
            code.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            if (code.length() == 3) break;
        }
        if (code.length() < 2) {
            String compact = name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            return compact.substring(0, Math.min(4, compact.length()));
        }
        return code.toString();
    }
}
