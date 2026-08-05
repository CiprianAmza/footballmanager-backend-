package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScoutingFocusService {

    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    private final ScoutingFocusRepository focuses;
    private final ScoutingFocusResultRepository results;
    private final ScoutRepository scouts;
    private final ScoutAssignmentRepository playerAssignments;
    private final TeamRepository teams;
    private final CompetitionRepository competitions;
    private final HumanRepository humans;
    private final PlayerSkillsRepository skills;
    private final GameCalendarRepository calendars;
    private final RoundRepository rounds;
    private final ManagerInboxRepository inboxes;
    private final FinanceService finances;
    private final NationService nations;
    private final Random random = new Random();

    public ScoutingFocusService(ScoutingFocusRepository focuses,
                                ScoutingFocusResultRepository results,
                                ScoutRepository scouts,
                                ScoutAssignmentRepository playerAssignments,
                                TeamRepository teams,
                                CompetitionRepository competitions,
                                HumanRepository humans,
                                PlayerSkillsRepository skills,
                                GameCalendarRepository calendars,
                                RoundRepository rounds,
                                ManagerInboxRepository inboxes,
                                FinanceService finances,
                                NationService nations) {
        this.focuses = focuses;
        this.results = results;
        this.scouts = scouts;
        this.playerAssignments = playerAssignments;
        this.teams = teams;
        this.competitions = competitions;
        this.humans = humans;
        this.skills = skills;
        this.calendars = calendars;
        this.rounds = rounds;
        this.inboxes = inboxes;
        this.finances = finances;
        this.nations = nations;
    }

    public record FocusRequest(long scoutId, String targetType, long targetId, String position,
                               Double minRating, Double maxRating, Integer minAge, Integer maxAge,
                               List<String> keyAttributes, Integer minimumAttribute, String emphasis) { }

    public Map<String, Object> catalog() {
        List<Map<String, Object>> teamTargets = teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .map(team -> Map.<String, Object>of("id", team.getId(), "name", team.getName(),
                        "competitionId", team.getCompetitionId()))
                .toList();
        List<Map<String, Object>> competitionTargets = competitions.findAll().stream()
                .sorted(Comparator.comparing(Competition::getName, String.CASE_INSENSITIVE_ORDER))
                .map(competition -> Map.<String, Object>of("id", competition.getId(),
                        "name", competition.getName(), "nationId", competition.getNationId(),
                        "typeId", competition.getTypeId()))
                .toList();
        List<Map<String, Object>> nationTargets = competitions.findAll().stream()
                .map(Competition::getNationId).filter(id -> id > 0).distinct().sorted()
                .map(id -> Map.<String, Object>of("id", id, "name", nations.infoFor(id).name()))
                .toList();
        return Map.of(
                "teams", teamTargets,
                "competitions", competitionTargets,
                "nations", nationTargets,
                "positions", humans.findDistinctActivePlayerPositions(),
                "attributes", PlayerSkillsService.GETTER_MAP.keySet(),
                "emphases", List.of("BALANCED", "CURRENT_ABILITY", "POTENTIAL", "KEY_ATTRIBUTES", "VALUE")
        );
    }

    @Transactional
    public ScoutingFocus create(long humanTeamId, FocusRequest request) {
        if (request == null) throw new IllegalArgumentException("Mission details are required.");
        Scout scout = scouts.findByIdForUpdate(request.scoutId())
                .orElseThrow(() -> new IllegalArgumentException("Scout not found."));
        if (scout.getTeamId() == null || scout.getTeamId() != humanTeamId) {
            throw new IllegalArgumentException("This scout does not work for your club.");
        }
        if (!playerAssignments.findAllByScoutIdAndStatus(scout.getId(), IN_PROGRESS).isEmpty()
                || !focuses.findAllByScoutIdAndStatus(scout.getId(), IN_PROGRESS).isEmpty()) {
            throw new IllegalArgumentException(scout.getName() + " is already on an assignment.");
        }

        Target target = resolveTarget(request.targetType(), request.targetId());
        Team team = teams.findByIdForUpdate(humanTeamId)
                .orElseThrow(() -> new IllegalArgumentException("Your club could not be found."));
        GameCalendar calendar = currentCalendar();
        if (calendar == null) throw new IllegalArgumentException("No active calendar was found.");

        double minRating = clamp(request.minRating() == null ? 0 : request.minRating(), 0, 300);
        double maxRating = clamp(request.maxRating() == null ? 300 : request.maxRating(), 0, 300);
        if (minRating > maxRating) throw new IllegalArgumentException("Minimum rating cannot exceed maximum rating.");
        int minAge = clamp(request.minAge() == null ? 15 : request.minAge(), 15, 50);
        int maxAge = clamp(request.maxAge() == null ? 40 : request.maxAge(), 15, 50);
        if (minAge > maxAge) throw new IllegalArgumentException("Minimum age cannot exceed maximum age.");
        String position = request.position() == null || request.position().isBlank() ? "ANY" : request.position().trim();
        if (!"ANY".equalsIgnoreCase(position)
                && !humans.findDistinctActivePlayerPositions().contains(position)) {
            throw new IllegalArgumentException("Unknown player position: " + position);
        }
        List<String> attributes = canonicalAttributes(request.keyAttributes());
        int minimumAttribute = clamp(request.minimumAttribute() == null ? 1 : request.minimumAttribute(), 1, 20);
        String emphasis = normalizeEmphasis(request.emphasis());

        int baseDays = switch (target.type()) {
            case "TEAM" -> 6;
            case "COMPETITION" -> 11;
            default -> 17;
        };
        long cost = switch (target.type()) {
            case "TEAM" -> 15_000L;
            case "COMPETITION" -> 30_000L;
            default -> 50_000L;
        };
        double experienceFactor = Math.max(.58, 1.04 - scout.getExperience() * .025);
        int duration = Math.max(3, (int) Math.ceil(baseDays * experienceFactor));
        if (knowsTarget(scout, target)) {
            duration = Math.max(3, duration - 2);
            cost = Math.round(cost * .75);
        }
        if (team.getTransferBudget() < cost) {
            throw new IllegalArgumentException("Insufficient transfer budget. This mission costs " + cost + ".");
        }

        finances.recordExpense(team.getId(), calendar.getSeason(), calendar.getCurrentDay(),
                "SCOUT_COST", "Recruitment focus: " + target.name(), cost);
        Team refreshed = teams.findByIdForUpdate(humanTeamId).orElse(team);
        refreshed.setTransferBudget(refreshed.getTransferBudget() - cost);
        teams.save(refreshed);

        ScoutingFocus focus = new ScoutingFocus();
        focus.setTeamId(humanTeamId);
        focus.setScoutId(scout.getId());
        focus.setScoutName(scout.getName());
        focus.setTargetType(target.type());
        focus.setTargetId(target.id());
        focus.setTargetName(target.name());
        focus.setPosition(position.toUpperCase(Locale.ROOT));
        focus.setMinRating(minRating);
        focus.setMaxRating(maxRating);
        focus.setMinAge(minAge);
        focus.setMaxAge(maxAge);
        focus.setKeyAttributes(String.join(",", attributes));
        focus.setMinimumAttribute(minimumAttribute);
        focus.setEmphasis(emphasis);
        focus.setStartDay(calendar.getCurrentDay());
        focus.setEndDay(calendar.getCurrentDay() + duration);
        focus.setSeason(calendar.getSeason());
        focus.setCost(cost);
        focus.setStatus(IN_PROGRESS);
        return focuses.save(focus);
    }

    public List<Map<String, Object>> list(long teamId, String status) {
        List<ScoutingFocus> rows = status == null || status.isBlank() || "all".equalsIgnoreCase(status)
                ? focuses.findAllByTeamIdOrderByIdDesc(teamId)
                : focuses.findAllByTeamIdAndStatusOrderByIdDesc(teamId, status.toLowerCase(Locale.ROOT));
        GameCalendar calendar = currentCalendar();
        int day = calendar == null ? 0 : calendar.getCurrentDay();
        return rows.stream().map(focus -> view(focus, day)).toList();
    }

    public List<ScoutingFocusResult> results(long humanTeamId, long focusId) {
        ScoutingFocus focus = ownedFocus(humanTeamId, focusId);
        if (!COMPLETED.equals(focus.getStatus())) return List.of();
        return results.findAllByFocusIdOrderByFitScoreDesc(focusId);
    }

    @Transactional
    public ScoutingFocus cancel(long humanTeamId, long focusId) {
        ScoutingFocus focus = ownedFocus(humanTeamId, focusId);
        if (!IN_PROGRESS.equals(focus.getStatus())) {
            throw new IllegalArgumentException("Only active recruitment focuses can be cancelled.");
        }
        focus.setStatus("cancelled");
        return focuses.save(focus);
    }

    @Transactional
    public void processCompleted(int season, int day) {
        for (ScoutingFocus focus : focuses.findAllBySeasonAndStatusAndEndDayLessThanEqual(season, IN_PROGRESS, day)) {
            complete(focus);
        }
    }

    private void complete(ScoutingFocus focus) {
        Set<Long> targetTeamIds = targetTeamIds(focus);
        List<Human> pool = targetTeamIds.isEmpty() ? List.of()
                : humans.findAllByTeamIdInAndTypeIdAndRetiredFalse(targetTeamIds, 1L).stream()
                .filter(player -> player.getTeamId() != null && player.getTeamId() != focus.getTeamId())
                .filter(player -> !player.isWillNeverLeave())
                .filter(player -> "ANY".equals(focus.getPosition())
                        || focus.getPosition().equalsIgnoreCase(player.getPosition()))
                .filter(player -> player.getAge() >= focus.getMinAge() && player.getAge() <= focus.getMaxAge())
                .filter(player -> player.getRating() >= focus.getMinRating() && player.getRating() <= focus.getMaxRating())
                .toList();

        Map<Long, PlayerSkills> skillRows = skills.findAllByPlayerIdIn(pool.stream().map(Human::getId).toList())
                .stream().collect(Collectors.toMap(PlayerSkills::getPlayerId, Function.identity(), (a, b) -> a));
        Map<Long, String> teamNames = teams.findAllById(targetTeamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        Scout scout = scouts.findById(focus.getScoutId()).orElse(null);
        int ability = scout == null ? 10 : scout.getScoutingAbility();
        int potentialJudgement = scout == null ? 10 : scout.getJudgingPotential();

        List<ScoutingFocusResult> ranked = new ArrayList<>();
        for (Human player : pool) {
            PlayerSkills playerSkills = skillRows.get(player.getId());
            List<String> matched = matchingAttributes(focus, playerSkills);
            if (!focus.getKeyAttributes().isBlank() && matched.size() < split(focus.getKeyAttributes()).size()) continue;

            double attributeAverage = matched.isEmpty() ? 10 : matched.stream()
                    .mapToInt(value -> Integer.parseInt(value.substring(value.lastIndexOf(':') + 1))).average().orElse(10);
            double fit = fitScore(focus.getEmphasis(), player, attributeAverage);
            double estimatedRating = noisy(player.getRating(), (20 - ability) * .55, 1, 300);
            double estimatedPotential = noisy(player.getPotentialAbility(), (20 - potentialJudgement) * 1.4, 1, 100);

            ScoutingFocusResult result = new ScoutingFocusResult();
            result.setFocusId(focus.getId());
            result.setTeamId(focus.getTeamId());
            result.setPlayerId(player.getId());
            result.setPlayerName(player.getName());
            result.setPosition(player.getPosition());
            result.setAge(player.getAge());
            result.setPlayerTeamId(player.getTeamId());
            result.setPlayerTeamName(teamNames.getOrDefault(player.getTeamId(), "Unknown club"));
            result.setEstimatedRating(round(estimatedRating));
            result.setEstimatedPotential(round(estimatedPotential));
            result.setEstimatedTransferValue(Math.max(0, player.getTransferValue()));
            result.setFitScore(round(fit));
            result.setMatchedAttributes(String.join(",", matched));
            result.setRecommendation(recommendation(fit));
            ranked.add(result);
        }
        ranked.sort(Comparator.comparingDouble(ScoutingFocusResult::getFitScore).reversed());
        List<ScoutingFocusResult> shortlist = ranked.stream().limit(20).toList();
        results.saveAll(shortlist);
        focus.setCandidatesFound(shortlist.size());
        focus.setStatus(COMPLETED);
        focuses.save(focus);
        notifyCompletion(focus, shortlist);
    }

    private Set<Long> targetTeamIds(ScoutingFocus focus) {
        if ("TEAM".equals(focus.getTargetType())) return Set.of(focus.getTargetId());
        if ("COMPETITION".equals(focus.getTargetType())) return teams.findAllByCompetitionId(focus.getTargetId())
                .stream().map(Team::getId).collect(Collectors.toSet());
        Set<Long> competitionIds = competitions.findAll().stream()
                .filter(c -> c.getNationId() == focus.getTargetId()).map(Competition::getId).collect(Collectors.toSet());
        return teams.findAll().stream().filter(t -> competitionIds.contains(t.getCompetitionId()))
                .map(Team::getId).collect(Collectors.toSet());
    }

    private List<String> matchingAttributes(ScoutingFocus focus, PlayerSkills row) {
        if (focus.getKeyAttributes().isBlank()) return List.of();
        if (row == null) return List.of();
        List<String> matches = new ArrayList<>();
        for (String attribute : split(focus.getKeyAttributes())) {
            int value = PlayerSkillsService.GETTER_MAP.get(attribute).apply(row);
            if (value >= focus.getMinimumAttribute()) matches.add(attribute + ":" + value);
        }
        return matches;
    }

    private double fitScore(String emphasis, Human player, double attributes) {
        double current = player.getRating() / 3.0;
        double potential = player.getPotentialAbility();
        double attribute = attributes * 5.0;
        double value = 100 - Math.min(100, Math.log10(Math.max(1, player.getTransferValue())) * 12);
        return switch (emphasis) {
            case "CURRENT_ABILITY" -> current * .65 + potential * .15 + attribute * .20;
            case "POTENTIAL" -> current * .20 + potential * .65 + attribute * .15;
            case "KEY_ATTRIBUTES" -> current * .20 + potential * .15 + attribute * .65;
            case "VALUE" -> current * .30 + potential * .20 + attribute * .15 + value * .35;
            default -> current * .38 + potential * .32 + attribute * .30;
        };
    }

    private void notifyCompletion(ScoutingFocus focus, List<ScoutingFocusResult> shortlist) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(focus.getTeamId());
        inbox.setSeasonNumber(focus.getSeason());
        inbox.setRoundNumber(focus.getEndDay());
        inbox.setTitle("Recruitment Focus Complete: " + focus.getTargetName());
        String top = shortlist.isEmpty() ? "No players met all criteria."
                : "Top recommendation: " + shortlist.get(0).getPlayerName() + " (fit "
                + Math.round(shortlist.get(0).getFitScore()) + "/100).";
        inbox.setContent(focus.getScoutName() + " completed the " + focus.getTargetType().toLowerCase(Locale.ROOT)
                + " search in " + focus.getTargetName() + ". " + shortlist.size() + " candidates found. " + top);
        inbox.setCategory("scouting");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inboxes.save(inbox);
    }

    private Target resolveTarget(String rawType, long id) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "TEAM" -> {
                Team team = teams.findById(id).orElseThrow(() -> new IllegalArgumentException("Target club not found."));
                yield new Target(type, id, team.getName(), team.getCompetitionId());
            }
            case "COMPETITION" -> {
                Competition competition = competitions.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Target competition not found."));
                yield new Target(type, id, competition.getName(), competition.getId());
            }
            case "NATION" -> {
                if (id <= 0 || competitions.findAll().stream().noneMatch(c -> c.getNationId() == id)) {
                    throw new IllegalArgumentException("Target nation not found.");
                }
                yield new Target(type, id, nations.infoFor(id).name(), 0);
            }
            default -> throw new IllegalArgumentException("Target type must be TEAM, COMPETITION or NATION.");
        };
    }

    private boolean knowsTarget(Scout scout, Target target) {
        if (scout.getKnownLeagues() == null || scout.getKnownLeagues().isBlank()) return false;
        Set<Long> known = Arrays.stream(scout.getKnownLeagues().split(",")).map(String::trim)
                .filter(value -> !value.isBlank()).map(Long::parseLong).collect(Collectors.toSet());
        if (target.competitionId() > 0) return known.contains(target.competitionId());
        return competitions.findAll().stream().anyMatch(c -> c.getNationId() == target.id() && known.contains(c.getId()));
    }

    private List<String> canonicalAttributes(List<String> requested) {
        if (requested == null) return List.of();
        Map<String, String> canonical = PlayerSkillsService.GETTER_MAP.keySet().stream()
                .collect(Collectors.toMap(name -> name.toLowerCase(Locale.ROOT), Function.identity()));
        return requested.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .map(value -> canonical.get(value.toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull).distinct().limit(6).toList();
    }

    private String normalizeEmphasis(String value) {
        String emphasis = value == null ? "BALANCED" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("BALANCED", "CURRENT_ABILITY", "POTENTIAL", "KEY_ATTRIBUTES", "VALUE").contains(emphasis)
                ? emphasis : "BALANCED";
    }

    private ScoutingFocus ownedFocus(long teamId, long focusId) {
        ScoutingFocus focus = focuses.findById(focusId)
                .orElseThrow(() -> new IllegalArgumentException("Recruitment focus not found."));
        if (focus.getTeamId() != teamId) throw new IllegalArgumentException("This recruitment focus belongs to another club.");
        return focus;
    }

    private Map<String, Object> view(ScoutingFocus focus, int day) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", focus.getId());
        view.put("scoutId", focus.getScoutId());
        view.put("scoutName", focus.getScoutName());
        view.put("targetType", focus.getTargetType());
        view.put("targetId", focus.getTargetId());
        view.put("targetName", focus.getTargetName());
        view.put("position", focus.getPosition());
        view.put("minRating", focus.getMinRating());
        view.put("maxRating", focus.getMaxRating());
        view.put("minAge", focus.getMinAge());
        view.put("maxAge", focus.getMaxAge());
        view.put("keyAttributes", split(focus.getKeyAttributes()));
        view.put("minimumAttribute", focus.getMinimumAttribute());
        view.put("emphasis", focus.getEmphasis());
        view.put("startDay", focus.getStartDay());
        view.put("endDay", focus.getEndDay());
        view.put("daysRemaining", IN_PROGRESS.equals(focus.getStatus()) ? Math.max(0, focus.getEndDay() - day) : 0);
        view.put("season", focus.getSeason());
        view.put("cost", focus.getCost());
        view.put("status", focus.getStatus());
        view.put("candidatesFound", focus.getCandidatesFound());
        return view;
    }

    private GameCalendar currentCalendar() {
        int season = (int) rounds.findById(1L).orElse(new Round()).getSeason();
        List<GameCalendar> rows = calendars.findBySeason(season);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private double noisy(double value, double margin, double min, double max) {
        return clamp(value + (random.nextDouble() * 2 - 1) * margin, min, max);
    }

    private static String recommendation(double score) {
        if (score >= 82) return "TOP_TARGET";
        if (score >= 70) return "STRONG_MATCH";
        if (score >= 58) return "MONITOR";
        return "DEPTH_OPTION";
    }

    private static double round(double value) { return Math.round(value * 10) / 10.0; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private record Target(String type, long id, String name, long competitionId) { }
}
