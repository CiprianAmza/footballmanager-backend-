package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only world rankings used by the public Overview pages. */
@Service
public class WorldOverviewService {

    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final PlayerSkillsRepository playerSkillsRepository;
    private final CompetitionRepository competitionRepository;
    private final PlayerValueService playerValueService;
    private final TacticService tacticService;
    private final GameStateService gameStateService;

    public WorldOverviewService(TeamRepository teamRepository,
                                HumanRepository humanRepository,
                                PlayerSkillsRepository playerSkillsRepository,
                                CompetitionRepository competitionRepository,
                                PlayerValueService playerValueService,
                                TacticService tacticService,
                                GameStateService gameStateService) {
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.playerSkillsRepository = playerSkillsRepository;
        this.competitionRepository = competitionRepository;
        this.playerValueService = playerValueService;
        this.tacticService = tacticService;
        this.gameStateService = gameStateService;
    }

    public Map<String, Object> teamValues() {
        WorldData data = loadWorldData();
        Map<Long, Human> managersByTeam = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                .filter(manager -> !manager.isRetired() && manager.getTeamId() != null && manager.getTeamId() > 0)
                .collect(Collectors.toMap(Human::getTeamId, Function.identity(), (current, ignored) -> current));

        List<TeamValueRow> rows = new ArrayList<>();
        for (Team team : data.teams()) {
            List<Human> squad = data.playersByTeam().getOrDefault(team.getId(), List.of());
            if (squad.isEmpty()) continue;

            Human manager = managersByTeam.get(team.getId());
            String currentFormation = manager != null && manager.getTacticStyle() != null
                    ? manager.getTacticStyle() : "442";
            FormationScore current = formationScore(squad, data.skillsByPlayer(), currentFormation);
            FormationScore best = tacticService.getAllExistingTactics().stream()
                    .map(formation -> formationScore(squad, data.skillsByPlayer(), formation))
                    .max(Comparator.comparingDouble(FormationScore::rating))
                    .orElse(current);

            long marketValue = squad.stream().mapToLong(Human::getTransferValue).sum();
            rows.add(new TeamValueRow(
                    0,
                    team.getId(),
                    team.getName(),
                    team.getColor1(),
                    team.getColor2(),
                    team.getReputation(),
                    manager != null ? manager.getName() : "No manager",
                    current.formation(),
                    round2(current.rating()),
                    best.formation(),
                    round2(best.rating()),
                    marketValue,
                    squad.size()
            ));
        }

        rows.sort(Comparator.comparingDouble(TeamValueRow::bestPossibleXiRating).reversed()
                .thenComparing(TeamValueRow::teamName));
        List<TeamValueRow> ranked = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            TeamValueRow row = rows.get(index);
            ranked.add(new TeamValueRow(index + 1, row.teamId(), row.teamName(), row.color1(), row.color2(),
                    row.reputation(), row.managerName(), row.currentFormation(), row.currentXiRating(),
                    row.bestFormation(), row.bestPossibleXiRating(), row.squadMarketValue(), row.playerCount()));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("season", gameStateService.currentSeason());
        response.put("ratingDefinition", "Position-weighted intrinsic value of the selected eleven; morale, fitness and injuries are excluded.");
        response.put("teams", ranked);
        return response;
    }

    public Map<String, Object> worldBestEleven() {
        WorldData data = loadWorldData();
        Map<Long, Team> teamsById = data.teams().stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        Map<Long, Competition> competitionsById = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Function.identity()));

        List<WorldSlotDefinition> formation = List.of(
                new WorldSlotDefinition("ST-L", "ST", 35, 14),
                new WorldSlotDefinition("ST-R", "ST", 65, 14),
                new WorldSlotDefinition("ML", "ML", 13, 43),
                new WorldSlotDefinition("MC-L", "MC", 38, 48),
                new WorldSlotDefinition("MC-R", "MC", 62, 48),
                new WorldSlotDefinition("MR", "MR", 87, 43),
                new WorldSlotDefinition("DL", "DL", 13, 73),
                new WorldSlotDefinition("DC-L", "DC", 38, 77),
                new WorldSlotDefinition("DC-R", "DC", 62, 77),
                new WorldSlotDefinition("DR", "DR", 87, 73),
                new WorldSlotDefinition("GK", "GK", 50, 91)
        );

        Set<Long> selectedPlayerIds = new HashSet<>();
        List<WorldBestPlayer> selected = new ArrayList<>();
        for (WorldSlotDefinition slot : formation) {
            Human player = data.players().stream()
                    .filter(candidate -> !selectedPlayerIds.contains(candidate.getId()))
                    .filter(candidate -> slot.position().equals(TacticService.getBasePosition(candidate.getPosition())))
                    .max(Comparator.comparingDouble(candidate -> intrinsicValue(
                            candidate, data.skillsByPlayer().get(candidate.getId()), slot.position())))
                    .orElse(null);
            if (player == null) continue;
            selectedPlayerIds.add(player.getId());

            Team team = teamsById.get(player.getTeamId());
            Competition competition = team != null ? competitionsById.get(team.getCompetitionId()) : null;
            selected.add(toWorldPlayer(slot, player, data.skillsByPlayer().get(player.getId()), team,
                    competition != null ? competition.getNationId() : 0));
        }

        double total = selected.stream().mapToDouble(WorldBestPlayer::positionRating).sum();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("season", gameStateService.currentSeason());
        response.put("formation", "4-4-2");
        response.put("selectionRule", "Best intrinsic position-weighted player for every slot; current morale, fitness and injuries do not affect selection.");
        response.put("totalRating", round2(total));
        response.put("players", selected);
        return response;
    }

    private WorldBestPlayer toWorldPlayer(WorldSlotDefinition slot, Human player, PlayerSkills skills,
                                          Team team, long nationId) {
        return new WorldBestPlayer(
                slot.slot(), slot.position(), slot.xPercent(), slot.yPercent(),
                player.getId(), player.getName(), player.getPosition(), round2(player.getRating()),
                round2(intrinsicValue(player, skills, slot.position())), player.getAge(),
                team != null ? team.getId() : 0,
                team != null ? team.getName() : "Free Agent",
                team != null ? team.getColor1() : null,
                team != null ? team.getColor2() : null,
                nationId,
                player.getBaseFaceId(), player.getSkinTone(), player.getHairStyle(), player.getHairColor(),
                player.getEyeColor(), player.getFaceShape(), player.getNoseShape(), player.getEyeShape(),
                player.getMouthShape(), player.getBrowShape(), player.getSpecies()
        );
    }

    private FormationScore formationScore(List<Human> squad, Map<Long, PlayerSkills> skillsByPlayer,
                                          String formation) {
        Map<String, Integer> needs = tacticService.getRoomInTeamByTactic(formation);
        List<String> slots = needs.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> tacticService.getValueForTacticDisplay(entry.getKey())))
                .flatMap(entry -> java.util.stream.IntStream.range(0, entry.getValue())
                        .mapToObj(ignored -> TacticService.getBasePosition(entry.getKey())))
                .toList();
        Set<Long> used = new HashSet<>();
        double total = 0;
        for (String position : slots) {
            Human selected = squad.stream()
                    .filter(player -> !used.contains(player.getId()))
                    .filter(player -> position.equals(TacticService.getBasePosition(player.getPosition())))
                    .max(Comparator.comparingDouble(player -> intrinsicValue(
                            player, skillsByPlayer.get(player.getId()), position)))
                    .orElseGet(() -> squad.stream()
                            .filter(player -> !used.contains(player.getId()))
                            .max(Comparator.comparingDouble(player -> intrinsicValue(
                                    player, skillsByPlayer.get(player.getId()), position)))
                            .orElse(null));
            if (selected == null) continue;
            used.add(selected.getId());
            total += intrinsicValue(selected, skillsByPlayer.get(selected.getId()), position);
        }
        return new FormationScore(formation, total);
    }

    private double intrinsicValue(Human player, PlayerSkills skills, String usedPosition) {
        String natural = TacticService.getBasePosition(player.getPosition());
        String used = TacticService.getBasePosition(usedPosition);
        if (skills != null) {
            return playerValueService.computePositionalValue(skills, used)
                    * playerValueService.familiarityFactor(natural, used);
        }
        return player.getRating() * playerValueService.familiarityFactor(natural, used);
    }

    private WorldData loadWorldData() {
        List<Team> teams = teamRepository.findAll();
        List<Human> players = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired() && player.getTeamId() != null && player.getTeamId() > 0)
                .toList();
        Map<Long, List<Human>> playersByTeam = players.stream()
                .collect(Collectors.groupingBy(Human::getTeamId));
        List<Long> playerIds = players.stream().map(Human::getId).toList();
        Map<Long, PlayerSkills> skillsByPlayer = playerSkillsRepository.findAllByPlayerIdIn(playerIds).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, Function.identity(), (left, ignored) -> left));
        return new WorldData(teams, players, playersByTeam, skillsByPlayer);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record WorldData(List<Team> teams, List<Human> players,
                             Map<Long, List<Human>> playersByTeam,
                             Map<Long, PlayerSkills> skillsByPlayer) {}

    private record FormationScore(String formation, double rating) {}

    private record WorldSlotDefinition(String slot, String position, int xPercent, int yPercent) {}

    public record TeamValueRow(int rank, long teamId, String teamName, String color1, String color2,
                               int reputation, String managerName, String currentFormation,
                               double currentXiRating, String bestFormation,
                               double bestPossibleXiRating, long squadMarketValue, int playerCount) {}

    public record WorldBestPlayer(String slot, String slotPosition, int xPercent, int yPercent,
                                  long playerId, String playerName, String naturalPosition,
                                  double overallRating, double positionRating, int age,
                                  long teamId, String teamName, String teamColor1, String teamColor2,
                                  long nationId,
                                  int baseFaceId, int skinTone, int hairStyle, int hairColor, int eyeColor,
                                  int faceShape, int noseShape, int eyeShape, int mouthShape, int browShape,
                                  String species) {}
}
