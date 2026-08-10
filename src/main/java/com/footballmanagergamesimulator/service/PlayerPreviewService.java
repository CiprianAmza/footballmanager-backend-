package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.PlayerAttributeView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlayerPreviewService {

    private final PlayerSkillsRepository playerSkillsRepository;
    private final ScorerRepository scorerRepository;

    public PlayerPreviewService(PlayerSkillsRepository playerSkillsRepository,
                                ScorerRepository scorerRepository) {
        this.playerSkillsRepository = playerSkillsRepository;
        this.scorerRepository = scorerRepository;
    }

    /** Loads preview data for an entire page/list in two queries, never per player. */
    public Map<Long, Preview> previews(Collection<Human> players, int season) {
        if (players == null || players.isEmpty()) return Map.of();

        List<Long> playerIds = players.stream().map(Human::getId).distinct().toList();
        Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(playerIds).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, Function.identity(), (first, ignored) -> first));
        Map<Long, ScorerRepository.SeasonPreviewAggregate> stats =
                scorerRepository.aggregateSeasonPreview(playerIds, season).stream()
                        .collect(Collectors.toMap(ScorerRepository.SeasonPreviewAggregate::getPlayerId,
                                Function.identity()));

        Map<Long, Preview> result = new LinkedHashMap<>();
        for (Human player : players) {
            ScorerRepository.SeasonPreviewAggregate summary = stats.get(player.getId());
            result.put(player.getId(), new Preview(
                    summary == null ? 0 : summary.getAppearances().intValue(),
                    summary == null ? 0 : summary.getGoals().intValue(),
                    summary == null ? 0 : summary.getAssists().intValue(),
                    importantAttributes(player.getPosition(), skills.get(player.getId()))
            ));
        }
        return result;
    }

    List<PlayerAttributeView> importantAttributes(String rawPosition, PlayerSkills skills) {
        if (skills == null) return List.of();
        String position = rawPosition == null ? "" : rawPosition.toUpperCase(Locale.ROOT);
        List<String> names;
        if (position.contains("GK")) {
            names = List.of("Reflexes", "Handling", "One On Ones", "Command Of Area", "Kicking", "Throwing");
        } else if (position.contains("ST") || position.contains("CF")) {
            names = List.of("Finishing", "Off The Ball", "Composure", "Pace", "First Touch", "Heading");
        } else if (position.contains("AM") || position.contains("LW") || position.contains("RW")) {
            names = List.of("Dribbling", "Pace", "Acceleration", "Crossing", "Technique", "Flair");
        } else if (position.contains("DM")) {
            names = List.of("Tackling", "Passing", "Positioning", "Work Rate", "Anticipation", "Stamina");
        } else if (position.contains("MC") || position.contains("ML") || position.contains("MR")) {
            names = List.of("Passing", "Vision", "Technique", "First Touch", "Decisions", "Work Rate");
        } else {
            names = List.of("Tackling", "Marking", "Positioning", "Heading", "Pace", "Strength");
        }
        return names.stream()
                .map(name -> new PlayerAttributeView(name, PlayerSkillsService.GETTER_MAP.get(name).apply(skills)))
                .toList();
    }

    public record Preview(int appearances, int goals, int assists,
                          List<PlayerAttributeView> importantAttributes) {
    }
}
