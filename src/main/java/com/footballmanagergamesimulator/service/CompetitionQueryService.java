package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionType;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only queries over competitions, results and participants. The bodies
 * used to sit on {@code CompetitionController} as small repository lookups;
 * lifted out so the controller is a pure REST routing layer.
 */
@Service
public class CompetitionQueryService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository;
    private final CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    private final GameStateService gameStateService;

    public CompetitionQueryService(CompetitionRepository competitionRepository,
                                   CompetitionTeamInfoRepository competitionTeamInfoRepository,
                                   CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository,
                                   GameStateService gameStateService) {
        this.competitionRepository = competitionRepository;
        this.competitionTeamInfoRepository = competitionTeamInfoRepository;
        this.competitionTeamInfoDetailRepository = competitionTeamInfoDetailRepository;
        this.gameStateService = gameStateService;
    }

    // ==================== Metadata ====================

    public List<Competition> getAllCompetitions() {
        return competitionRepository.findAll();
    }

    public List<Competition> getAllCompetitionsByTypeId(long typeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == typeId)
                .toList();
    }

    public List<CompetitionType> getAllCompetitionTypes() {
        List<CompetitionType> types = new ArrayList<>();

        CompetitionType championship = new CompetitionType();
        championship.setId(1);
        championship.setTypeName("Championship");
        championship.setTypeId(1);
        types.add(championship);

        CompetitionType cup = new CompetitionType();
        cup.setId(2);
        cup.setTypeName("Cup");
        cup.setTypeId(2);
        types.add(cup);

        return types;
    }

    public String getCompetitionName(long competitionId) {
        return competitionRepository.findById(competitionId)
                .map(Competition::getName)
                .orElse("Unknown Competition");
    }

    public String getCompetitionNameById(long competitionId) {
        return competitionRepository.findNameById(competitionId);
    }

    // ==================== Results ====================

    public List<CompetitionTeamInfoDetail> getResults(long competitionId, long roundId) {
        long currentSeason = gameStateService.currentSeason();
        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getRoundId() == roundId)
                .filter(d -> d.getCompetitionId() == competitionId)
                .filter(d -> d.getSeasonNumber() == currentSeason)
                .toList();
    }

    public List<CompetitionTeamInfoDetail> getResultsBySeason(long competitionId, long roundId, long season) {
        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getRoundId() == roundId)
                .filter(d -> d.getCompetitionId() == competitionId)
                .filter(d -> d.getSeasonNumber() == season)
                .toList();
    }

    public List<CompetitionTeamInfoDetail> getMatchesByCompetitionAndSeason(long competitionId, long season) {
        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && d.getSeasonNumber() == season)
                .toList();
    }

    // ==================== Participants ====================

    public List<Long> getParticipants(long competitionId, long roundId) {
        List<CompetitionTeamInfo> rows = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(roundId, competitionId, gameStateService.currentSeason());

        return new ArrayList<>(rows.stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .collect(Collectors.toSet()));
    }
}
