package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.JobOffer;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import com.footballmanagergamesimulator.util.ManagerTacticPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manager career lifecycle — extracted from {@link SeasonTransitionService}
 * (sesiunea 6, §6.2 Pass A). Three responsibilities, all triggered at end of
 * season after {@code SeasonObjectiveService.evaluateSeasonObjectives}:
 * <ul>
 *   <li>{@link #recordManagerHistory} — write a {@link ManagerHistory} row per
 *       team (games, wins, position, trophies, promoted/relegated) and apply
 *       a reputation delta to the manager based on results + objectives.</li>
 *   <li>{@link #checkManagerFiring} — entry point that evaluates each human
 *       team's manager against board expectations and either fires them
 *       (inbox + user-state mutation) or sends a warning/praise message; then
 *       runs the AI variant via {@link #fireAIManagers}.</li>
 * </ul>
 *
 * <p>Originally bundled with the season transition logic; the split makes
 * the SeasonTransition coordinator smaller and gives manager-career rules a
 * single home where the firing threshold, reputation deltas, and trophy
 * weighting all live together.
 *
 * <p>Caller: {@code SeasonObjectiveService.evaluateSeasonObjectives} — itself
 * called from {@code SeasonTransitionService.processEndOfSeason}.
 */
@Service
public class ManagerCareerService {

    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private ManagerHistoryRepository managerHistoryRepository;
    @Autowired private SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired private UserContext userContext;
    @Autowired private UserRepository userRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private CompositeNameGenerator compositeNameGenerator;
    @Autowired private TacticService tacticService;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired @org.springframework.context.annotation.Lazy private JobOfferService jobOfferService;
    @Autowired @org.springframework.context.annotation.Lazy private HumanService humanService;

    /** Minimum league matches an AI manager must have played before they can be sacked mid-season. */
    private static final int MIN_MATCHES_FOR_SACKING = 10;
    /** How many places below their reputation-predicted rank a manager must sit to be sacked. */
    private static final int POSITION_SHORTFALL_FOR_SACKING = 4;

    // ============================================================
    //  ManagerHistory record + reputation delta
    // ============================================================

    /**
     * For each team with a non-retired manager: build a ManagerHistory row
     * (stats, trophies, promoted/relegated), then adjust the manager's
     * reputation based on league position, trophies won, promotion/relegation,
     * and failed season objectives.
     */
    public void recordManagerHistory(int season, List<TeamCompetitionDetail> allDetails) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<Competition> allCompetitions = competitionRepository.findAll();
        List<CompetitionHistory> allCompHistory = competitionHistoryRepository.findAll().stream()
                .filter(h -> h.getSeasonNumber() == season)
                .toList();

        Set<Long> leagueCompetitionIds = allCompetitions.stream()
                .filter(c -> c.getTypeId() == 1 || c.getTypeId() == 3)
                .map(Competition::getId)
                .collect(Collectors.toSet());

        for (Team team : allTeams) {
            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst().orElse(null);
            if (manager == null) continue;

            TeamCompetitionDetail leagueDetail = allDetails.stream()
                    .filter(d -> d.getTeamId() == team.getId() && leagueCompetitionIds.contains(d.getCompetitionId()))
                    .findFirst().orElse(null);
            if (leagueDetail == null) continue;

            long competitionId = leagueDetail.getCompetitionId();
            List<TeamCompetitionDetail> leagueStandings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == competitionId)
                    .sorted((o1, o2) -> {
                        if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                        if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                        return o2.getGoalsFor() - o1.getGoalsFor();
                    })
                    .toList();

            int leaguePosition = 1;
            for (TeamCompetitionDetail standing : leagueStandings) {
                if (standing.getTeamId() == team.getId()) break;
                leaguePosition++;
            }

            List<String> trophies = new ArrayList<>();
            if (leaguePosition == 1) {
                Competition comp = allCompetitions.stream().filter(c -> c.getId() == competitionId).findFirst().orElse(null);
                if (comp != null) trophies.add(comp.getName());
            }
            for (CompetitionHistory ch : allCompHistory) {
                if (ch.getTeamId() == team.getId() && ch.getLastPosition() == 1) {
                    Competition comp = allCompetitions.stream()
                            .filter(c -> c.getId() == ch.getCompetitionId() && (c.getTypeId() == 2
                                    || c.getTypeId() == 4 || c.getTypeId() == 5 || c.getTypeId() == 6))
                            .findFirst().orElse(null);
                    if (comp != null) trophies.add(comp.getName());
                }
            }

            boolean promoted = false, relegated = false;
            Competition leagueComp = allCompetitions.stream().filter(c -> c.getId() == competitionId).findFirst().orElse(null);
            if (leagueComp != null) {
                int numTeams = leagueStandings.size();
                if (leagueComp.getTypeId() == 3 && leaguePosition <= 2) promoted = true;
                if (leagueComp.getTypeId() == 1 && leaguePosition >= numTeams - 1) relegated = true;
            }

            ManagerHistory mh = new ManagerHistory();
            mh.setManagerId(manager.getId());
            mh.setManagerName(manager.getName());
            mh.setTeamId(team.getId());
            mh.setTeamName(team.getName());
            mh.setSeasonNumber(season);
            mh.setGamesPlayed(leagueDetail.getGames());
            mh.setWins(leagueDetail.getWins());
            mh.setDraws(leagueDetail.getDraws());
            mh.setLosses(leagueDetail.getLoses());
            mh.setGoalsFor(leagueDetail.getGoalsFor());
            mh.setGoalsAgainst(leagueDetail.getGoalsAgainst());
            mh.setLeaguePosition(leaguePosition);
            mh.setTrophiesWon(String.join(",", trophies));
            mh.setPromoted(promoted);
            mh.setRelegated(relegated);
            managerHistoryRepository.save(mh);

            double repChange = 0;
            if (leaguePosition == 1) repChange += 100;
            else if (leaguePosition == 2) repChange += 40;
            else if (leaguePosition == 3) repChange += 20;

            int numTeams = leagueStandings.size();
            if (leaguePosition == numTeams) repChange -= 50;
            else if (leaguePosition >= numTeams - 2) repChange -= 25;

            for (String trophy : trophies) {
                String lower = trophy.toLowerCase();
                if (lower.contains("champions") || lower.contains("loc") || lower.contains("league of champions")) repChange += 500;
                else if (lower.contains("stars")) repChange += 200;
                else if (lower.contains("cup")) repChange += 75;
            }
            if (promoted) repChange += 50;
            if (relegated) repChange -= 200;

            List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            for (SeasonObjective obj : objectives) {
                if ("failed".equals(obj.getStatus())) {
                    if ("critical".equals(obj.getImportance())) repChange -= 50;
                    else if ("high".equals(obj.getImportance())) repChange -= 25;
                    else repChange -= 10;
                }
            }

            double newRep = Math.max(0, Math.min(10000, manager.getManagerReputation() + repChange));
            manager.setManagerReputation((int) newRep);
            humanRepository.save(manager);
        }

        System.out.println("=== MANAGER HISTORY RECORDED FOR SEASON " + season + " ===");
    }

    // ============================================================
    //  Firing decisions — human teams first, then AI
    // ============================================================

    /**
     * Evaluate each human team's manager against board firing thresholds, then
     * delegate to {@link #fireAIManagers} for the AI side.
     */
    public void checkManagerFiring(int season) {
        for (long humanTeamId : userContext.getAllHumanTeamIds()) {
            checkManagerFiringForTeam(humanTeamId, season);
        }
        fireAIManagers(season);
    }

    private void checkManagerFiringForTeam(long humanTeamId, int season) {
        Human currentManager = resolveHumanManager(humanTeamId);
        if (currentManager != null && currentManager.isAlwaysContinue()) {
            // An unattended simulation must not become stranded on the fired/free-agent screen.
            return;
        }

        List<SeasonObjective> humanObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(humanTeamId, season);
        if (humanObjectives.isEmpty()) return;

        int firingScore = 0;
        for (SeasonObjective obj : humanObjectives) {
            if (!"failed".equals(obj.getStatus())) continue;
            int weight = "critical".equals(obj.getImportance()) ? 3 : "high".equals(obj.getImportance()) ? 2 : 1;
            if ("league_position".equals(obj.getObjectiveType())) {
                firingScore += weight * Math.min(obj.getActualValue() - obj.getTargetValue(), 5);
            } else {
                firingScore += weight * Math.min(obj.getTargetValue() - obj.getActualValue(), 3);
            }
        }

        String title, content;
        String category = "board";

        if (firingScore >= 12) {
            title = "You Have Been Sacked!";
            content = "The board has lost all confidence in your ability to manage this club. "
                    + "You have been relieved of your duties with immediate effect. "
                    + "Check the available jobs to find a new position.";
            List<User> usersWithTeam = userRepository.findAllByTeamId(humanTeamId);
            for (User user : usersWithTeam) {
                user.setFired(true);
                user.setLastTeamId(humanTeamId);
                user.setTeamId(null);
                userRepository.save(user);
            }
            List<GameCalendar> nextCalendars = gameCalendarRepository.findBySeason(season + 1);
            if (!nextCalendars.isEmpty()) {
                GameCalendar cal = nextCalendars.get(0);
                cal.setManagerFired(true);
                gameCalendarRepository.save(cal);
            } else {
                List<GameCalendar> calendars = gameCalendarRepository.findBySeason(season);
                if (!calendars.isEmpty()) {
                    GameCalendar cal = calendars.get(0);
                    cal.setManagerFired(true);
                    gameCalendarRepository.save(cal);
                }
            }
            Human humanManager = humanRepository.findAllByTeamIdAndTypeId(humanTeamId, TypeNames.MANAGER_TYPE)
                    .stream().findFirst().orElse(null);
            if (humanManager != null) {
                humanManager.setManagerReputation(Math.max(0, humanManager.getManagerReputation() - 100));
                humanManager.setTeamId(0L);
                humanRepository.save(humanManager);
            }
            System.out.println("=== HUMAN MANAGER FIRED - Game paused for job selection ===");
        } else if (firingScore >= 8) {
            title = "Board Disappointed - Final Warning";
            content = "The board is extremely disappointed with your performance this season. "
                    + "You have failed to meet critical objectives. "
                    + "Another season like this and you will be relieved of your duties.";
        } else if (firingScore >= 5) {
            title = "Board Concerns";
            content = "The board has expressed concerns about your management. "
                    + "Several objectives were not met. You are expected to improve next season.";
        } else if (firingScore > 0) {
            title = "Board Review";
            content = "The board acknowledges a mixed season. Some objectives were not fully met, "
                    + "but they remain supportive of your vision.";
        } else {
            title = "Board Praise";
            content = "The board is delighted with your performance this season! "
                    + "All objectives have been met. Keep up the excellent work!";
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(humanTeamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(50);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    private Human resolveHumanManager(long teamId) {
        Long linkedManagerId = userRepository.findAllByTeamId(teamId).stream()
                .map(User::getManagerId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (linkedManagerId != null) {
            Human linkedManager = humanRepository.findById(linkedManagerId).orElse(null);
            if (linkedManager != null) return linkedManager;
        }
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void fireAIManagers(int season) {
        List<Team> allTeams = teamRepository.findAll();
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);

        for (Team team : allTeams) {
            if (userContext.isHumanTeam(team.getId())) continue;
            Human manager = allManagers.stream()
                    .filter(m -> m.getTeamId() != null && m.getTeamId() == team.getId() && !m.isRetired())
                    .findFirst().orElse(null);
            if (manager == null) continue;

            List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(team.getId(), season);
            int firingScore = 0;
            for (SeasonObjective obj : objectives) {
                if (!"failed".equals(obj.getStatus())) continue;
                int weight = "critical".equals(obj.getImportance()) ? 3 : "high".equals(obj.getImportance()) ? 2 : 1;
                if ("league_position".equals(obj.getObjectiveType())) {
                    firingScore += weight * Math.min(obj.getActualValue() - obj.getTargetValue(), 5);
                } else {
                    firingScore += weight * Math.min(obj.getTargetValue() - obj.getActualValue(), 3);
                }
            }

            if (firingScore >= 12) {
                manager.setTeamId(0L);
                manager.setRetired(true);
                humanRepository.save(manager);

                Human newManager = new Human();
                newManager.setName(compositeNameGenerator.generateName(1L));
                newManager.setTypeId(TypeNames.MANAGER_TYPE);
                newManager.setTeamId(team.getId());
                newManager.setManagerReputation(team.getReputation() / 3);
                newManager.setAge(35 + new Random().nextInt(20));
                newManager.setSeasonCreated(season);
                newManager.setMorale(100D);
                newManager.setFitness(100D);
                newManager.setRating(0);
                String[] kit = tacticService.buildManagerTacticKit((int) newManager.getRating(), new Random());
                newManager.setTacticStyle(kit[0]);
                newManager.setKnownTactics(kit[1]);
                newManager.setAlwaysUseBestPossibleTactic(
                        ManagerTacticPolicy.defaultsToBestPossibleTactic(team.getName()));
                humanRepository.save(newManager);

                System.out.println("=== AI MANAGER FIRED: " + manager.getName() + " from " + team.getName()
                        + " | Replaced by: " + newManager.getName() + " ===");
            }
        }
    }

    // ============================================================
    //  Mid-season AI sackings -> human job offers (B3)
    // ============================================================

    /**
     * Scan AI-managed teams mid-season: if a manager has played enough matches
     * and sits far below their reputation-predicted league rank, sack them and
     * leave the seat OPEN — then offer it to a free-agent human whose reputation
     * is within the club's band. Only if no human is offered (no in-band human,
     * or already pending/cooldown) do we auto-respawn an AI replacement so the
     * simulator never finds a managerless team.
     *
     * <p>Called only after a league matchday. The caller passes the leagues that
     * actually played, so cup-only days and routine calendar advances do no
     * manager-career work. This is the ONLY path that produces opportunistic
     * human offers.
     */
    public void evaluateMidSeasonSackings(int season) {
        Set<Long> leagueCompIds = new HashSet<>(competitionRepository.findIdsByTypeId(1));
        leagueCompIds.addAll(competitionRepository.findIdsByTypeId(3));
        evaluateMidSeasonSackings(season, leagueCompIds);
    }

    /**
     * Evaluate only the league competitions that have just completed a round.
     * All repository reads are scoped to those competitions and the membership
     * rows of the current season. Standings, managers and predicted positions
     * are indexed once, avoiding the previous nested full-history scans.
     */
    public void evaluateMidSeasonSackings(int season, Set<Long> playedLeagueCompetitionIds) {
        if (playedLeagueCompetitionIds == null || playedLeagueCompetitionIds.isEmpty()) return;

        List<CompetitionTeamInfo> currentMemberships = competitionTeamInfoRepository
                .findAllByCompetitionIdInAndSeasonNumber(playedLeagueCompetitionIds, season);
        if (currentMemberships.isEmpty()) return;

        Map<Long, Set<Long>> currentTeamIdsByCompetition = currentMemberships.stream()
                .collect(Collectors.groupingBy(
                        CompetitionTeamInfo::getCompetitionId,
                        Collectors.mapping(CompetitionTeamInfo::getTeamId, Collectors.toSet())));
        Set<Long> currentTeamIds = currentMemberships.stream()
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());

        Map<Long, Team> teamsById = teamRepository.findAllById(currentTeamIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        Map<Long, Human> activeManagersByTeam = humanRepository
                .findAllByTeamIdInAndTypeId(currentTeamIds, TypeNames.MANAGER_TYPE).stream()
                .filter(manager -> manager.getTeamId() != null && !manager.isRetired())
                .collect(Collectors.toMap(
                        Human::getTeamId,
                        manager -> manager,
                        (first, ignored) -> first));

        Map<Long, List<TeamCompetitionDetail>> standingsByCompetition =
                teamCompetitionDetailRepository.findAllByCompetitionIdIn(playedLeagueCompetitionIds).stream()
                        .filter(detail -> currentTeamIdsByCompetition
                                .getOrDefault(detail.getCompetitionId(), Set.of())
                                .contains(detail.getTeamId()))
                        .collect(Collectors.groupingBy(
                                TeamCompetitionDetail::getCompetitionId,
                                LinkedHashMap::new,
                                Collectors.toList()));

        for (Map.Entry<Long, List<TeamCompetitionDetail>> entry : standingsByCompetition.entrySet()) {
            evaluateLeagueSackings(season, entry.getValue(), teamsById, activeManagersByTeam);
        }
    }

    private void evaluateLeagueSackings(
            int season,
            Collection<TeamCompetitionDetail> leagueDetails,
            Map<Long, Team> teamsById,
            Map<Long, Human> activeManagersByTeam) {
        List<TeamCompetitionDetail> standings = leagueDetails.stream()
                .sorted(ManagerCareerService::compareStandingRows)
                .toList();
        if (standings.isEmpty()) return;

        Map<Long, Integer> currentPositions = new HashMap<>();
        for (int index = 0; index < standings.size(); index++) {
            currentPositions.put(standings.get(index).getTeamId(), index + 1);
        }

        List<Team> reputationOrder = standings.stream()
                .map(detail -> teamsById.get(detail.getTeamId()))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Team::getReputation).reversed())
                .toList();
        Map<Long, Integer> predictedPositions = new HashMap<>();
        for (int index = 0; index < reputationOrder.size(); index++) {
            predictedPositions.put(reputationOrder.get(index).getId(), index + 1);
        }

        for (TeamCompetitionDetail leagueDetail : standings) {
            Team team = teamsById.get(leagueDetail.getTeamId());
            if (team == null) continue;
            if (userContext.isHumanTeam(team.getId())) continue;
            if (team.getLastMidSeasonManagerChangeSeason() == season) continue;
            Human manager = activeManagersByTeam.get(team.getId());
            if (manager == null) continue;
            if (leagueDetail.getGames() < MIN_MATCHES_FOR_SACKING) continue;

            int currentPosition = currentPositions.getOrDefault(team.getId(), standings.size());
            int predicted = predictedPositions.getOrDefault(team.getId(), reputationOrder.size());
            if (currentPosition - predicted < POSITION_SHORTFALL_FOR_SACKING) continue;

            // Sack the AI manager — seat stays open for now.
            manager.setTeamId(0L);
            manager.setRetired(true);
            humanRepository.save(manager);
            team.setLastMidSeasonManagerChangeSeason(season);
            teamRepository.save(team);

            boolean offered = offerVacantSeatToHuman(team, season);
            if (!offered) {
                // No human took it — respawn an AI so the team isn't managerless.
                humanService.ensureTeamHasManager(team.getId());
            }
            System.out.println("=== AI MANAGER SACKED MID-SEASON: " + manager.getName() + " from " + team.getName()
                    + " (pos " + currentPosition + " vs predicted " + predicted + ") | seat offered to human: " + offered + " ===");
        }
    }

    private static int compareStandingRows(TeamCompetitionDetail left, TeamCompetitionDetail right) {
        if (left.getPoints() != right.getPoints()) return Integer.compare(right.getPoints(), left.getPoints());
        if (left.getGoalDifference() != right.getGoalDifference()) {
            return Integer.compare(right.getGoalDifference(), left.getGoalDifference());
        }
        return Integer.compare(right.getGoalsFor(), left.getGoalsFor());
    }

    /**
     * Offer a vacant seat to a free-agent human whose reputation is within the
     * club's band, unless they have a pending offer or the club is still in the
     * decline cooldown for them. Returns true if an offer was created.
     */
    private boolean offerVacantSeatToHuman(Team team, int season) {
        for (User user : userRepository.findAll()) {
            // Only free agents (no current team) are eligible for opportunistic offers here.
            if (user.getTeamId() != null) continue;
            if (jobOfferService.userHasPendingOffer(user.getId())) continue;
            if (jobOfferService.isClubInDeclineCooldown(user.getId(), team.getId(), season)) continue;

            int humanRep = humanReputationFor(user);
            // Reputation band: human must be within reach of the club (not absurdly under/over).
            int low = (int) (team.getReputation() * 0.4);
            int high = team.getReputation() + 1500;
            if (humanRep < low || humanRep > high) continue;

            JobOffer offer = jobOfferService.generateOffer(user.getId(), team.getId());
            if (offer != null) return true;
        }
        return false;
    }

    private int humanReputationFor(User user) {
        Human mgr = null;
        if (user.getManagerId() != null) {
            mgr = humanRepository.findById(user.getManagerId()).orElse(null);
        }
        return mgr != null ? mgr.getManagerReputation() : 500;
    }
}
