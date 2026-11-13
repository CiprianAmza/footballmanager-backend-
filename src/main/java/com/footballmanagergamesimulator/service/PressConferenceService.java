package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Ownership;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.OwnershipRepository;
import com.footballmanagergamesimulator.repository.PressConferenceRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PressConferenceService {

    @Autowired
    PressConferenceRepository pressConferenceRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    CoachPermissionService coachPermissionService;
    @Autowired
    OwnershipRepository ownershipRepository;
    @Autowired
    MatchEngineConfig engineConfig;

    private static final List<String> PRE_MATCH_QUESTIONS = List.of(
            "How do you rate your chances?",
            "Any injury concerns?",
            "What's the team morale like?",
            "Are you happy with recent form?",
            "Will recent performances influence your team selection?",
            "Do you feel the pressure from the supporters?",
            "Has transfer speculation distracted the squad?",
            "What tactical threat concerns you most about the opposition?",
            "Your side has started recent matches slowly. Is that a mental or tactical problem?",
            "Do you still feel you have the full support of the board?",
            "Reports suggest there is tension in the dressing room. How do you respond?",
            "Have you asked the club for more signings because the squad is too thin?",
            "Are you concerned by the referee appointment for this match?",
            "Is this match decisive for your chances of reaching the stated objective?",
            "Will you rotate the squad, or is the risk too great?",
            "Has one player done enough in training to force his way into the starting eleven?",
            "Are you considering a change of formation after the recent results?",
            "How much could a full stadium influence this match?",
            "Do you expect your players to cope with the pressure of a derby?",
            "The opposition coach has questioned your team. Will that motivate the players?",
            "Would anything other than a win be considered a failure?",
            "Are fatigue and the crowded schedule affecting your selection?",
            "Could an academy player receive an opportunity in this match?",
            "Are contract and transfer rumours affecting any of your key players?",
            "Do you need to protect a player who has been heavily criticised this week?",
            "Are you satisfied with the club's work in the transfer market?",
            "Would you accept an offer from a bigger club or a national team?",
            "What must improve most: finishing, defending, or the team's attitude?"
    );

    private static final List<String> POST_MATCH_QUESTIONS_WIN = List.of(
            "How does this win feel?",
            "Any standout performers today?",
            "What was the turning point?",
            "Can you keep this momentum going?",
            "Was this your most complete performance of the season?",
            "How important was the reaction from the substitutes?",
            "Are expectations around the club rising?",
            "Does this result prove your critics were too quick to judge the team?",
            "Can you now say that your side is a genuine title contender?",
            "Why did you leave an in-form player out of the starting eleven?",
            "Did the referee make the correct decision in the match's controversial moment?",
            "Was the score a fair reflection of the performance?",
            "Does this win change what you need from the transfer market?",
            "How important was the support from the stands tonight?",
            "Did your tactical changes win the match?",
            "Can this victory repair the relationship with disappointed supporters?",
            "Were you surprised by how little the opposition threatened your team?",
            "Is the competition objective now within your control?",
            "Did a player answer the criticism aimed at him before the match?",
            "How do you keep the squad grounded after a result like this?",
            "Was the clean sheet as satisfying as the goals?",
            "Do you expect the board to reward this run with new signings?"
    );

    private static final List<String> POST_MATCH_QUESTIONS_DRAW = List.of(
            "Two points dropped or one earned?",
            "What did you make of the performance?",
            "Where did the game slip away?",
            "What are you taking away from today?",
            "Did your side manage the decisive moments well enough?",
            "Were you satisfied with the refereeing decisions?",
            "Does the team need more attacking options?",
            "Why did you wait so long before making the substitutions?",
            "Did fear of losing stop the team from taking more risks?",
            "Is the lack of efficiency becoming a serious concern?",
            "Was the equaliser conceded because of an individual error or the system?",
            "Do you understand why some supporters expressed their frustration?",
            "Has this draw damaged your chances of reaching the objective?",
            "Does the squad still believe in your ideas?",
            "Did the referee influence the final result?",
            "Would another striker have changed this match?",
            "Are the players struggling physically at this stage of the season?",
            "Was the team too cautious after taking the lead?",
            "How concerned are you by the number of chances being missed?",
            "Will this result force changes to the starting eleven?",
            "Is the pressure around the club affecting the players?",
            "Can you guarantee that the dressing room remains united?"
    );

    private static final List<String> POST_MATCH_QUESTIONS_LOSS = List.of(
            "What went wrong out there?",
            "Is the squad good enough?",
            "Are you worried about your job?",
            "How do you respond from this?",
            "Do you accept responsibility for the tactical plan?",
            "Did the players show enough attitude?",
            "What is your message to the supporters?",
            "Has the dressing room lost confidence?",
            "Do you still have the full backing of the board?",
            "Have you considered resigning after this result?",
            "Is there a rupture between you and the dressing room?",
            "Why did you persist with players who were clearly struggling?",
            "Was the defeat caused by your selection or by the players' execution?",
            "Do you blame the referee, or would that only hide the team's problems?",
            "Were your substitutions made too late to change the match?",
            "Does this defeat show that the club failed in the transfer market?",
            "Can you understand the boos from your own supporters?",
            "Did the team play with fear in the decisive moments?",
            "Is this now a crisis rather than a poor run of form?",
            "Will there be consequences for players who did not show enough commitment?",
            "How can you convince the supporters that the objective is still achievable?",
            "Has a lack of leaders in the squad become a problem?",
            "Are you worried that key players want to leave the club?",
            "What will you change immediately before the next match?",
            "Do you accept that the opposition coach won the tactical battle?"
    );

    /** A pre-match answer replaced by a later conference before it was given. */
    public static final String MISSED = "MISSED";

    /**
     * One pre-match conference for every candidate team that plays this round, not only the first human team
     * found (design DESIGN_MEDIA_SI_COMPETITII §1). The caller picks the candidates (human teams whose manager
     * attends press conferences).
     */
    public List<PressConference> generatePreMatchPressConferences(Collection<Long> candidateTeamIds, long competitionId,
                                                                  int matchday, int legNumber, int season) {
        if (candidateTeamIds == null || candidateTeamIds.isEmpty() || competitionId <= 0 || matchday <= 0) {
            return List.of();
        }
        List<CompetitionTeamInfoMatch> roundMatches = competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(competitionId, matchday, String.valueOf(season));
        List<PressConference> generated = new ArrayList<>();
        for (long teamId : candidateTeamIds) {
            boolean plays = roundMatches.stream().anyMatch(m -> m.getTeam1Id() == teamId || m.getTeam2Id() == teamId);
            if (plays) generated.add(generatePreMatchPressConference(teamId, competitionId, matchday, legNumber, season));
        }
        return generated;
    }

    public PressConference generatePreMatchPressConference(long teamId, long competitionId, int matchday, int season) {
        return generatePreMatchPressConference(teamId, competitionId, matchday, 0, season);
    }

    public PressConference generatePreMatchPressConference(long teamId, long competitionId, int matchday,
                                                           int legNumber, int season) {
        // An older pre-match conference still unanswered can no longer be answered: its match is gone or replaced.
        List<PressConference> missed = pressConferenceRepository.findAllByTeamIdAndSeasonNumber(teamId, season).stream()
                .filter(pc -> pc.getResponseChosen() == null && isPreMatch(pc))
                .toList();
        missed.forEach(pc -> pc.setResponseChosen(MISSED));
        if (!missed.isEmpty()) pressConferenceRepository.saveAll(missed);

        PressConference pressConference = new PressConference();
        pressConference.setTeamId(teamId);
        pressConference.setSeasonNumber(season);
        pressConference.setDay(matchday);
        pressConference.setMatchDay(matchday);
        pressConference.setCompetitionId(competitionId);
        pressConference.setLegNumber(legNumber);
        String question = selectQuestion(PRE_MATCH_QUESTIONS, teamId, competitionId, matchday, season, "PRE_MATCH");
        pressConference.setTopic("PRE_MATCH:" + question);
        pressConference.setResponseChosen(null); // null means PENDING
        pressConference.setMoraleEffect(0);
        pressConference.setReputationEffect(0);

        pressConferenceRepository.save(pressConference);

        // Send inbox message to the manager
        sendInboxMessage(teamId, season, matchday,
                "Press Conference Scheduled",
                "A pre-match press conference has been scheduled. The media wants to know your thoughts before the upcoming match.",
                "PRESS_CONFERENCE");

        return pressConference;
    }

    /**
     * Generate a post-match press conference. Question pool is chosen by
     * outcome — winners get celebratory prompts, losers get hostile ones.
     * Returns the saved PressConference; caller is responsible for surfacing
     * its id to the frontend so it can be chained after the live-match modal.
     */
    public PressConference generatePostMatchPressConference(long teamId, long competitionId,
                                                            int matchday, int season,
                                                            int teamScore, int opponentScore) {
        List<String> questions;
        String outcome;
        if (teamScore > opponentScore) { outcome = "WIN"; questions = POST_MATCH_QUESTIONS_WIN; }
        else if (teamScore < opponentScore) { outcome = "LOSS"; questions = POST_MATCH_QUESTIONS_LOSS; }
        else { outcome = "DRAW"; questions = POST_MATCH_QUESTIONS_DRAW; }

        PressConference pressConference = new PressConference();
        pressConference.setTeamId(teamId);
        pressConference.setSeasonNumber(season);
        pressConference.setDay(matchday);
        pressConference.setMatchDay(matchday);
        pressConference.setCompetitionId(competitionId);
        String question = selectQuestion(questions, teamId, competitionId, matchday, season, "POST_MATCH:" + outcome);
        pressConference.setTopic("POST_MATCH:" + outcome + "|" + question);
        pressConference.setResponseChosen(null);
        pressConference.setMoraleEffect(0);
        pressConference.setReputationEffect(0);

        pressConferenceRepository.save(pressConference);

        sendInboxMessage(teamId, season, matchday,
                "Post-Match Press Conference",
                "The media are waiting to speak with you after the match (" + teamScore + "-" + opponentScore + ").",
                "PRESS_CONFERENCE");

        return pressConference;
    }

    static String selectQuestion(List<String> questions, long teamId, long competitionId,
                                 int matchday, int season, String phase) {
        int index = Math.floorMod(Objects.hash(teamId, competitionId, matchday, season, phase), questions.size());
        return questions.get(index);
    }

    public String questionFor(PressConference pressConference) {
        if (pressConference == null || pressConference.getTopic() == null) return "";
        String topic = pressConference.getTopic();
        if (topic.startsWith("POST_MATCH:")) {
            int separator = topic.indexOf('|');
            return separator >= 0 && separator + 1 < topic.length()
                    ? firstQuestion(topic.substring(separator + 1)) : "";
        }
        int separator = topic.indexOf(':');
        return firstQuestion(separator >= 0 && separator + 1 < topic.length()
                ? topic.substring(separator + 1) : topic);
    }

    private String firstQuestion(String value) {
        int legacySeparator = value.indexOf('|');
        return legacySeparator >= 0 ? value.substring(0, legacySeparator) : value;
    }

    static List<String> questionCatalog(String phase) {
        return switch (phase) {
            case "PRE_MATCH" -> PRE_MATCH_QUESTIONS;
            case "WIN" -> POST_MATCH_QUESTIONS_WIN;
            case "DRAW" -> POST_MATCH_QUESTIONS_DRAW;
            case "LOSS" -> POST_MATCH_QUESTIONS_LOSS;
            default -> List.of();
        };
    }

    private static final String BOARDROOM_COACH_QUESTION =
            "Te deranjează că patronul îți impune deciziile clubului?";
    private static final String BOARDROOM_OWNER_QUESTION =
            "E normal să treci peste antrenorul tău?";

    /**
     * Boardroom press conference (Faza 6): fired when the owner restricts the coach. Applies a
     * baseline arrogance/humiliation bump scaled by how much the owner restricts, then offers
     * coach + owner questions whose responses move the metrics further. Returns null (no
     * conference) when the owner imposes no restrictions on this team.
     */
    public PressConference generateBoardroomPressConference(long teamId, int season, int day) {
        int offToggles = coachPermissionService.countOffToggles(teamId);
        int lockedSlots = coachPermissionService.lockedSlots(teamId).size();
        if (offToggles == 0 && lockedSlots == 0) {
            return null;
        }
        MatchEngineConfig.Boardroom cfg = engineConfig.getBoardroom();

        Human owner = findOwner(teamId);
        if (owner != null) {
            bump(owner, true, offToggles * cfg.getArrogancePerRestriction());
            humanRepository.save(owner);
        }
        Human coach = findCoach(teamId);
        if (coach != null) {
            double humInc = offToggles * cfg.getHumiliationPerRestriction()
                    + lockedSlots * cfg.getHumiliationPerLockedSlot();
            bump(coach, false, humInc);
            humanRepository.save(coach);
            applyHumiliationConsequences(teamId, coach, season, day);
        }

        PressConference pressConference = new PressConference();
        pressConference.setTeamId(teamId);
        pressConference.setSeasonNumber(season);
        pressConference.setDay(day);
        pressConference.setMatchDay(day);
        pressConference.setTopic("BOARDROOM:|" + BOARDROOM_COACH_QUESTION + "|" + BOARDROOM_OWNER_QUESTION);
        pressConference.setResponseChosen(null);
        pressConference.setMoraleEffect(0);
        pressConference.setReputationEffect(0);
        pressConferenceRepository.save(pressConference);

        sendInboxMessage(teamId, season, day, "Întrebări despre patronat",
                "Presa vrea reacția ta față de implicarea patronului în deciziile clubului.",
                "COACH_COMPLAINT");
        return pressConference;
    }

    public Map<String, Object> respondToPressConference(long pressConferenceId, String responseType) {

        PressConference pressConference = pressConferenceRepository.findById(pressConferenceId)
                .orElseThrow(() -> new NoSuchElementException("Press conference not found: " + pressConferenceId));
        requireUnanswered(pressConference);

        if (pressConference.getTopic() != null && pressConference.getTopic().startsWith("BOARDROOM")) {
            return respondToBoardroom(pressConference, responseType);
        }

        int moraleEffect;
        String responseDescription;

        switch (responseType == null ? "" : responseType) {
            case "confident":
                moraleEffect = 2;
                responseDescription = "The manager expressed full confidence in the team's ability to win.";
                break;
            case "cautious":
                moraleEffect = 0;
                responseDescription = "The manager gave a measured, cautious response to the media.";
                break;
            case "aggressive":
                moraleEffect = 3;
                responseDescription = "The manager made bold, aggressive statements to the press.";
                break;
            case "deflect":
                moraleEffect = -1;
                responseDescription = "The manager deflected all questions from the media.";
                break;
            default:
                throw new IllegalArgumentException("Invalid response type: " + responseType);
        }
        // Only a pre-match answer is a bet on a result still to come; it is settled after the match.
        int[] bet = isPreMatch(pressConference) ? reputationBet(responseType) : new int[]{0, 0};
        int reputationIfWin = bet[0];
        int reputationIfLose = bet[1];

        // Apply morale effect to all team players
        List<Human> teamPlayers = humanRepository.findAllByTeamIdAndTypeId(pressConference.getTeamId(), TypeNames.PLAYER_TYPE);
        for (Human player : teamPlayers) {
            double newMorale = Math.max(0, Math.min(100, player.getMorale() + moraleEffect));
            player.setMorale(newMorale);
        }
        humanRepository.saveAll(teamPlayers);

        // Update press conference
        pressConference.setResponseChosen(responseType);
        pressConference.setMoraleEffect(moraleEffect);
        pressConference.setReputationEffect(0); // the real change is known only after the match
        pressConferenceRepository.save(pressConference);

        String mediaHeadline = switch (responseType) {
            case "confident" -> "Media reaction: manager refuses to lower expectations";
            case "aggressive" -> "Media reaction: bold words raise the stakes";
            case "deflect" -> "Media reaction: manager shuts down the questions";
            default -> "Media reaction: measured message from the manager";
        };
        String mediaContext = switch (responseType) {
            case "confident" -> "The studio panel viewed the response as a public show of faith in the squad.";
            case "aggressive" -> "Analysts warned that the statement could inspire the team, but also increase pressure before the next result.";
            case "deflect" -> "The refusal to engage became the main talking point, with attention now shifting to the dressing room.";
            default -> "Commentators described the tone as controlled and designed to keep attention on the next match.";
        };

        // Turn the chosen response into a media story, not only an internal receipt.
        sendInboxMessage(pressConference.getTeamId(), pressConference.getSeasonNumber(),
                pressConference.getDay(),
                mediaHeadline,
                responseDescription + "\n\n" + mediaContext + "\n\nSquad morale effect: "
                        + (moraleEffect >= 0 ? "+" : "") + moraleEffect + ".",
                "MEDIA_REACTION");

        Map<String, Object> result = new HashMap<>();
        result.put("pressConferenceId", pressConferenceId);
        result.put("responseType", responseType);
        result.put("question", questionFor(pressConference));
        result.put("moraleEffect", moraleEffect);
        result.put("reputationIfWin", reputationIfWin);
        result.put("reputationIfLose", reputationIfLose);
        result.put("description", responseDescription);

        return result;
    }

    /** A conference can be answered once; a pre-match one replaced by a later conference has expired. */
    private static void requireUnanswered(PressConference pressConference) {
        String chosen = pressConference.getResponseChosen();
        if (chosen == null) return;
        throw new IllegalStateException(MISSED.equals(chosen) ? "EXPIRED" : "ALREADY_ANSWERED");
    }

    static boolean isPreMatch(PressConference pressConference) {
        return pressConference.getTopic() != null && pressConference.getTopic().startsWith("PRE_MATCH");
    }

    /** {reputation on a win, reputation on a defeat} staked by a pre-match answer; a draw changes nothing. */
    static int[] reputationBet(String responseType) {
        if (responseType == null) return new int[]{0, 0};
        return switch (responseType) {
            case "confident" -> new int[]{1, -3};
            case "aggressive" -> new int[]{2, -5};
            default -> new int[]{0, 0};
        };
    }

    /**
     * The team's pre-match conference still to answer before its match is played, or null. Shown to its own
     * manager by the frontend, whoever pressed Continue (and in multiplayer rooms, where Continue never pauses).
     */
    public PressConference pendingPreMatch(long teamId, int season) {
        return pressConferenceRepository.findAllByTeamIdAndSeasonNumber(teamId, season).stream()
                .filter(pc -> pc.getResponseChosen() == null && isPreMatch(pc) && pc.getCompetitionId() > 0)
                .max(Comparator.comparingLong(PressConference::getId))
                .filter(this::matchStillToPlay)
                .orElse(null);
    }

    /** Whether any of these teams still has a pre-match conference to answer before its match. */
    public boolean anyPendingPreMatch(Collection<Long> teamIds, int season) {
        return teamIds.stream().anyMatch(teamId -> pendingPreMatch(teamId, season) != null);
    }

    private boolean matchStillToPlay(PressConference pc) {
        return competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(pc.getCompetitionId(), pc.getMatchDay(),
                        String.valueOf(pc.getSeasonNumber()))
                .stream()
                .anyMatch(m -> (m.getTeam1Id() == pc.getTeamId() || m.getTeam2Id() == pc.getTeamId())
                        && (pc.getLegNumber() == 0 || m.getLegNumber() == pc.getLegNumber())
                        && m.getTeam1Score() < 0);
    }

    /**
     * Settles the reputation staked by the pre-match answer once the match is played (design
     * DESIGN_MEDIA_SI_COMPETITII §1): confident +1 / -3, aggressive +2 / -5, a draw nothing. The conference is the
     * latest pre-match one of the team for this competition and round (the second leg of a tie has its own);
     * a settled conference keeps its change and is never settled again. Returns the change.
     */
    public int settleReputationBet(long teamId, long competitionId, int round, int season,
                                   int teamScore, int opponentScore) {
        PressConference pc = pressConferenceRepository.findAllByTeamIdAndSeasonNumber(teamId, season).stream()
                .filter(c -> isPreMatch(c) && c.getCompetitionId() == competitionId && c.getMatchDay() == round)
                .max(Comparator.comparingLong(PressConference::getId))
                .orElse(null);
        if (pc == null || pc.getReputationEffect() != 0) return 0;
        int[] bet = reputationBet(pc.getResponseChosen());
        int change = teamScore > opponentScore ? bet[0] : teamScore < opponentScore ? bet[1] : 0;
        if (change == 0) return 0;
        Human manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .min(Comparator.comparingLong(Human::getId))
                .orElse(null);
        if (manager == null) return 0;
        manager.setManagerReputation(Math.max(0, Math.min(10_000, manager.getManagerReputation() + change)));
        humanRepository.save(manager);
        pc.setReputationEffect(change);
        pressConferenceRepository.save(pc);
        if (Math.abs(change) >= 2) {
            boolean won = change > 0;
            sendInboxMessage(teamId, season, round,
                    won ? "Media reaction: bold words backed up by the result"
                        : "Media reaction: pre-match words come back to haunt the manager",
                    (won ? "Before kick-off the manager promised a statement. The result delivered it, and the media "
                            + "now treat the confidence as justified."
                         : "The confident message before kick-off is now being quoted back after the defeat. "
                            + "Expect the same words to be raised at the next press conference.")
                            + "\n\nManager reputation: " + (won ? "+" : "") + change + ".",
                    "MEDIA_REACTION");
        }
        return change;
    }

    public List<PressConference> getPendingPressConferences(long teamId, int season) {

        return pressConferenceRepository.findAllByTeamIdAndSeasonNumber(teamId, season)
                .stream()
                .filter(pc -> pc.getResponseChosen() == null)
                .collect(Collectors.toList());
    }

    /**
     * Handle a boardroom press response. Coach answers ("accept"/"resent") move coach humiliation;
     * owner answers ("humble"/"assertive") move owner arrogance. Assertive/resentful answers push
     * the metric up, conciliatory answers ease it down.
     */
    private Map<String, Object> respondToBoardroom(PressConference pressConference, String responseType) {
        long teamId = pressConference.getTeamId();
        MatchEngineConfig.Boardroom cfg = engineConfig.getBoardroom();
        double delta = 0;
        String who = "";
        String description;

        switch (responseType) {
            case "resent" -> { delta = cfg.getPressHumiliationDelta(); who = "coach"; description = "Antrenorul și-a exprimat frustrarea față de ingerința patronului."; }
            case "accept" -> { delta = -cfg.getPressHumiliationDelta() * 0.5; who = "coach"; description = "Antrenorul a acceptat public rolul patronului."; }
            case "assertive" -> { delta = cfg.getPressArroganceDelta(); who = "owner"; description = "Patronul a reafirmat că el ia deciziile."; }
            case "humble" -> { delta = -cfg.getPressArroganceDelta() * 0.5; who = "owner"; description = "Patronul a dat credit antrenorului."; }
            default -> throw new IllegalArgumentException("Invalid boardroom response type: " + responseType);
        }

        double arroganceAfter = 0;
        double humiliationAfter = 0;
        if ("coach".equals(who)) {
            Human coach = findCoach(teamId);
            if (coach != null) {
                bump(coach, false, delta);
                humanRepository.save(coach);
                humiliationAfter = coach.getCoachHumiliation();
                applyHumiliationConsequences(teamId, coach, pressConference.getSeasonNumber(), pressConference.getDay());
            }
        } else {
            Human owner = findOwner(teamId);
            if (owner != null) {
                bump(owner, true, delta);
                humanRepository.save(owner);
                arroganceAfter = owner.getOwnerArrogance();
            }
        }

        pressConference.setResponseChosen(responseType);
        pressConferenceRepository.save(pressConference);

        sendInboxMessage(teamId, pressConference.getSeasonNumber(), pressConference.getDay(),
                "Reacție la patronat", description,
                "owner".equals(who) ? "OWNER_DECISION" : "COACH_COMPLAINT");

        Map<String, Object> result = new HashMap<>();
        result.put("pressConferenceId", pressConference.getId());
        result.put("responseType", responseType);
        result.put("target", who);
        result.put("ownerArrogance", arroganceAfter);
        result.put("coachHumiliation", humiliationAfter);
        result.put("description", description);
        return result;
    }

    /** When coach humiliation crosses the configured threshold, log a complaint and dent squad morale. */
    private void applyHumiliationConsequences(long teamId, Human coach, int season, int day) {
        double threshold = engineConfig.getBoardroom().getCoachLeaveHumiliationThreshold();
        if (coach.getCoachHumiliation() < threshold) return;

        int hit = engineConfig.getBoardroom().getSquadMoraleHitAtHighHumiliation();
        if (hit != 0) {
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player : players) {
                player.setMorale(Math.max(0, Math.min(100, player.getMorale() - hit)));
            }
            humanRepository.saveAll(players);
        }
        sendInboxMessage(teamId, season, day, "Antrenorul e nemulțumit",
                coach.getName() + " s-a săturat de ingerința patronului și cere reducerea restricțiilor. "
                        + "Moralul lotului a scăzut.",
                "COACH_COMPLAINT");
    }

    private Human findCoach(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)
                .stream().findFirst().orElse(null);
    }

    private Human findOwner(long teamId) {
        for (Ownership o : ownershipRepository.findAllByTeamId(teamId)) {
            Human owner = humanRepository.findById(o.getHumanId()).orElse(null);
            if (owner != null) return owner;
        }
        return null;
    }

    /** Move a 0-100 metric (owner arrogance or coach humiliation) by delta, clamped. */
    private void bump(Human human, boolean arrogance, double delta) {
        if (arrogance) {
            human.setOwnerArrogance(clamp(human.getOwnerArrogance() + delta));
        } else {
            human.setCoachHumiliation(clamp(human.getCoachHumiliation() + delta));
        }
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private void sendInboxMessage(long teamId, int season, int day, String title, String content, String category) {

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }
}
