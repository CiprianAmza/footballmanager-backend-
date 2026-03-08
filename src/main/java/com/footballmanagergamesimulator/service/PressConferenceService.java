package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
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

    private static final List<String> PRE_MATCH_QUESTIONS = List.of(
            "How do you rate your chances?",
            "Any injury concerns?",
            "What's the team morale like?",
            "Are you happy with recent form?"
    );

    public PressConference generatePreMatchPressConference(long teamId, long competitionId, int matchday, int season) {

        PressConference pressConference = new PressConference();
        pressConference.setTeamId(teamId);
        pressConference.setSeasonNumber(season);
        pressConference.setDay(matchday);
        pressConference.setMatchDay(matchday);
        pressConference.setTopic("PRE_MATCH:" + String.join("|", PRE_MATCH_QUESTIONS));
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

    public Map<String, Object> respondToPressConference(long pressConferenceId, String responseType) {

        PressConference pressConference = pressConferenceRepository.findById(pressConferenceId)
                .orElseThrow(() -> new RuntimeException("Press conference not found: " + pressConferenceId));

        int moraleEffect = 0;
        int reputationIfWin = 0;
        int reputationIfLose = 0;
        String responseDescription;

        switch (responseType) {
            case "confident":
                moraleEffect = 2;
                reputationIfWin = 1;
                reputationIfLose = -3;
                responseDescription = "The manager expressed full confidence in the team's ability to win.";
                break;
            case "cautious":
                moraleEffect = 0;
                reputationIfWin = 0;
                reputationIfLose = 0;
                responseDescription = "The manager gave a measured, cautious response to the media.";
                break;
            case "aggressive":
                moraleEffect = 3;
                reputationIfWin = 2;
                reputationIfLose = -5;
                responseDescription = "The manager made bold, aggressive statements to the press.";
                break;
            case "deflect":
                moraleEffect = -1;
                reputationIfWin = 0;
                reputationIfLose = 0;
                responseDescription = "The manager deflected all questions from the media.";
                break;
            default:
                throw new RuntimeException("Invalid response type: " + responseType);
        }

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
        pressConference.setReputationEffect(reputationIfWin); // store potential win effect
        pressConferenceRepository.save(pressConference);

        // Send inbox confirmation
        sendInboxMessage(pressConference.getTeamId(), pressConference.getSeasonNumber(),
                pressConference.getDay(),
                "Press Conference Completed",
                responseDescription + " Morale effect: " + (moraleEffect >= 0 ? "+" : "") + moraleEffect + " for all players.",
                "PRESS_CONFERENCE");

        Map<String, Object> result = new HashMap<>();
        result.put("pressConferenceId", pressConferenceId);
        result.put("responseType", responseType);
        result.put("moraleEffect", moraleEffect);
        result.put("reputationIfWin", reputationIfWin);
        result.put("reputationIfLose", reputationIfLose);
        result.put("description", responseDescription);

        return result;
    }

    public List<PressConference> getPendingPressConferences(long teamId, int season) {

        return pressConferenceRepository.findAllByTeamIdAndSeasonNumber(teamId, season)
                .stream()
                .filter(pc -> pc.getResponseChosen() == null)
                .collect(Collectors.toList());
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
