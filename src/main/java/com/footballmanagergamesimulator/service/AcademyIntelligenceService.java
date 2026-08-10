package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.FacilityUpgrade;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read model for academy planning, benchmarking and player development decisions. */
@Service
public class AcademyIntelligenceService {

    private static final Set<String> YOUTH_FACILITIES = Set.of("YOUTH_ACADEMY", "YOUTH_TRAINING");

    private final YouthPlayerRepository youthPlayers;
    private final TeamRepository teams;
    private final TeamFacilitiesRepository facilities;
    private final HumanRepository humans;
    private final RoundRepository rounds;
    private final FacilityUpgradeService upgrades;

    public AcademyIntelligenceService(YouthPlayerRepository youthPlayers, TeamRepository teams,
                                      TeamFacilitiesRepository facilities, HumanRepository humans,
                                      RoundRepository rounds, FacilityUpgradeService upgrades) {
        this.youthPlayers = youthPlayers;
        this.teams = teams;
        this.facilities = facilities;
        this.humans = humans;
        this.rounds = rounds;
        this.upgrades = upgrades;
    }

    public AcademyOverview overview(long teamId) {
        int season = (int) rounds.findById(1L).orElse(new Round()).getSeason();
        Team ownTeam = teams.findById(teamId).orElseGet(() -> blankTeam(teamId));
        List<YouthPlayer> history = youthPlayers.findAllByTeamId(teamId);
        List<YouthPlayer> active = history.stream().filter(this::active).toList();
        TeamFacilities ownFacilities = facilities.findByTeamId(teamId);
        int academyLevel = level(ownFacilities == null ? 1 : ownFacilities.getYouthAcademyLevel());
        int trainingLevel = level(ownFacilities == null ? 1 : ownFacilities.getYouthTrainingLevel());
        int hoydQuality = hoydQuality(teamId);

        List<ProspectView> prospects = active.stream().map(this::prospectView)
                .sorted(Comparator.comparingInt(ProspectView::potentialAbility).reversed()
                        .thenComparing(Comparator.comparingDouble(ProspectView::readinessPercent).reversed()))
                .toList();
        Pipeline pipeline = pipeline(history, active, season);
        List<AcademyRank> ranking = ranking(teamId);
        AcademyRank ownRank = ranking.stream().filter(row -> row.teamId() == teamId).findFirst()
                .orElse(new AcademyRank(teamId, ownTeam.getName(), 0, 0, 0, academyLevel,
                        trainingLevel, hoydQuality, active.size(), 0, 0));

        Map<String, Long> positions = active.stream().collect(Collectors.groupingBy(
                player -> positionGroup(player.getPosition()), LinkedHashMap::new, Collectors.counting()));
        return new AcademyOverview(teamId, ownTeam.getName(), season, ownTeam.getTotalFinances(),
                academyLevel, trainingLevel, hoydQuality, ownRank.leagueRank(), ownRank.worldRank(),
                pipeline, prospects, positions, youthFacilityOptions(teamId), ranking);
    }

    @SuppressWarnings("unchecked")
    private List<YouthFacilityOption> youthFacilityOptions(long teamId) {
        Map<String, Object> full = upgrades.getFullFacilityOverview(teamId);
        List<Map<String, Object>> available = (List<Map<String, Object>>) full.getOrDefault("availableUpgrades", List.of());
        List<FacilityUpgrade> inProgress = (List<FacilityUpgrade>) full.getOrDefault("upgradesInProgress", List.of());
        Map<String, FacilityUpgrade> activeByType = inProgress.stream().collect(Collectors.toMap(
                FacilityUpgrade::getFacilityType, Function.identity(), (left, right) -> left));
        List<YouthFacilityOption> result = new ArrayList<>();
        for (Map<String, Object> item : available) {
            String type = String.valueOf(item.get("type"));
            if (!YOUTH_FACILITIES.contains(type)) continue;
            FacilityUpgrade active = activeByType.get(type);
            result.add(new YouthFacilityOption(type, String.valueOf(item.get("name")),
                    String.valueOf(item.get("description")), number(item.get("currentLevel")),
                    number(item.get("maxLevel")), Boolean.TRUE.equals(item.get("canUpgrade")),
                    longNumber(item.get("upgradeCost")), number(item.get("upgradeDuration")),
                    active != null, active == null ? 0 : active.getTargetLevel(),
                    active == null ? 0 : active.getStartDay(), active == null ? 0 : active.getStartSeason(),
                    active == null ? 0 : active.getDurationDays()));
        }
        return result;
    }

    private List<AcademyRank> ranking(long selectedTeamId) {
        List<Team> allTeams = teams.findAll();
        Set<Long> ids = allTeams.stream().map(Team::getId).collect(Collectors.toSet());
        Map<Long, TeamFacilities> facilityByTeam = facilities.findAllByTeamIdIn(ids).stream()
                .collect(Collectors.toMap(TeamFacilities::getTeamId, Function.identity(), (left, right) -> left));
        Map<Long, List<YouthPlayer>> youthByTeam = youthPlayers.findAll().stream()
                .filter(player -> ids.contains(player.getTeamId())).collect(Collectors.groupingBy(YouthPlayer::getTeamId));
        Map<Long, Integer> hoydByTeam = new HashMap<>();
        humans.findAllByTeamIdInAndTypeIdIn(ids, List.of(TypeNames.HOYD_TYPE)).forEach(coach ->
                hoydByTeam.merge(coach.getTeamId(), coach.getWorkingWithYoungsters(), Math::max));

        List<AcademyRank> rows = new ArrayList<>();
        for (Team team : allTeams) {
            TeamFacilities tf = facilityByTeam.get(team.getId());
            int academy = level(tf == null ? 1 : tf.getYouthAcademyLevel());
            int training = level(tf == null ? 1 : tf.getYouthTrainingLevel());
            int hoyd = hoydByTeam.getOrDefault(team.getId(), 5);
            List<YouthPlayer> all = youthByTeam.getOrDefault(team.getId(), List.of());
            List<YouthPlayer> active = all.stream().filter(this::active).toList();
            double averagePotential = active.stream().mapToInt(YouthPlayer::getPotentialAbility).average().orElse(0);
            int graduates = (int) all.stream().filter(player -> "PROMOTED".equals(player.getStatus())).count();
            double score = academy * 2.0 + training * 2.0 + hoyd
                    + averagePotential * .25 + Math.min(15, graduates * 3);
            rows.add(new AcademyRank(team.getId(), team.getName(), team.getCompetitionId(), 0, 0,
                    academy, training, hoyd, active.size(), graduates, round(Math.min(100, score))));
        }
        rows.sort(Comparator.comparingDouble(AcademyRank::score).reversed().thenComparing(AcademyRank::teamName));
        Map<Long, Integer> world = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) world.put(rows.get(index).teamId(), index + 1);
        long leagueId = allTeams.stream().filter(team -> team.getId() == selectedTeamId)
                .mapToLong(Team::getCompetitionId).findFirst().orElse(0);
        List<AcademyRank> leagueRows = rows.stream().filter(row -> row.competitionId() == leagueId).toList();
        Map<Long, Integer> league = new HashMap<>();
        for (int index = 0; index < leagueRows.size(); index++) league.put(leagueRows.get(index).teamId(), index + 1);
        return rows.stream().map(row -> new AcademyRank(row.teamId(), row.teamName(), row.competitionId(),
                world.get(row.teamId()), league.getOrDefault(row.teamId(), 0), row.academyLevel(),
                row.trainingLevel(), row.hoydQuality(), row.activeProspects(), row.graduates(), row.score())).toList();
    }

    private ProspectView prospectView(YouthPlayer player) {
        double readiness = player.getPotentialAbility() <= 0 ? 0
                : player.getCurrentAbility() * 100.0 / player.getPotentialAbility();
        long wage = Math.max(500, WageService.baseWage(player.getCurrentAbility()));
        long value = TransferValueCalculator.calculate(player.getAge(), player.getPosition(), player.getCurrentAbility());
        String recommendation = readiness >= 80 ? "READY" : player.getAge() >= 18 ? "REVIEW"
                : player.getPotentialAbility() >= 75 ? "PRIORITY" : "DEVELOP";
        return new ProspectView(player.getId(), player.getName(), player.getAge(), player.getPosition(),
                player.getCurrentAbility(), player.getPotentialAbility(), round(readiness),
                Math.max(0, player.getPotentialAbility() - player.getCurrentAbility()), player.getPotential(),
                player.getDaysInAcademy(), player.getSeasonJoined(), wage, value, recommendation);
    }

    private Pipeline pipeline(List<YouthPlayer> history, List<YouthPlayer> active, int season) {
        int ready = (int) active.stream().filter(player -> player.getPotentialAbility() > 0
                && player.getCurrentAbility() * 1.0 / player.getPotentialAbility() >= .8).count();
        int elite = (int) active.stream().filter(player -> player.getPotentialAbility() >= 75).count();
        int promoted = (int) history.stream().filter(player -> "PROMOTED".equals(player.getStatus())).count();
        int graduatesFromCurrentIntake = (int) history.stream().filter(player -> "PROMOTED".equals(player.getStatus())
                && player.getSeasonJoined() == season).count();
        int released = (int) history.stream().filter(player -> "RELEASED".equals(player.getStatus())).count();
        return new Pipeline(active.size(), ready, elite,
                round(active.stream().mapToInt(YouthPlayer::getCurrentAbility).average().orElse(0)),
                round(active.stream().mapToInt(YouthPlayer::getPotentialAbility).average().orElse(0)),
                promoted, graduatesFromCurrentIntake, released);
    }

    private int hoydQuality(long teamId) {
        return humans.findAllByTeamIdAndTypeId(teamId, TypeNames.HOYD_TYPE).stream()
                .mapToInt(Human::getWorkingWithYoungsters).max().orElse(5);
    }

    private boolean active(YouthPlayer player) { return "IN_ACADEMY".equals(player.getStatus()); }
    private int level(long value) { return Math.max(1, (int) value); }
    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private long longNumber(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private String positionGroup(String position) {
        String value = position == null ? "" : position.toUpperCase();
        if (value.contains("GK")) return "Goalkeepers";
        if (value.contains("D") || value.contains("WB")) return "Defenders";
        if (value.contains("M")) return "Midfielders";
        return "Forwards";
    }
    private Team blankTeam(long teamId) { Team team = new Team(); team.setId(teamId); team.setName("Club"); return team; }

    public record AcademyOverview(long teamId, String teamName, int season, long clubFinances,
                                  int academyLevel, int trainingLevel, int hoydQuality,
                                  int leagueRank, int worldRank, Pipeline pipeline,
                                  List<ProspectView> prospects, Map<String, Long> positionCoverage,
                                  List<YouthFacilityOption> facilities, List<AcademyRank> ranking) {}
    public record Pipeline(int active, int ready, int elitePotential, double averageAbility,
                           double averagePotential, int graduates, int graduatesFromCurrentIntake, int released) {}
    public record ProspectView(long id, String name, int age, String position, int currentAbility,
                               int potentialAbility, double readinessPercent, int developmentGap,
                               String potentialBand, int daysInAcademy, int seasonJoined,
                               long projectedWage, long estimatedValue, String recommendation) {}
    public record YouthFacilityOption(String type, String name, String description, int currentLevel,
                                      int maxLevel, boolean canUpgrade, long upgradeCost, int upgradeDuration,
                                      boolean upgrading, int targetLevel, int startDay, int startSeason,
                                      int durationDays) {}
    public record AcademyRank(long teamId, String teamName, long competitionId, int worldRank, int leagueRank,
                              int academyLevel, int trainingLevel, int hoydQuality, int activeProspects,
                              int graduates, double score) {}
}
