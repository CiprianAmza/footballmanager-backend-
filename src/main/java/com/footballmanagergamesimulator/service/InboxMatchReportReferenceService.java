package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Adds canonical fixture references to legacy post-match inbox messages in the
 * response without rewriting career data. A reference is attached only when
 * the title identifies exactly one stored match, so unrelated match-result
 * notices (for example derby morale messages) are never guessed.
 */
@Service
public class InboxMatchReportReferenceService {

    private final MatchStatsRepository matchStatsRepository;
    private final TeamRepository teamRepository;

    public InboxMatchReportReferenceService(MatchStatsRepository matchStatsRepository,
                                            TeamRepository teamRepository) {
        this.matchStatsRepository = matchStatsRepository;
        this.teamRepository = teamRepository;
    }

    public List<ManagerInbox> attachMissingReferences(List<ManagerInbox> messages) {
        messages.stream()
                .filter(message -> "match_result".equals(message.getCategory()))
                .filter(message -> message.getDeduplicationKey() == null || message.getDeduplicationKey().isBlank())
                .forEach(this::attachWhenUnambiguous);
        return messages;
    }

    private void attachWhenUnambiguous(ManagerInbox message) {
        List<MatchStats> matches = matchStatsRepository
                .findAllBySeasonNumberAndRoundNumber(message.getSeasonNumber(), message.getRoundNumber())
                .stream()
                .filter(match -> match.getTeam1Id() == message.getTeamId() || match.getTeam2Id() == message.getTeamId())
                .filter(match -> titleMatches(message, match))
                .toList();
        if (matches.size() != 1) return;

        MatchStats match = matches.get(0);
        message.setDeduplicationKey(TeamPostMatchService.matchReportReference(
                match.getCompetitionId(), match.getSeasonNumber(), match.getRoundNumber(),
                match.getTeam1Id(), match.getTeam2Id(), message.getTeamId()));
    }

    private boolean titleMatches(ManagerInbox message, MatchStats match) {
        boolean recipientIsHome = match.getTeam1Id() == message.getTeamId();
        long opponentId = recipientIsHome ? match.getTeam2Id() : match.getTeam1Id();
        String teamName = teamRepository.findNameById(message.getTeamId());
        String opponentName = teamRepository.findNameById(opponentId);
        if (teamName == null || opponentName == null) return false;
        int teamScore = recipientIsHome ? match.getHomeGoals() : match.getAwayGoals();
        int opponentScore = recipientIsHome ? match.getAwayGoals() : match.getHomeGoals();
        String signature = teamName + " " + teamScore + "-" + opponentScore + " " + opponentName;
        return message.getTitle() != null && message.getTitle().contains(signature);
    }
}
