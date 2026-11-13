package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TrainingUpdateMarker;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TrainingUpdateMarkerRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The monthly training update: on a league's update day its players grow from what they
 * trained ({@link MonthlyTrainingGrowth}) and lose from what they did not
 * ({@link MonthlyAttributeDecline}); the rating then follows the attributes.
 *
 * <p>Human teams use their stored counters ({@link TrainingCounterService}), reset after the
 * update. AI teams use counters derived from their coach's play ({@link AiTrainingCounters});
 * for them, "untrained" means every attribute outside that set.
 */
@Service
public class MonthlyTrainingUpdateService {

    /** How long an attribute an AI coach never trains counts as untrained. */
    static final int NEVER_TRAINED_MONTHS = 99;

    @Autowired TrainingUpdateSchedule schedule;
    @Autowired TrainingUpdateMarkerRepository markerRepository;
    @Autowired TrainingCounterService counterService;
    @Autowired AiTrainingCounters aiTrainingCounters;
    @Autowired HumanRepository humanRepository;
    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired CompetitionService competitionService;
    @Autowired @Lazy MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired TrainingQuality trainingQuality;
    @Autowired TrainingDelegationService delegationService;
    @Autowired PlayerPersonalityService personalityService;
    @Autowired TeamFacilitiesRepository facilitiesRepository;
    @Autowired MatchEngineConfig engineConfig;

    private final MonthlyTrainingGrowth growth = new MonthlyTrainingGrowth(MonthlyTrainingGrowth.Settings.defaults());
    private final MonthlyAttributeDecline decline = new MonthlyAttributeDecline(MonthlyAttributeDecline.Settings.defaults());

    /** The leagues whose players are due their monthly update today. */
    public Set<Long> dueLeagues(int season, int day) {
        Map<Long, Integer> lastByLeague = markerRepository.findAll().stream()
                .collect(Collectors.toMap(TrainingUpdateMarker::getLeagueId, TrainingUpdateMarker::getLastMonthKey,
                        Math::max));
        Set<Long> due = new HashSet<>();
        for (Long league : leaguesOfAllTeams()) {
            if (schedule.isDue(league, season, day, lastByLeague.get(league))) due.add(league);
        }
        return due;
    }

    /** Records that these leagues got this month's update. */
    @Transactional
    public void markUpdated(Collection<Long> leagues, int season, int day) {
        if (leagues.isEmpty()) return;
        int monthKey = TrainingUpdateSchedule.monthKey(season, day);
        Map<Long, TrainingUpdateMarker> existing = markerRepository.findAll().stream()
                .collect(Collectors.toMap(TrainingUpdateMarker::getLeagueId, Function.identity(), (a, b) -> a));
        List<TrainingUpdateMarker> changed = new ArrayList<>();
        for (Long league : leagues) {
            TrainingUpdateMarker marker = existing.computeIfAbsent(league, id -> {
                TrainingUpdateMarker created = new TrainingUpdateMarker();
                created.setLeagueId(id);
                return created;
            });
            marker.setLastMonthKey(monthKey);
            changed.add(marker);
        }
        markerRepository.saveAll(changed);
    }

    /** The league each team plays in (team id → league id). */
    public Map<Long, Long> leagueByTeam() {
        Map<Long, Long> result = new HashMap<>();
        for (Team team : teamRepository.findAll()) result.put(team.getId(), team.getCompetitionId());
        return result;
    }

    private Set<Long> leaguesOfAllTeams() {
        return new HashSet<>(leagueByTeam().values());
    }

    /** The monthly update of a human-managed team, from its stored counters; resets them after. */
    @Transactional
    public void updateHumanTeam(long teamId, int season, int day, Random rng) {
        int monthKey = TrainingUpdateSchedule.monthKey(season, day);
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired()).toList();
        Map<Long, PlayerSkills> skillsByPlayer = skillsOf(players);
        Map<TrainingType, Double> coaches = delegationService.humanCoachRatings(teamId);
        TeamFacilities facilities = facilitiesRepository.findByTeamId(teamId);
        List<PlayerSkills> toSave = new ArrayList<>();
        for (Human player : players) {
            PlayerSkills skills = skillsOrProvision(player, skillsByPlayer, rng);
            Map<String, Integer> untrained = new HashMap<>();
            for (String attribute : relevantAttributes(skills.getPosition())) untrained.put(attribute, 0);
            untrained.putAll(counterService.monthsUntrained(teamId, player.getId(), monthKey));
            untrained.keySet().retainAll(relevantAttributes(skills.getPosition()));

            update(player, skills, counterService.counters(teamId, player.getId()), untrained,
                    qByType(player, facilities, coaches), season, rng);
            toSave.add(skills);
        }
        humanRepository.saveAll(players);
        playerSkillsRepository.saveAll(toSave);
        counterService.resetCounts(teamId);
        matchSimulationOrchestrator.invalidateSquadCaches(teamId);
    }

    /** The monthly update of AI-managed teams, from counters derived from their coach's play. */
    @Transactional
    public void updateAiTeams(Collection<Long> teamIds, int season, Random rng) {
        if (teamIds.isEmpty()) return;
        List<Human> players = humanRepository.findAllByTeamIdInAndTypeIdAndRetiredFalse(teamIds, TypeNames.PLAYER_TYPE);
        Map<Long, PlayerSkills> skillsByPlayer = skillsOf(players);
        Map<Long, Map<TrainingType, Double>> coachesByTeam = delegationService.optimalCoachRatings(teamIds);
        Map<Long, TeamFacilities> facilitiesByTeam = new HashMap<>();
        facilitiesRepository.findAllByTeamIdIn(teamIds).forEach(row -> facilitiesByTeam.put(row.getTeamId(), row));
        List<PlayerSkills> created = new ArrayList<>();
        for (Human player : players) {
            boolean missing = !skillsByPlayer.containsKey(player.getId());
            PlayerSkills skills = skillsOrProvision(player, skillsByPlayer, rng);
            if (missing) created.add(skills);

            Map<String, Integer> counters = aiTrainingCounters.countersFor(skills.getPosition());
            Map<String, Integer> untrained = new HashMap<>();
            for (String attribute : relevantAttributes(skills.getPosition())) {
                untrained.put(attribute, counters.containsKey(attribute) ? 0 : NEVER_TRAINED_MONTHS);
            }
            update(player, skills, counters, untrained,
                    qByType(player, facilitiesByTeam.get(player.getTeamId()),
                            coachesByTeam.getOrDefault(player.getTeamId(), Map.of())), season, rng);
        }
        // Loaded in this transaction: dirty checking saves the changes; only new rows need an insert.
        if (!created.isEmpty()) playerSkillsRepository.saveAll(created);
        teamIds.forEach(matchSimulationOrchestrator::invalidateSquadCaches);
    }

    private void update(Human player, PlayerSkills skills, Map<String, Integer> counters,
                        Map<String, Integer> monthsUntrained, Map<TrainingType, Double> qByType,
                        int season, Random rng) {
        java.util.function.ToDoubleFunction<String> q = name -> {
            TrainingType type = TrainingType.of(name);
            return type == null ? 1.0 : qByType.getOrDefault(type, 1.0);
        };
        growth.apply(skills, player.getAge(), counters, rng, q);
        decline.apply(skills, player.getAge(), monthsUntrained, rng,
                name -> trainingQuality.declineMultiplier(q.applyAsDouble(name)));
        PlayerRatingSync.refresh(player, skills, season);
    }

    /**
     * Training quality q per type for this player: his club's training facility (youth or
     * senior, by age), his professionalism and the rating of the coach delegated to the type.
     */
    Map<TrainingType, Double> qByType(Human player, TeamFacilities facilities, Map<TrainingType, Double> coaches) {
        double facilityLevel = 1;
        if (facilities != null) {
            facilityLevel = player.getAge() <= engineConfig.getTraining().getYouthMaxAge()
                    ? facilities.getYouthTrainingLevel() : facilities.getSeniorTrainingLevel();
        }
        double professionalism = personalityService.profile(player).professionalism();
        Map<TrainingType, Double> result = new java.util.EnumMap<>(TrainingType.class);
        for (TrainingType type : TrainingType.values()) {
            double coach = coaches.getOrDefault(type, TrainingDelegationService.NO_COACH);
            result.put(type, trainingQuality.q(facilityLevel, professionalism, coach));
        }
        return result;
    }

    /** The attributes a player can have: goalkeepers all of them, outfield players none of the goalkeeping ones. */
    static Set<String> relevantAttributes(String position) {
        boolean goalkeeper = "GK".equals(position);
        return PlayerSkillsService.GETTER_MAP.keySet().stream()
                .filter(name -> goalkeeper || !PlayerSkillsService.GOALKEEPER.contains(name))
                .collect(Collectors.toSet());
    }

    private Map<Long, PlayerSkills> skillsOf(List<Human> players) {
        Map<Long, PlayerSkills> result = new HashMap<>();
        if (players.isEmpty()) return result;
        for (PlayerSkills skills : playerSkillsRepository.findAllByPlayerIdIn(players.stream().map(Human::getId).toList())) {
            result.put(skills.getPlayerId(), skills);
        }
        return result;
    }

    /** A player without attributes gets them from his current rating first. */
    private PlayerSkills skillsOrProvision(Human player, Map<Long, PlayerSkills> skillsByPlayer, Random rng) {
        PlayerSkills skills = skillsByPlayer.get(player.getId());
        if (skills != null) return skills;
        skills = new PlayerSkills();
        skills.setPlayerId(player.getId());
        skills.setPosition(player.getPosition());
        competitionService.generateSkills(skills, player.getRating(), rng);
        PlayerSkillsService.calibrateOverallRating(skills, player.getRating());
        return skills;
    }
}
