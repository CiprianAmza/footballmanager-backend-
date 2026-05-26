package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class LiveMatchSimulationService {

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private CompetitionRepository competitionRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private GoalAnimationService goalAnimationService;

    private final Map<String, LiveMatchData> liveMatchCache = new ConcurrentHashMap<>();

    // Commentary templates
    private static final String[] POSSESSION_COMMENTARY = {
            "%s controlling the tempo in midfield.",
            "%s keeping the ball well, probing for an opening.",
            "Patient build-up play from %s.",
            "%s passing it around confidently.",
            "Good movement off the ball by %s players.",
            "%s dominating possession in the middle third.",
            "Neat interplay between the %s midfielders.",
            "%s looking composed on the ball."
    };

    private static final String[] BUILDUP_COMMENTARY = {
            "%s working the ball down the right flank.",
            "A switch of play from %s, moving it to the left side.",
            "%s pushing forward with intent.",
            "Good pressing from %s, forcing a turnover.",
            "The ball recycled back to the %s defence.",
            "%s looking to play on the counter-attack.",
            "A long ball forward by %s, but it's dealt with.",
            "%s patiently waiting for the right moment to strike."
    };

    private static final String[] GOAL_DESCRIPTIONS = {
            "A clinical finish into the bottom corner!",
            "Powerful header from close range!",
            "A stunning long-range strike that flies into the top corner!",
            "Cool and composed finish, slotted past the keeper!",
            "Tap in from close range after a great cross!",
            "A brilliant volley that leaves the goalkeeper no chance!",
            "A curling effort that bends into the far post!",
            "One-on-one with the keeper, and it's buried!",
            "A free kick that dips over the wall and in!",
            "A penalty, confidently dispatched!"
    };

    private static final String[] SAVE_DESCRIPTIONS = {
            "shoots but the goalkeeper makes a brilliant save!",
            "forces a good save from the keeper, diving to his right!",
            "fires a powerful shot, but it's straight at the goalkeeper!",
            "tries to curl one in, but the keeper is equal to it!",
            "heads towards goal, but the goalkeeper tips it over the bar!"
    };

    private static final String[] MISS_DESCRIPTIONS = {
            "fires wide of the far post!",
            "blazes it over the crossbar!",
            "drags the shot wide from a good position.",
            "hits it just past the post! So close!",
            "skies the shot from inside the box.",
            "scuffs the shot and it rolls harmlessly wide."
    };

    private static final String[] BLOCK_DESCRIPTIONS = {
            "'s shot is blocked by a brave defensive challenge!",
            " tries to shoot but it's blocked at the last second!",
            " lets fly but it deflects off a defender!",
            "'s effort is charged down by the defence!"
    };

    private static final String[] FOUL_DESCRIPTIONS = {
            "brings down the opponent with a late challenge.",
            "commits a cynical foul to stop the counter-attack.",
            "clips the attacker's heels. Free kick.",
            "goes in too hard and concedes a foul.",
            "slides in and takes the man instead of the ball."
    };

    // ==================== PUBLIC API ====================

    public LiveMatchData simulateLiveMatch(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round) {
        return simulateLiveMatch(teamId1, teamId2, power1, power2, competitionId, season, round, true);
    }

    public LiveMatchData simulateLiveMatch(
            long teamId1, long teamId2,
            double power1, double power2,
            long competitionId, int season, int round,
            boolean generateGoalAnimations) {

        Random random = new Random();

        String homeTeamName = teamRepository.findNameById(teamId1);
        String awayTeamName = teamRepository.findNameById(teamId2);
        String competitionName = competitionRepository.findNameById(competitionId);

        // Load outfield players for each team (for goalscoring/events)
        List<Human> team1Outfield = getOutfieldPlayers(teamId1);
        List<Human> team2Outfield = getOutfieldPlayers(teamId2);
        List<Human> team1All = humanRepository.findAllByTeamIdAndTypeId(teamId1, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());
        List<Human> team2All = humanRepository.findAllByTeamIdAndTypeId(teamId2, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());

        // Goal animations (keyed by minute)
        Map<Integer, GoalAnimationData> goalAnimations = generateGoalAnimations
                ? new LinkedHashMap<>() : null;

        // Match state
        int homeScore = 0, awayScore = 0;
        int homeShots = 0, awayShots = 0;
        int homeShotsOnTarget = 0, awayShotsOnTarget = 0;
        int homeCorners = 0, awayCorners = 0;
        int homeFouls = 0, awayFouls = 0;
        int homeYellowCards = 0, awayYellowCards = 0;
        int homeRedCards = 0, awayRedCards = 0;
        int homeOffsides = 0, awayOffsides = 0;
        int homePossessionMinutes = 0;

        List<LiveMatchMinute> timeline = new ArrayList<>();
        List<MatchEvent> dbEvents = new ArrayList<>();

        // Substitution tracking
        boolean[] homeSubs = new boolean[3];
        boolean[] awaySubs = new boolean[3];
        int[] homeSubMinutes = generateSubMinutes(random);
        int[] awaySubMinutes = generateSubMinutes(random);

        double totalPower = power1 + power2;
        double team1PossChance = totalPower > 0 ? power1 / totalPower : 0.5;
        // Slight home advantage
        team1PossChance = Math.min(0.65, team1PossChance + 0.03);

        // Kickoff
        timeline.add(createMinuteEvent(1, 0, 0, "kickoff",
                "The referee blows the whistle! " + homeTeamName + " vs " + awayTeamName + " is underway!",
                0, null, 0, null));

        for (int min = 1; min <= 90; min++) {
            boolean team1HasBall = random.nextDouble() < team1PossChance;
            if (team1HasBall) homePossessionMinutes++;

            long attackingTeamId = team1HasBall ? teamId1 : teamId2;
            String attackingTeamName = team1HasBall ? homeTeamName : awayTeamName;
            List<Human> attackers = team1HasBall ? team1Outfield : team2Outfield;
            List<Human> allDefenders = team1HasBall ? team2All : team1All;

            double roll = random.nextDouble();

            if (roll < 0.38) {
                // Possession play - add commentary every ~5 minutes
                if (min % 5 == 0 || min == 1) {
                    String template = POSSESSION_COMMENTARY[random.nextInt(POSSESSION_COMMENTARY.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "commentary",
                            String.format(template, attackingTeamName),
                            0, null, attackingTeamId, attackingTeamName));
                }

            } else if (roll < 0.60) {
                // ATTACK - the core of the match engine
                if (attackers.isEmpty()) continue;
                Human attacker = pickWeightedAttacker(attackers, random);

                if (team1HasBall) homeShots++; else awayShots++;

                double attackRoll = random.nextDouble();

                if (attackRoll < 0.17) {
                    // GOAL!
                    if (team1HasBall) { homeScore++; homeShotsOnTarget++; }
                    else { awayScore++; awayShotsOnTarget++; }

                    String goalDesc = GOAL_DESCRIPTIONS[random.nextInt(GOAL_DESCRIPTIONS.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "goal",
                            "GOAL! " + attacker.getName() + "! " + goalDesc,
                            attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "goal", attacker.getId(), attacker.getName(), attackingTeamId, goalDesc));

                    // 70% chance of assist
                    Human goalAssister = null;
                    if (random.nextDouble() < 0.7 && attackers.size() > 1) {
                        goalAssister = pickDifferentPlayer(attackers, attacker, random);
                        if (goalAssister != null) {
                            dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                    min, "assist", goalAssister.getId(), goalAssister.getName(), attackingTeamId, "Assist"));
                        }
                    }

                    // Generate goal animation (randomly pick type)
                    if (goalAnimations != null) {
                        List<Human> atkAll = team1HasBall ? team1All : team2All;
                        List<Human> defAll = team1HasBall ? team2All : team1All;
                        long defTeamId = team1HasBall ? teamId2 : teamId1;
                        double typeRoll = random.nextDouble();
                        GoalAnimationData anim;
                        if (typeRoll < 0.15) {
                            anim = goalAnimationService.generatePenalty(
                                    atkAll, defAll, attacker,
                                    attackingTeamId, defTeamId, teamId1, min, true);
                        } else if (typeRoll < 0.35) {
                            anim = goalAnimationService.generateFreeKick(
                                    atkAll, defAll, attacker,
                                    attackingTeamId, defTeamId, teamId1, min, "GOAL");
                        } else {
                            anim = goalAnimationService.generate(
                                    atkAll, defAll, attacker, goalAssister,
                                    attackingTeamId, defTeamId, teamId1, min);
                        }
                        if (anim != null) goalAnimations.put(min, anim);
                    }

                } else if (attackRoll < 0.42) {
                    // Shot saved
                    if (team1HasBall) homeShotsOnTarget++; else awayShotsOnTarget++;
                    String saveDesc = SAVE_DESCRIPTIONS[random.nextInt(SAVE_DESCRIPTIONS.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_saved",
                            attacker.getName() + " " + saveDesc,
                            attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                    // 25% chance to show a set piece animation for saves
                    if (goalAnimations != null && random.nextDouble() < 0.25) {
                        List<Human> atkAll = team1HasBall ? team1All : team2All;
                        List<Human> defAll = team1HasBall ? team2All : team1All;
                        long defTeamId = team1HasBall ? teamId2 : teamId1;
                        GoalAnimationData anim;
                        if (random.nextDouble() < 0.4) {
                            anim = goalAnimationService.generatePenalty(
                                    atkAll, defAll, attacker,
                                    attackingTeamId, defTeamId, teamId1, min, false);
                        } else {
                            anim = goalAnimationService.generateFreeKick(
                                    atkAll, defAll, attacker,
                                    attackingTeamId, defTeamId, teamId1, min, "SAVE");
                        }
                        if (anim != null) goalAnimations.put(min, anim);
                    }

                } else if (attackRoll < 0.70) {
                    // Shot wide/high
                    String missDesc = MISS_DESCRIPTIONS[random.nextInt(MISS_DESCRIPTIONS.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_wide",
                            attacker.getName() + " " + missDesc,
                            attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                    // 15% chance to show a free kick miss animation
                    if (goalAnimations != null && random.nextDouble() < 0.15) {
                        List<Human> atkAll = team1HasBall ? team1All : team2All;
                        List<Human> defAll = team1HasBall ? team2All : team1All;
                        long defTeamId = team1HasBall ? teamId2 : teamId1;
                        GoalAnimationData anim = goalAnimationService.generateFreeKick(
                                atkAll, defAll, attacker,
                                attackingTeamId, defTeamId, teamId1, min, "MISS");
                        if (anim != null) goalAnimations.put(min, anim);
                    }

                } else if (attackRoll < 0.87) {
                    // Shot blocked
                    String blockDesc = BLOCK_DESCRIPTIONS[random.nextInt(BLOCK_DESCRIPTIONS.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_blocked",
                            attacker.getName() + blockDesc,
                            attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                } else {
                    // Corner won
                    if (team1HasBall) homeCorners++; else awayCorners++;
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "corner",
                            "Corner kick for " + attackingTeamName + ".",
                            0, null, attackingTeamId, attackingTeamName));

                    // 8% chance of goal from corner
                    if (random.nextDouble() < 0.08 && !attackers.isEmpty()) {
                        Human header = pickWeightedAttacker(attackers, random);
                        if (team1HasBall) { homeScore++; homeShotsOnTarget++; homeShots++; }
                        else { awayScore++; awayShotsOnTarget++; awayShots++; }

                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "goal",
                                "GOAL! " + header.getName() + " rises highest and heads it in from the corner!",
                                header.getId(), header.getName(), attackingTeamId, attackingTeamName));

                        dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                min, "goal", header.getId(), header.getName(), attackingTeamId, "Header from corner"));

                        // Generate goal animation for corner goal
                        if (goalAnimations != null) {
                            List<Human> atkAll = team1HasBall ? team1All : team2All;
                            List<Human> defAll = team1HasBall ? team2All : team1All;
                            long defTeamId = team1HasBall ? teamId2 : teamId1;
                            GoalAnimationData anim = goalAnimationService.generate(
                                    atkAll, defAll, header, null,
                                    attackingTeamId, defTeamId, teamId1, min);
                            if (anim != null) goalAnimations.put(min, anim);
                        }
                    }
                }

            } else if (roll < 0.72) {
                // FOUL
                if (allDefenders.isEmpty()) continue;
                Human fouler = allDefenders.get(random.nextInt(allDefenders.size()));
                if (team1HasBall) awayFouls++; else homeFouls++;

                double cardRoll = random.nextDouble();
                long foulerTeamId = team1HasBall ? teamId2 : teamId1;
                String foulerTeamName = team1HasBall ? awayTeamName : homeTeamName;

                if (cardRoll < 0.22) {
                    // Yellow card
                    if (team1HasBall) awayYellowCards++; else homeYellowCards++;
                    String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "yellow_card",
                            fouler.getName() + " " + foulDesc + " Yellow card!",
                            fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));

                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "yellow_card", fouler.getId(), fouler.getName(), foulerTeamId, "Yellow card"));

                } else if (cardRoll < 0.24) {
                    // Red card
                    if (team1HasBall) awayRedCards++; else homeRedCards++;
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "red_card",
                            "RED CARD! " + fouler.getName() + " is sent off for a terrible challenge!",
                            fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));

                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "red_card", fouler.getId(), fouler.getName(), foulerTeamId, "Red card"));

                } else {
                    // Regular foul
                    if (min % 4 == 0) { // Don't spam too many foul events
                        String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "foul",
                                fouler.getName() + " " + foulDesc,
                                fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                    }
                }

            } else if (roll < 0.76) {
                // OFFSIDE
                if (!attackers.isEmpty()) {
                    Human offside = attackers.get(random.nextInt(attackers.size()));
                    if (team1HasBall) homeOffsides++; else awayOffsides++;
                    if (min % 3 == 0) { // Don't spam too many offside events
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "offside",
                                offside.getName() + " is caught offside. Free kick to the defence.",
                                offside.getId(), offside.getName(), attackingTeamId, attackingTeamName));
                    }
                }
            } else {
                // Buildup play / transitions
                if (min % 7 == 0) {
                    String template = BUILDUP_COMMENTARY[random.nextInt(BUILDUP_COMMENTARY.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "commentary",
                            String.format(template, attackingTeamName),
                            0, null, attackingTeamId, attackingTeamName));
                }
            }

            // Substitutions
            for (int s = 0; s < 3; s++) {
                if (!homeSubs[s] && min == homeSubMinutes[s] && team1All.size() > 11) {
                    homeSubs[s] = true;
                    Human subPlayer = team1All.get(random.nextInt(team1All.size()));
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "substitution",
                            "Substitution for " + homeTeamName + ". " + subPlayer.getName() + " is replaced.",
                            subPlayer.getId(), subPlayer.getName(), teamId1, homeTeamName));
                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "substitution", subPlayer.getId(), subPlayer.getName(), teamId1, "Substitution"));
                }
                if (!awaySubs[s] && min == awaySubMinutes[s] && team2All.size() > 11) {
                    awaySubs[s] = true;
                    Human subPlayer = team2All.get(random.nextInt(team2All.size()));
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "substitution",
                            "Substitution for " + awayTeamName + ". " + subPlayer.getName() + " is replaced.",
                            subPlayer.getId(), subPlayer.getName(), teamId2, awayTeamName));
                    dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "substitution", subPlayer.getId(), subPlayer.getName(), teamId2, "Substitution"));
                }
            }

            // Half time
            if (min == 45) {
                timeline.add(createMinuteEvent(45, homeScore, awayScore, "half_time",
                        "HALF TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                        0, null, 0, null));
            }
        }

        // Full time
        timeline.add(createMinuteEvent(90, homeScore, awayScore, "full_time",
                "FULL TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                0, null, 0, null));

        // Save match events to DB
        if (!dbEvents.isEmpty()) {
            matchEventRepository.saveAll(dbEvents);
        }

        // Build result
        int possessionPct = homePossessionMinutes * 100 / 90;

        LiveMatchData data = new LiveMatchData();
        data.setGoalAnimations(goalAnimations);
        data.setHomeTeamId(teamId1);
        data.setAwayTeamId(teamId2);
        data.setHomeTeamName(homeTeamName);
        data.setAwayTeamName(awayTeamName);
        data.setCompetitionName(competitionName);
        data.setCompetitionId(competitionId);
        data.setRound(round);
        data.setTimeline(timeline);
        data.setHomeScore(homeScore);
        data.setAwayScore(awayScore);
        data.setHomePossession(possessionPct);
        data.setAwayPossession(100 - possessionPct);
        data.setHomeShots(homeShots);
        data.setAwayShots(awayShots);
        data.setHomeShotsOnTarget(homeShotsOnTarget);
        data.setAwayShotsOnTarget(awayShotsOnTarget);
        data.setHomeCorners(homeCorners);
        data.setAwayCorners(awayCorners);
        data.setHomeFouls(homeFouls);
        data.setAwayFouls(awayFouls);
        data.setHomeYellowCards(homeYellowCards);
        data.setAwayYellowCards(awayYellowCards);
        data.setHomeRedCards(homeRedCards);
        data.setAwayRedCards(awayRedCards);
        data.setHomeOffsides(homeOffsides);
        data.setAwayOffsides(awayOffsides);

        // Store in cache
        String key = buildKey(competitionId, season, round, teamId1, teamId2);
        liveMatchCache.put(key, data);

        return data;
    }

    public LiveMatchData getLiveMatchData(String key) {
        return liveMatchCache.get(key);
    }

    public LiveMatchData getLiveMatchData(long competitionId, int season, int round, long teamId1, long teamId2) {
        return liveMatchCache.get(buildKey(competitionId, season, round, teamId1, teamId2));
    }

    public static String buildKey(long competitionId, int season, int round, long teamId1, long teamId2) {
        return competitionId + "_" + season + "_" + round + "_" + teamId1 + "_" + teamId2;
    }

    // ==================== PRIVATE HELPERS ====================

    private List<Human> getOutfieldPlayers(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, 1L).stream()
                .filter(h -> !h.isRetired())
                .filter(h -> !"GK".equals(h.getPosition()))
                .collect(Collectors.toList());
    }

    /**
     * Pick an attacker weighted by rating and position.
     * Forwards have higher weight, midfielders medium, defenders low.
     */
    private Human pickWeightedAttacker(List<Human> players, Random random) {
        if (players.isEmpty()) return null;
        if (players.size() == 1) return players.get(0);

        double totalWeight = 0;
        double[] weights = new double[players.size()];
        for (int i = 0; i < players.size(); i++) {
            Human p = players.get(i);
            double posWeight = getPositionWeight(p.getPosition());
            weights[i] = p.getRating() * posWeight;
            totalWeight += weights[i];
        }

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < players.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) return players.get(i);
        }
        return players.get(players.size() - 1);
    }

    private double getPositionWeight(String position) {
        if (position == null) return 1.0;
        return switch (position) {
            case "ST" -> 3.0;
            case "AMC", "AML", "AMR" -> 2.0;
            case "MC", "ML", "MR" -> 1.2;
            case "DC", "DL", "DR", "DM" -> 0.4;
            default -> 1.0;
        };
    }

    private Human pickDifferentPlayer(List<Human> players, Human exclude, Random random) {
        List<Human> candidates = players.stream()
                .filter(p -> p.getId() != exclude.getId())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private int[] generateSubMinutes(Random random) {
        int[] mins = new int[3];
        mins[0] = 55 + random.nextInt(15); // 55-69
        mins[1] = 65 + random.nextInt(15); // 65-79
        mins[2] = 75 + random.nextInt(11); // 75-85
        Arrays.sort(mins);
        return mins;
    }

    private LiveMatchMinute createMinuteEvent(int minute, int homeScore, int awayScore,
                                               String eventType, String commentary,
                                               long playerId, String playerName,
                                               long teamId, String teamName) {
        LiveMatchMinute event = new LiveMatchMinute();
        event.setMinute(minute);
        event.setHomeScore(homeScore);
        event.setAwayScore(awayScore);
        event.setEventType(eventType);
        event.setCommentary(commentary);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setTeamId(teamId);
        event.setTeamName(teamName);
        return event;
    }

    private MatchEvent buildMatchEvent(long competitionId, int season, int round,
                                        long teamId1, long teamId2,
                                        int minute, String eventType,
                                        long playerId, String playerName,
                                        long teamId, String details) {
        MatchEvent event = new MatchEvent();
        event.setCompetitionId(competitionId);
        event.setSeasonNumber(season);
        event.setRoundNumber(round);
        event.setTeamId1(teamId1);
        event.setTeamId2(teamId2);
        event.setMinute(minute);
        event.setEventType(eventType);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setTeamId(teamId);
        event.setDetails(details);
        return event;
    }
}
