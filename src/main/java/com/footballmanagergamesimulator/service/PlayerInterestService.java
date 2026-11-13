package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerPersonality;
import com.footballmanagergamesimulator.model.RelationParty;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.PlayerInterest.Reason;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** Answers "would this player talk to my club?" — the agent's reply on the player page and before personal terms. */
@Service
public class PlayerInterestService {

    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired RelationService relationService;
    @Autowired ContractSignature contractSignature;
    @Autowired AgentRelationService agentRelationService;

    public Reason interest(Human player, long clubId, long managerId) {
        if (agentRelationService.refusesToNegotiate(player.getAgentId(), managerId)) return Reason.AGENT_REFUSES;
        if (player.isWillNeverLeave()) return Reason.ONE_CLUB_PLAYER;

        if (contractSignature.signedRecently(player)) return Reason.JUST_SIGNED;

        return PlayerInterest.byPersonality(PlayerPersonality.of(player), player.getRating(), squadRatings(clubId, player),
                reputationOf(clubId), reputationOf(player.getTeamId()), hasBond(player, clubId, managerId));
    }

    /** He likes the club or its manager (relation at least {@link PlayerInterest#BOND}). */
    public boolean hasBond(Human player, long clubId, long managerId) {
        return relationService.relation(RelationParty.PLAYER, player.getId(), RelationParty.CLUB, clubId) >= PlayerInterest.BOND
                || (managerId > 0 && relationService.relation(RelationParty.PLAYER, player.getId(),
                RelationParty.MANAGER, managerId) >= PlayerInterest.BOND);
    }

    /** Ratings of a club's active players, without the player himself. */
    public List<Double> squadRatings(long clubId, Human player) {
        return humanRepository.findAllByTeamIdAndTypeId(clubId, TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired() && h.getId() != player.getId())
                .map(Human::getRating).toList();
    }

    private int reputationOf(Long teamId) {
        return teamId == null ? 0 : teamRepository.findById(teamId).map(Team::getReputation).orElse(0);
    }
}
