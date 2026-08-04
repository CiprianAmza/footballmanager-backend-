package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GlobalSearchResponse;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GlobalSearchService {

    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;

    public GlobalSearchService(HumanRepository humanRepository,
                               TeamRepository teamRepository,
                               CompetitionRepository competitionRepository) {
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
    }

    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) return GlobalSearchResponse.empty();
        int limit = Math.max(1, Math.min(10, requestedLimit));

        var players = humanRepository
                .findTop10ByTypeIdAndRetiredFalseAndNameContainingIgnoreCaseOrderByNameAsc(
                        TypeNames.PLAYER_TYPE, query);
        Map<Long, Team> teams = teamsById(players.stream()
                .map(Human::getTeamId)
                .filter(Objects::nonNull)
                .toList());

        var playerResults = players.stream().limit(limit)
                .map(player -> new GlobalSearchResponse.Item(
                        player.getId(),
                        player.getName(),
                        playerMeta(player, teams.get(player.getTeamId()))))
                .toList();

        var clubResults = teamRepository
                .findTop10ByNameContainingIgnoreCaseOrderByNameAsc(query).stream().limit(limit)
                .map(team -> new GlobalSearchResponse.Item(
                        team.getId(), team.getName(), "Reputation " + team.getReputation()))
                .toList();

        var competitionResults = competitionRepository
                .findTop10ByNameContainingIgnoreCaseOrderByNameAsc(query).stream().limit(limit)
                .map(competition -> new GlobalSearchResponse.Item(
                        competition.getId(), competition.getName(), competitionMeta(competition)))
                .toList();

        return new GlobalSearchResponse(playerResults, clubResults, competitionResults);
    }

    private Map<Long, Team> teamsById(Collection<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return teamRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
    }

    private String playerMeta(Human player, Team team) {
        String position = player.getPosition() == null || player.getPosition().isBlank()
                ? "Player" : player.getPosition();
        return team == null ? position + " · Free agent" : position + " · " + team.getName();
    }

    private String competitionMeta(Competition competition) {
        if (competition.isLeague()) return "League · Tier " + competition.getTier();
        if (competition.getTypeId() == Competition.CUP) return "Domestic cup";
        return "Competition";
    }
}
