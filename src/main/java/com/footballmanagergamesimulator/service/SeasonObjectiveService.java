package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the season-objective lifecycle: per-team objective generation at
 * season start ({@link #generateSeasonObjectives}) and end-of-season scoring
 * ({@link #evaluateSeasonObjectives}).
 *
 * <p>Evaluation also triggers the manager-history record + manager-firing
 * checks via {@link ManagerCareerService} — cross-cutting end-of-season
 * concerns (ManagerHistory, User.fired, GameCalendar.managerFired, AI
 * manager replacement) live in that dedicated service since sesiunea 6
 * Pass A.
 */
@Service
public class SeasonObjectiveService {

    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private SeasonObjectiveRepository seasonObjectiveRepository;

    @Autowired private ManagerCareerService managerCareerService;

    /** Per-team, per-competition objective rows seeded at season start.
     *  Objectives scale with predicted league position (ranked by team reputation
     *  inside each competition). Skips teams that already have objectives for
     *  this season (idempotent). */
    public void generateSeasonObjectives(int season) {
        List<Team> allTeams = teamRepository.findAll();
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        List<CompetitionTeamInfo> allCompTeamInfos = competitionTeamInfoRepository.findAll();

        for (Team team : allTeams) {
            List<SeasonObjective> teamObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            if (!teamObjectives.isEmpty()) continue;

            // Find which competitions this team is in (via CompetitionTeamInfo for current season, or TeamCompetitionDetail)
            Set<Long> teamCompIds = allCompTeamInfos.stream()
                    .filter(i -> i.getTeamId() == team.getId() && i.getSeasonNumber() == season)
                    .map(CompetitionTeamInfo::getCompetitionId)
                    .collect(Collectors.toSet());
            // Also check TeamCompetitionDetail (for season 1 before CompetitionTeamInfo is populated)
            allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId())
                    .forEach(d -> teamCompIds.add((long) d.getCompetitionId()));

            // Pre-compute predicted position for each competition this team is in
            // by ranking all teams in that competition by reputation
            Map<Long, Integer> predictedPositionByComp = new HashMap<>();
            Map<Long, Integer> teamCountByComp = new HashMap<>();
            for (Long compId : teamCompIds) {
                List<Team> teamsInComp = allTeams.stream()
                        .filter(t -> allCompTeamInfos.stream()
                                .anyMatch(i -> i.getTeamId() == t.getId() && i.getCompetitionId() == compId && i.getSeasonNumber() == season)
                                || allDetails.stream()
                                .anyMatch(d -> d.getTeamId() == t.getId() && d.getCompetitionId() == compId))
                        .sorted(Comparator.comparingInt(Team::getReputation).reversed())
                        .collect(Collectors.toList());
                teamCountByComp.put(compId, teamsInComp.size());
                for (int pos = 0; pos < teamsInComp.size(); pos++) {
                    if (teamsInComp.get(pos).getId() == team.getId()) {
                        predictedPositionByComp.put(compId, pos + 1); // 1-based
                        break;
                    }
                }
            }

            for (Competition comp : allCompetitions) {
                if (!teamCompIds.contains(comp.getId())) continue;

                SeasonObjective objective = new SeasonObjective();
                objective.setTeamId(team.getId());
                objective.setSeasonNumber(season);
                objective.setCompetitionId(comp.getId());
                objective.setCompetitionName(comp.getName());
                objective.setStatus("active");

                int numTeams = teamCountByComp.getOrDefault(comp.getId(), 12);
                if (numTeams == 0) numTeams = 12;
                int predicted = predictedPositionByComp.getOrDefault(comp.getId(), numTeams / 2);

                if (comp.getTypeId() == 1) { // First League — has relegation
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int target = Math.min(predicted + 2, numTeams);
                    target = Math.max(target, 1);
                    if (target <= 3) {
                        objective.setDescription("Finish in the top " + target);
                    } else if (target <= numTeams / 2) {
                        objective.setDescription("Finish in the top half (top " + target + ")");
                    } else if (target >= numTeams - 1) {
                        objective.setDescription("Avoid relegation");
                    } else {
                        objective.setDescription("Finish in position " + target + " or higher");
                    }
                    objective.setTargetValue(target);
                } else if (comp.getTypeId() == 3) { // Second League — promotion target, no relegation
                    objective.setObjectiveType("league_position");
                    objective.setImportance("critical");
                    int target = Math.min(predicted + 2, numTeams);
                    target = Math.max(target, 1);
                    if (target <= 2) {
                        objective.setDescription("Promote to the first league (top " + target + ")");
                    } else if (target <= numTeams / 2) {
                        objective.setDescription("Finish in the top half (top " + target + ")");
                    } else {
                        objective.setDescription("Finish in position " + target + " or higher");
                    }
                    objective.setTargetValue(target);
                } else if (comp.getTypeId() == 2) { // Cup — scale realistically by predicted strength
                    objective.setObjectiveType("cup_round");
                    objective.setImportance("medium");
                    if (predicted <= 3) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the cup");
                    } else if (predicted <= 6) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the cup final");
                    } else if (predicted <= numTeams / 2) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the cup semi-final");
                    } else if (predicted <= 3 * numTeams / 4) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the cup quarter-final");
                    } else {
                        // Weak side: just being in the cup is fine, no realistic deep run
                        objective.setTargetValue(0);
                        objective.setDescription("Compete honorably in the cup");
                    }
                } else if (comp.getTypeId() == 4) { // LoC — scale by predicted strength
                    objective.setObjectiveType("european_round");
                    objective.setImportance("high");
                    if (predicted <= 2) {
                        objective.setTargetValue(4);
                        objective.setDescription("Win the League of Champions");
                    } else if (predicted <= 5) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the LoC final");
                    } else if (predicted <= 8) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the LoC semi-final");
                    } else if (predicted <= 11) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the LoC quarter-final");
                    } else {
                        // Mid/lower-table side qualifying through coefficient cascade —
                        // just getting past the group stage is the realistic ceiling.
                        objective.setTargetValue(0);
                        objective.setDescription("Qualify from the group stage");
                    }
                } else if (comp.getTypeId() == 5) { // Stars Cup — scale by predicted strength
                    objective.setObjectiveType("european_round");
                    objective.setImportance("medium");
                    if (predicted <= 3) {
                        objective.setTargetValue(3);
                        objective.setDescription("Reach the Stars Cup final");
                    } else if (predicted <= 8) {
                        objective.setTargetValue(2);
                        objective.setDescription("Reach the Stars Cup semi-final");
                    } else if (predicted <= 11) {
                        objective.setTargetValue(1);
                        objective.setDescription("Reach the Stars Cup quarter-final");
                    } else {
                        objective.setTargetValue(0);
                        objective.setDescription("Qualify from the group stage");
                    }
                } else {
                    continue; // Unknown competition type
                }

                seasonObjectiveRepository.save(objective);
            }
        }
        System.out.println("=== SEASON OBJECTIVES GENERATED FOR SEASON " + season + " ===");
    }

    /** Marks active objectives as achieved/failed based on final standings
     *  (league_position) or round reached (cup/european_round). Then triggers
     *  the manager-history record + manager-firing decisions on the controller. */
    public void evaluateSeasonObjectives(int season) {
        List<SeasonObjective> allObjectives = seasonObjectiveRepository.findAll().stream()
                .filter(o -> o.getSeasonNumber() == season && "active".equals(o.getStatus()))
                .toList();

        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        List<CompetitionTeamInfo> allCompTeamInfos = competitionTeamInfoRepository.findAll();

        for (SeasonObjective objective : allObjectives) {
            if ("league_position".equals(objective.getObjectiveType())) {
                List<TeamCompetitionDetail> leagueDetails = allDetails.stream()
                        .filter(d -> d.getCompetitionId() == objective.getCompetitionId())
                        .sorted((o1, o2) -> {
                            if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                            if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                            return o2.getGoalsFor() - o1.getGoalsFor();
                        })
                        .toList();

                int position = 1;
                for (TeamCompetitionDetail detail : leagueDetails) {
                    if (detail.getTeamId() == objective.getTeamId()) {
                        objective.setActualValue(position);
                        objective.setStatus(position <= objective.getTargetValue() ? "achieved" : "failed");
                        break;
                    }
                    position++;
                }
            } else if ("cup_round".equals(objective.getObjectiveType()) || "european_round".equals(objective.getObjectiveType())) {
                // Find highest round reached (check current season's CompetitionTeamInfo, or match data)
                CompetitionTeamInfo info = allCompTeamInfos.stream()
                        .filter(i -> i.getTeamId() == objective.getTeamId()
                                && i.getCompetitionId() == objective.getCompetitionId()
                                && i.getSeasonNumber() == season)
                        .findFirst()
                        .orElse(null);

                if (info != null) {
                    int roundReached = (int) info.getRound();
                    objective.setActualValue(roundReached);
                    objective.setStatus(roundReached >= objective.getTargetValue() ? "achieved" : "failed");
                } else {
                    objective.setActualValue(0);
                    objective.setStatus("failed");
                }
            }
            seasonObjectiveRepository.save(objective);
        }

        // Manager history record + firing decisions live in ManagerCareerService
        // (cross-cutting end-of-season concerns: ManagerHistory, User.fired, GameCalendar).
        managerCareerService.recordManagerHistory(season, allDetails);
        managerCareerService.checkManagerFiring(season);

        System.out.println("=== SEASON OBJECTIVES EVALUATED FOR SEASON " + season + " ===");
    }
}
