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

        // Per-team attack rate: how often a possession turns into a shot.
        // Was a flat 0.22 for both teams (giving roughly equal shot counts even when
        // power was very uneven — the source of the "12-10 between a top side and a
        // minnow" complaint). Scales with raw power share: a 75/25 power split
        // produces ~12 shots vs ~3 instead of ~13 vs ~7.
        // Tuned alongside big-chance count + cutoffs to keep match goal totals
        // close to the AI-vs-AI engine's ~3.0/match average.
        double rawRatio1 = totalPower > 0 ? power1 / totalPower : 0.5;
        double rawRatio2 = 1.0 - rawRatio1;
        double team1AttackChance = 0.04 + Math.min(0.14, rawRatio1 * 0.18);
        double team2AttackChance = 0.04 + Math.min(0.14, rawRatio2 * 0.18);

        // Big-chance allocation per team: a baseline + a slice from the attacking
        // surplus. Big chances ALWAYS produce an animation regardless of outcome,
        // so users on KEY_MOMENTS see saves and wide shots animated too.
        int team1BigChances = computeBigChances(rawRatio1, random);
        int team2BigChances = computeBigChances(rawRatio2, random);
        // Schedule big-chance minutes uniformly across the match.
        Set<Integer> team1BigChanceMinutes = pickRandomMinutes(team1BigChances, 5, 89, random);
        Set<Integer> team2BigChanceMinutes = pickRandomMinutes(team2BigChances, 5, 89, random);
        // Make sure the two teams don't claim the same minute.
        team2BigChanceMinutes.removeAll(team1BigChanceMinutes);

        // Kickoff
        timeline.add(createMinuteEvent(1, 0, 0, "kickoff",
                "The referee blows the whistle! " + homeTeamName + " vs " + awayTeamName + " is underway!",
                0, null, 0, null));

        for (int min = 1; min <= 90; min++) {
            // Big chances are pre-scheduled per team. On those minutes we force the
            // possession to the scheduled team and force the per-minute roll into
            // the ATTACK branch so the key moment actually lands.
            boolean isHomeBigChance = team1BigChanceMinutes.contains(min);
            boolean isAwayBigChance = team2BigChanceMinutes.contains(min);
            boolean forcedAttack = isHomeBigChance || isAwayBigChance;

            boolean team1HasBall = forcedAttack
                    ? isHomeBigChance
                    : random.nextDouble() < team1PossChance;
            if (team1HasBall) homePossessionMinutes++;

            long attackingTeamId = team1HasBall ? teamId1 : teamId2;
            String attackingTeamName = team1HasBall ? homeTeamName : awayTeamName;
            List<Human> attackers = team1HasBall ? team1Outfield : team2Outfield;
            List<Human> allDefenders = team1HasBall ? team2All : team1All;

            double roll = random.nextDouble();
            // Per-team attack chance — much lower for the weaker side. Layout:
            //   ATTACK (variable) → POSSESSION (0.38) → FOUL (0.10) → OFFSIDE (0.04) → BUILDUP (rest)
            double currentAttackChance = forcedAttack
                    ? 1.0
                    : (team1HasBall ? team1AttackChance : team2AttackChance);
            double attackEnd     = currentAttackChance;
            double possessionEnd = attackEnd + 0.38;
            double foulEnd       = possessionEnd + 0.10;
            double offsideEnd    = foulEnd + 0.04;

            if (roll < attackEnd) {
                // ATTACK - the core of the match engine
                if (!attackers.isEmpty()) {
                    Human attacker = pickWeightedAttacker(attackers, random);

                    if (team1HasBall) homeShots++; else awayShots++;

                    // For big chances we bias toward on-target outcomes and ALWAYS
                    // generate an animation (so KEY_MOMENTS users see them). Conversion
                    // (30%) is a touch above real-world big-chance rate (~38%) to keep
                    // overall goal counts near 3/match. Regular attacks keep the
                    // original 17% conversion.
                    double attackRoll = random.nextDouble();
                    double goalCutoff   = forcedAttack ? 0.30 : 0.17;
                    double saveCutoff   = forcedAttack ? 0.60 : 0.42;
                    double missCutoff   = forcedAttack ? 0.85 : 0.70;
                    double blockedCutoff = forcedAttack ? 0.95 : 0.87;

                    if (attackRoll < goalCutoff) {
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

                        if (goalAnimations != null) {
                            GoalAnimationData anim = buildAttackAnimation(
                                    team1HasBall ? team1All : team2All,
                                    team1HasBall ? team2All : team1All,
                                    attacker, goalAssister,
                                    attackingTeamId, team1HasBall ? teamId2 : teamId1,
                                    teamId1, min, "GOAL", random);
                            if (anim != null) goalAnimations.put(min, anim);
                        }

                    } else if (attackRoll < saveCutoff) {
                        // Shot saved
                        if (team1HasBall) homeShotsOnTarget++; else awayShotsOnTarget++;
                        String saveDesc = SAVE_DESCRIPTIONS[random.nextInt(SAVE_DESCRIPTIONS.length)];
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_saved",
                                attacker.getName() + " " + saveDesc,
                                attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                        // Big chances → always animate. Regular saves → 25% chance.
                        boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.25);
                        if (goalAnimations != null && shouldAnimate) {
                            GoalAnimationData anim = buildAttackAnimation(
                                    team1HasBall ? team1All : team2All,
                                    team1HasBall ? team2All : team1All,
                                    attacker, null,
                                    attackingTeamId, team1HasBall ? teamId2 : teamId1,
                                    teamId1, min, "SAVE", random);
                            if (anim != null) goalAnimations.put(min, anim);
                        }

                    } else if (attackRoll < missCutoff) {
                        // Shot wide/high
                        String missDesc = MISS_DESCRIPTIONS[random.nextInt(MISS_DESCRIPTIONS.length)];
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "shot_wide",
                                attacker.getName() + " " + missDesc,
                                attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

                        boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.15);
                        if (goalAnimations != null && shouldAnimate) {
                            GoalAnimationData anim = buildAttackAnimation(
                                    team1HasBall ? team1All : team2All,
                                    team1HasBall ? team2All : team1All,
                                    attacker, null,
                                    attackingTeamId, team1HasBall ? teamId2 : teamId1,
                                    teamId1, min, "MISS", random);
                            if (anim != null) goalAnimations.put(min, anim);
                        }

                    } else if (attackRoll < blockedCutoff) {
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

                        if (random.nextDouble() < 0.08 && !attackers.isEmpty()) {
                            Human header = pickWeightedAttacker(attackers, random);
                            if (team1HasBall) { homeScore++; homeShotsOnTarget++; homeShots++; }
                            else { awayScore++; awayShotsOnTarget++; awayShots++; }

                            timeline.add(createMinuteEvent(min, homeScore, awayScore, "goal",
                                    "GOAL! " + header.getName() + " rises highest and heads it in from the corner!",
                                    header.getId(), header.getName(), attackingTeamId, attackingTeamName));

                            dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                    min, "goal", header.getId(), header.getName(), attackingTeamId, "Header from corner"));

                            if (goalAnimations != null) {
                                GoalAnimationData anim = goalAnimationService.generate(
                                        team1HasBall ? team1All : team2All,
                                        team1HasBall ? team2All : team1All,
                                        header, null,
                                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                                        teamId1, min, "GOAL");
                                if (anim != null) goalAnimations.put(min, anim);
                            }
                        }
                    }
                }

            } else if (roll < possessionEnd) {
                if (min % 5 == 0 || min == 1) {
                    String template = POSSESSION_COMMENTARY[random.nextInt(POSSESSION_COMMENTARY.length)];
                    timeline.add(createMinuteEvent(min, homeScore, awayScore, "commentary",
                            String.format(template, attackingTeamName),
                            0, null, attackingTeamId, attackingTeamName));
                }

            } else if (roll < foulEnd) {
                // FOUL — defender from the OTHER team commits it
                if (!allDefenders.isEmpty()) {
                    Human fouler = allDefenders.get(random.nextInt(allDefenders.size()));
                    if (team1HasBall) awayFouls++; else homeFouls++;
                    long foulerTeamId = team1HasBall ? teamId2 : teamId1;
                    String foulerTeamName = team1HasBall ? awayTeamName : homeTeamName;
                    double cardRoll = random.nextDouble();
                    if (cardRoll < 0.22) {
                        if (team1HasBall) awayYellowCards++; else homeYellowCards++;
                        String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "yellow_card",
                                fouler.getName() + " " + foulDesc + " Yellow card!",
                                fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                        dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                min, "yellow_card", fouler.getId(), fouler.getName(), foulerTeamId, "Yellow card"));
                    } else if (cardRoll < 0.24) {
                        if (team1HasBall) awayRedCards++; else homeRedCards++;
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "red_card",
                                "RED CARD! " + fouler.getName() + " is sent off for a terrible challenge!",
                                fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                        dbEvents.add(buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                                min, "red_card", fouler.getId(), fouler.getName(), foulerTeamId, "Red card"));
                    } else if (min % 4 == 0) {
                        String foulDesc = FOUL_DESCRIPTIONS[random.nextInt(FOUL_DESCRIPTIONS.length)];
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "foul",
                                fouler.getName() + " " + foulDesc,
                                fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
                    }
                }

            } else if (roll < offsideEnd) {
                if (!attackers.isEmpty()) {
                    Human offside = attackers.get(random.nextInt(attackers.size()));
                    if (team1HasBall) homeOffsides++; else awayOffsides++;
                    if (min % 3 == 0) {
                        timeline.add(createMinuteEvent(min, homeScore, awayScore, "offside",
                                offside.getName() + " is caught offside. Free kick to the defence.",
                                offside.getId(), offside.getName(), attackingTeamId, attackingTeamName));
                    }
                }

            } else {
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

    /**
     * Pick an animation flavour (open play / penalty / free kick) for a given
     * attacker + outcome. Centralised so the GOAL / SAVE / MISS branches stay tidy
     * and so the outcome flag propagates consistently.
     *
     * Distribution: ~15% penalty, ~20% free kick, ~65% open play. Penalty only
     * fires for GOAL/SAVE (no "penalty miss" in the engine yet); MISS routes to
     * free kick or open play.
     */
    private GoalAnimationData buildAttackAnimation(
            List<Human> attackingAll, List<Human> defendingAll,
            Human attacker, Human assister,
            long attackingTeamId, long defendingTeamId,
            long homeTeamId, int minute, String outcome,
            Random random) {
        double typeRoll = random.nextDouble();
        if (!"MISS".equals(outcome) && typeRoll < 0.15) {
            return goalAnimationService.generatePenalty(
                    attackingAll, defendingAll, attacker,
                    attackingTeamId, defendingTeamId, homeTeamId, minute,
                    "GOAL".equals(outcome));
        }
        if (typeRoll < 0.35) {
            return goalAnimationService.generateFreeKick(
                    attackingAll, defendingAll, attacker,
                    attackingTeamId, defendingTeamId, homeTeamId, minute, outcome);
        }
        return goalAnimationService.generate(
                attackingAll, defendingAll, attacker, assister,
                attackingTeamId, defendingTeamId, homeTeamId, minute, outcome);
    }

    /**
     * Big chances allocated to a team per match — biased by their share of total
     * power. A 75% share averages ~2.75 big chances; a 25% share averages ~1.25;
     * 50/50 averages ~2. Capped at 4 to stay close to real-world rates and to
     * stop goal counts ballooning above ~3/match average.
     */
    private int computeBigChances(double rawRatio, Random random) {
        double expected = 0.5 + rawRatio * 3.0;        // 0.5→2.0 ; 1.0→3.5 ; 0.25→1.25
        int floor = (int) Math.floor(expected);
        double frac = expected - floor;
        int extra = random.nextDouble() < frac ? 1 : 0;
        return Math.max(0, Math.min(4, floor + extra));
    }

    /** Pick {@code count} distinct minutes in [minMinute, maxMinute]. */
    private Set<Integer> pickRandomMinutes(int count, int minMinute, int maxMinute, Random random) {
        Set<Integer> minutes = new LinkedHashSet<>();
        int range = maxMinute - minMinute + 1;
        int guard = 0;
        while (minutes.size() < count && guard++ < count * 10) {
            minutes.add(minMinute + random.nextInt(range));
        }
        return minutes;
    }

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
