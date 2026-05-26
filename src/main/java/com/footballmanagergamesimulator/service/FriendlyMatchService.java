package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FriendlyMatchService {

    @Autowired
    private FriendlyMatchRepository friendlyMatchRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private CalendarEventRepository calendarEventRepository;
    @Autowired
    private MatchSimulationService matchSimulationService;
    @Autowired
    private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private UserContext userContext;
    @Autowired
    @Lazy
    private TrainingService trainingService;

    private static final int PRE_SEASON_START = 1;
    private static final int PRE_SEASON_END = 30;
    private static final int WINTER_BREAK_START = 201;
    private static final int WINTER_BREAK_END = 210;
    private static final int MAX_FRIENDLIES_PER_SEASON = 8;

    /**
     * Get available opponents for a friendly match.
     * Returns teams from other leagues (different competitionId) sorted by reputation.
     */
    public List<Map<String, Object>> getAvailableOpponents(long teamId) {
        Team myTeam = teamRepository.findById(teamId).orElse(null);
        if (myTeam == null) return List.of();

        List<Team> allTeams = teamRepository.findAll();
        return allTeams.stream()
                .filter(t -> t.getId() != teamId)
                .sorted((a, b) -> Integer.compare(b.getReputation(), a.getReputation()))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("teamId", t.getId());
                    m.put("name", t.getName());
                    m.put("reputation", t.getReputation());
                    m.put("sameLeague", t.getCompetitionId() == myTeam.getCompetitionId());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get available days for scheduling a friendly.
     * Only during PRE_SEASON (days 1-30) and WINTER_BREAK (days 201-210).
     */
    public List<Map<String, Object>> getAvailableDays(long teamId, int season) {
        // Get all pending events for this season to find busy days
        Set<Integer> busyDays = new HashSet<>();
        List<CalendarEvent> allPending = calendarEventRepository.findAllBySeasonAndStatus(season, "PENDING");
        for (CalendarEvent e : allPending) {
            if (e.getEventType().startsWith("MATCH_")) {
                busyDays.add(e.getDay());
                busyDays.add(e.getDay() + 1); // rest day after match
            }
        }

        // Also check already scheduled friendlies
        List<FriendlyMatch> existingFriendlies = friendlyMatchRepository.findAllByScheduledByTeamIdAndSeason(teamId, season);
        for (FriendlyMatch fm : existingFriendlies) {
            if ("SCHEDULED".equals(fm.getStatus())) {
                busyDays.add(fm.getDay());
            }
        }

        List<Map<String, Object>> availableDays = new ArrayList<>();

        // Pre-season days
        for (int day = PRE_SEASON_START + 3; day <= PRE_SEASON_END; day++) {
            if (!busyDays.contains(day)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", day);
                m.put("phase", "PRE_SEASON");
                availableDays.add(m);
            }
        }

        // Winter break days
        for (int day = WINTER_BREAK_START; day <= WINTER_BREAK_END; day++) {
            if (!busyDays.contains(day)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", day);
                m.put("phase", "WINTER_BREAK");
                availableDays.add(m);
            }
        }

        return availableDays;
    }

    /**
     * Schedule a friendly match.
     */
    public Map<String, Object> scheduleFriendly(long teamId, long opponentTeamId, int day, int season) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Validation
        if (teamId == opponentTeamId) {
            result.put("success", false);
            result.put("error", "Cannot schedule a friendly against yourself");
            return result;
        }

        // Check if day is valid (pre-season or winter break)
        boolean isPreSeason = day >= PRE_SEASON_START && day <= PRE_SEASON_END;
        boolean isWinterBreak = day >= WINTER_BREAK_START && day <= WINTER_BREAK_END;
        if (!isPreSeason && !isWinterBreak) {
            result.put("success", false);
            result.put("error", "Friendlies can only be scheduled during pre-season (days 1-30) or winter break (days 201-210)");
            return result;
        }

        // Check max friendlies
        List<FriendlyMatch> existing = friendlyMatchRepository.findAllByScheduledByTeamIdAndSeason(teamId, season);
        long activeCount = existing.stream().filter(f -> !"CANCELLED".equals(f.getStatus())).count();
        if (activeCount >= MAX_FRIENDLIES_PER_SEASON) {
            result.put("success", false);
            result.put("error", "Maximum " + MAX_FRIENDLIES_PER_SEASON + " friendlies per season");
            return result;
        }

        // Check day not already taken by a match
        List<CalendarEvent> dayEvents = calendarEventRepository.findAllBySeasonAndDayAndStatus(season, day, "PENDING");
        boolean hasMath = dayEvents.stream().anyMatch(e -> e.getEventType().startsWith("MATCH_"));
        if (hasMath) {
            result.put("success", false);
            result.put("error", "There is already a match scheduled on this day");
            return result;
        }

        String homeTeamName = teamRepository.findNameById(teamId);
        String awayTeamName = teamRepository.findNameById(opponentTeamId);

        // Create the FriendlyMatch record
        FriendlyMatch friendly = new FriendlyMatch();
        friendly.setSeason(season);
        friendly.setDay(day);
        friendly.setHomeTeamId(teamId);
        friendly.setAwayTeamId(opponentTeamId);
        friendly.setHomeTeamName(homeTeamName);
        friendly.setAwayTeamName(awayTeamName);
        friendly.setStatus("SCHEDULED");
        friendly.setScheduledByTeamId(teamId);

        // Create calendar event
        CalendarEvent event = new CalendarEvent();
        event.setSeason(season);
        event.setDay(day);
        event.setPhase("EVENING");
        event.setEventType("MATCH_FRIENDLY");
        event.setStatus("PENDING");
        event.setTitle("Friendly: " + homeTeamName + " vs " + awayTeamName);
        event.setPriority(1);
        CalendarEvent savedEvent = calendarEventRepository.save(event);

        friendly.setCalendarEventId(savedEvent.getId());
        friendlyMatchRepository.save(friendly);

        result.put("success", true);
        result.put("matchId", friendly.getId());
        result.put("homeTeam", homeTeamName);
        result.put("awayTeam", awayTeamName);
        result.put("day", day);
        result.put("phase", isPreSeason ? "PRE_SEASON" : "WINTER_BREAK");
        return result;
    }

    /**
     * Cancel a scheduled friendly match.
     */
    public Map<String, Object> cancelFriendly(long matchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        FriendlyMatch friendly = friendlyMatchRepository.findById(matchId).orElse(null);
        if (friendly == null) {
            result.put("success", false);
            result.put("error", "Friendly match not found");
            return result;
        }
        if (!"SCHEDULED".equals(friendly.getStatus())) {
            result.put("success", false);
            result.put("error", "Can only cancel scheduled matches");
            return result;
        }

        friendly.setStatus("CANCELLED");
        friendlyMatchRepository.save(friendly);

        // Remove associated calendar event
        if (friendly.getCalendarEventId() > 0) {
            calendarEventRepository.findById(friendly.getCalendarEventId()).ifPresent(event -> {
                event.setStatus("SKIPPED");
                calendarEventRepository.save(event);
            });
        }

        result.put("success", true);
        result.put("message", "Friendly cancelled: " + friendly.getHomeTeamName() + " vs " + friendly.getAwayTeamName());
        return result;
    }

    /**
     * Get all friendly matches for a team in a season.
     */
    public List<Map<String, Object>> getFriendlyMatches(long teamId, int season) {
        List<FriendlyMatch> all = friendlyMatchRepository.findAllByScheduledByTeamIdAndSeason(teamId, season);
        // Also include friendlies where this team is an opponent (auto-scheduled)
        List<FriendlyMatch> asOpponent = friendlyMatchRepository
                .findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(season, teamId, season, teamId);
        // Merge, avoid duplicates
        Set<Long> ids = new HashSet<>();
        List<FriendlyMatch> merged = new ArrayList<>();
        for (FriendlyMatch fm : all) {
            if (ids.add(fm.getId())) merged.add(fm);
        }
        for (FriendlyMatch fm : asOpponent) {
            if (ids.add(fm.getId())) merged.add(fm);
        }

        merged.sort(Comparator.comparingInt(FriendlyMatch::getDay));

        return merged.stream().map(fm -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matchId", fm.getId());
            m.put("day", fm.getDay());
            m.put("homeTeamId", fm.getHomeTeamId());
            m.put("awayTeamId", fm.getAwayTeamId());
            m.put("homeTeamName", fm.getHomeTeamName());
            m.put("awayTeamName", fm.getAwayTeamName());
            m.put("status", fm.getStatus());
            if ("COMPLETED".equals(fm.getStatus())) {
                m.put("homeGoals", fm.getHomeGoals());
                m.put("awayGoals", fm.getAwayGoals());
                m.put("score", fm.getHomeGoals() + " - " + fm.getAwayGoals());
                m.put("homePossession", fm.getHomePossession());
                m.put("awayPossession", fm.getAwayPossession());
                m.put("homeShots", fm.getHomeShots());
                m.put("awayShots", fm.getAwayShots());
                m.put("homeShotsOnTarget", fm.getHomeShotsOnTarget());
                m.put("awayShotsOnTarget", fm.getAwayShotsOnTarget());
            }
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Simulate all friendly matches for a given day.
     * Called by GameAdvanceService when processing MATCH_FRIENDLY events.
     */
    public List<Map<String, Object>> simulateFriendliesForDay(int season, int day) {
        List<FriendlyMatch> matches = friendlyMatchRepository.findAllBySeasonAndDayAndStatus(season, day, "SCHEDULED");
        List<Map<String, Object>> results = new ArrayList<>();

        for (FriendlyMatch match : matches) {
            Map<String, Object> result = simulateFriendly(match);
            results.add(result);
        }

        return results;
    }

    /**
     * Simulate a single friendly match.
     */
    private Map<String, Object> simulateFriendly(FriendlyMatch match) {
        long homeId = match.getHomeTeamId();
        long awayId = match.getAwayTeamId();

        // Calculate team power (simplified - like AI vs AI)
        double homePower = getTeamPower(homeId);
        double awayPower = getTeamPower(awayId);

        // Calculate scores using Poisson distribution
        List<Integer> scores = matchSimulationService.calculateScores(homePower, awayPower);
        int homeGoals = scores.get(0);
        int awayGoals = scores.get(1);

        // Generate basic stats
        Random rng = new Random();
        double totalPower = homePower + awayPower;
        double homeRatio = totalPower > 0 ? homePower / totalPower : 0.5;

        int homePoss = (int) Math.round(Math.max(30, Math.min(70, homeRatio * 100 + 3 + rng.nextGaussian() * 4)));
        int homeShots = (int) (homeGoals * 3 + rng.nextInt(8) + 5);
        int awayShots = (int) (awayGoals * 3 + rng.nextInt(8) + 5);
        int homeSoT = Math.max(homeGoals, homeShots / 3 + rng.nextInt(3));
        int awaySoT = Math.max(awayGoals, awayShots / 3 + rng.nextInt(3));

        // Update friendly match
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setStatus("COMPLETED");
        match.setHomePossession(homePoss);
        match.setAwayPossession(100 - homePoss);
        match.setHomeShots(homeShots);
        match.setAwayShots(awayShots);
        match.setHomeShotsOnTarget(homeSoT);
        match.setAwayShotsOnTarget(awaySoT);
        friendlyMatchRepository.save(match);

        // Apply match day fitness loss to both teams' players
        applyFriendlyFitnessEffects(homeId);
        applyFriendlyFitnessEffects(awayId);

        // Small morale boost for winning team's players
        if (homeGoals > awayGoals) {
            applyMoraleEffect(homeId, 2.0);
            applyMoraleEffect(awayId, -1.0);
        } else if (awayGoals > homeGoals) {
            applyMoraleEffect(awayId, 2.0);
            applyMoraleEffect(homeId, -1.0);
        }

        // Send inbox notification to human teams
        sendFriendlyResultInbox(match, homeGoals, awayGoals);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchId", match.getId());
        result.put("homeTeam", match.getHomeTeamName());
        result.put("awayTeam", match.getAwayTeamName());
        result.put("score", homeGoals + " - " + awayGoals);
        result.put("homeGoals", homeGoals);
        result.put("awayGoals", awayGoals);
        result.put("homePossession", homePoss);
        result.put("awayPossession", 100 - homePoss);
        return result;
    }

    /**
     * Calculate team power for friendly (simplified version).
     * Uses top 11 players by rating.
     */
    private double getTeamPower(long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
        return players.stream()
                .filter(p -> !p.isRetired() && !"Injured".equals(p.getCurrentStatus()))
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .limit(11)
                .mapToDouble(p -> {
                    double moraleMul = 1.0 + (p.getMorale() - 70) * 0.004;
                    double fitMul = Math.max(0.7, p.getFitness() / 100.0);
                    return p.getRating() * moraleMul * fitMul;
                })
                .sum();
    }

    /**
     * Apply fitness loss for players who played a friendly.
     * Lighter than competitive matches (7-14% loss vs 10-20%).
     */
    private void applyFriendlyFitnessEffects(long teamId) {
        Random rng = new Random();
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
        List<Human> toSave = new ArrayList<>();

        // Top 11 by rating get fitness loss (they "played")
        List<Human> squad = players.stream()
                .filter(p -> !p.isRetired() && !"Injured".equals(p.getCurrentStatus()))
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .limit(11)
                .collect(Collectors.toList());

        for (Human player : squad) {
            double fitnessLoss = rng.nextDouble(7.0, 14.0);
            player.setFitness(Math.max(20.0, player.getFitness() - fitnessLoss));
            toSave.add(player);
        }

        if (!toSave.isEmpty()) humanRepository.saveAll(toSave);
    }

    /**
     * Apply a small morale change to a team's players.
     */
    private void applyMoraleEffect(long teamId, double change) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
        List<Human> toSave = new ArrayList<>();

        for (Human player : players) {
            if (player.isRetired()) continue;
            double newMorale = Math.max(0, Math.min(100, player.getMorale() + change));
            player.setMorale(newMorale);
            toSave.add(player);
        }

        if (!toSave.isEmpty()) humanRepository.saveAll(toSave);
    }

    /**
     * Send match result to human team managers.
     */
    private void sendFriendlyResultInbox(FriendlyMatch match, int homeGoals, int awayGoals) {
        List<Long> humanTeamIds = userContext.getAllHumanTeamIds();

        for (long htId : humanTeamIds) {
            if (htId == match.getHomeTeamId() || htId == match.getAwayTeamId()) {
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(htId);
                inbox.setSeasonNumber(match.getSeason());
                inbox.setRoundNumber(match.getDay());
                inbox.setTitle("Friendly Result");
                inbox.setContent(match.getHomeTeamName() + " " + homeGoals + " - " + awayGoals + " " + match.getAwayTeamName());
                inbox.setCategory("friendly");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }
    }

    /**
     * Auto-schedule pre-season friendlies for human teams.
     * Called during season calendar generation.
     * Pairs human teams with random opponents from different leagues.
     */
    public void autoSchedulePreSeasonFriendlies(int season, Set<Integer> existingMatchDays) {
        List<Long> humanTeamIds = userContext.getAllHumanTeamIds();
        if (humanTeamIds.isEmpty()) return;

        List<Team> allTeams = teamRepository.findAll();
        Random rng = new Random();

        for (long humanTeamId : humanTeamIds) {
            Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
            if (humanTeam == null) continue;

            // Find opponent candidates (different league, similar reputation)
            List<Team> candidates = allTeams.stream()
                    .filter(t -> t.getId() != humanTeamId)
                    .filter(t -> t.getCompetitionId() != humanTeam.getCompetitionId())
                    .collect(Collectors.toList());

            if (candidates.isEmpty()) {
                // Fallback: same league opponents
                candidates = allTeams.stream()
                        .filter(t -> t.getId() != humanTeamId)
                        .collect(Collectors.toList());
            }

            // Sort by reputation proximity, then shuffle within groups for variety
            candidates.sort((a, b) -> {
                int repDiffA = Math.abs(a.getReputation() - humanTeam.getReputation());
                int repDiffB = Math.abs(b.getReputation() - humanTeam.getReputation());
                return Integer.compare(repDiffA, repDiffB);
            });

            // Schedule 4 friendlies on pre-season days
            int[] friendlyDays = {7, 14, 21, 28};
            Set<Long> usedOpponents = new HashSet<>();

            for (int i = 0; i < friendlyDays.length && i < candidates.size(); i++) {
                int day = friendlyDays[i];
                // Avoid collision with existing match days
                while (existingMatchDays.contains(day) && day <= PRE_SEASON_END) day++;
                if (day > PRE_SEASON_END) continue;

                // Pick an opponent we haven't used yet
                Team opponent = null;
                for (Team c : candidates) {
                    if (!usedOpponents.contains(c.getId())) {
                        // Mix it up: pick from top 5 closest by reputation
                        List<Team> top = candidates.stream()
                                .filter(t -> !usedOpponents.contains(t.getId()))
                                .limit(5)
                                .collect(Collectors.toList());
                        if (!top.isEmpty()) {
                            opponent = top.get(rng.nextInt(top.size()));
                        }
                        break;
                    }
                }
                if (opponent == null) continue;
                usedOpponents.add(opponent.getId());

                FriendlyMatch friendly = new FriendlyMatch();
                friendly.setSeason(season);
                friendly.setDay(day);
                friendly.setHomeTeamId(humanTeamId);
                friendly.setAwayTeamId(opponent.getId());
                friendly.setHomeTeamName(teamRepository.findNameById(humanTeamId));
                friendly.setAwayTeamName(opponent.getName());
                friendly.setStatus("SCHEDULED");
                friendly.setScheduledByTeamId(humanTeamId);
                friendlyMatchRepository.save(friendly);

                existingMatchDays.add(day);
            }
        }
    }
}
