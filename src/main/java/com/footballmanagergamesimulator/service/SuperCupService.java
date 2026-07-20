package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Creates and seeds each nation's one-match Super Cup. */
@Service
public class SuperCupService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final CompetitionTeamInfoDetailRepository resultRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;

    public SuperCupService(CompetitionRepository competitionRepository,
                           CompetitionHistoryRepository historyRepository,
                           CompetitionTeamInfoDetailRepository resultRepository,
                           CompetitionTeamInfoRepository entryRepository,
                           CompetitionTeamInfoMatchRepository fixtureRepository) {
        this.competitionRepository = competitionRepository;
        this.historyRepository = historyRepository;
        this.resultRepository = resultRepository;
        this.entryRepository = entryRepository;
        this.fixtureRepository = fixtureRepository;
    }

    /** Adds missing competitions without changing IDs of the existing 17 competitions. */
    @Transactional
    public void ensureCompetitions() {
        List<Competition> all = competitionRepository.findAll();
        List<Competition> additions = new ArrayList<>();
        for (Competition league : all) {
            if (league.getTypeId() != 1) continue;
            boolean exists = all.stream().anyMatch(c -> c.getTypeId() == 6
                    && c.getNationId() == league.getNationId());
            if (exists) continue;
            Competition superCup = new Competition();
            superCup.setNationId(league.getNationId());
            superCup.setPrizesId(league.getPrizesId());
            superCup.setTypeId(6);
            superCup.setName(countryName(league.getName()) + " Super Cup");
            additions.add(superCup);
        }
        if (!additions.isEmpty()) competitionRepository.saveAll(additions);
    }

    /**
     * Seeds season N from season N-1. Champion plays cup winner; when they are
     * the same club, the league runner-up takes the second place.
     */
    @Transactional
    public void prepareSeason(int targetSeason) {
        if (targetSeason <= 1) return;
        ensureCompetitions();
        int sourceSeason = targetSeason - 1;
        List<Competition> all = competitionRepository.findAll();
        for (Competition superCup : all) {
            if (superCup.getTypeId() != 6) continue;
            Competition league = all.stream().filter(c -> c.getTypeId() == 1
                    && c.getNationId() == superCup.getNationId()).findFirst().orElse(null);
            Competition cup = all.stream().filter(c -> c.getTypeId() == 2
                    && c.getNationId() == superCup.getNationId()).findFirst().orElse(null);
            if (league == null || cup == null) continue;

            List<CompetitionHistory> leagueTable = historyRepository.findByCompetitionId(league.getId()).stream()
                    .filter(h -> h.getSeasonNumber() == sourceSeason)
                    .sorted(Comparator.comparingLong(CompetitionHistory::getLastPosition)).toList();
            if (leagueTable.size() < 2) continue;
            long champion = leagueTable.get(0).getTeamId();
            long cupWinner = cupWinner(cup.getId(), sourceSeason);
            if (cupWinner <= 0) continue;
            long opponent = cupWinner == champion ? leagueTable.get(1).getTeamId() : cupWinner;
            saveSeason(superCup.getId(), targetSeason, champion, opponent);
        }
    }

    private long cupWinner(long cupId, int season) {
        return resultRepository.findAllByCompetitionIdAndSeasonNumber(cupId, season).stream()
                .filter(r -> r.getWinnerTeamId() != null && !"FIRST_LEG".equals(r.getDecidedBy()))
                .max(Comparator.comparingLong(CompetitionTeamInfoDetail::getRoundId)
                        .thenComparingLong(CompetitionTeamInfoDetail::getId))
                .map(CompetitionTeamInfoDetail::getWinnerTeamId).orElse(0L);
    }

    private void saveSeason(long competitionId, int season, long home, long away) {
        boolean fixtureExists = !fixtureRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                competitionId, 1, String.valueOf(season)).isEmpty();
        if (fixtureExists) return;
        List<CompetitionTeamInfo> entries = new ArrayList<>();
        for (long teamId : List.of(home, away)) {
            CompetitionTeamInfo entry = new CompetitionTeamInfo();
            entry.setCompetitionId(competitionId);
            entry.setSeasonNumber(season);
            entry.setRound(1);
            entry.setTeamId(teamId);
            entries.add(entry);
        }
        entryRepository.saveAll(entries);
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(competitionId);
        fixture.setSeasonNumber(String.valueOf(season));
        fixture.setRound(1);
        fixture.setMatchIndex(1);
        fixture.setTeam1Id(home);
        fixture.setTeam2Id(away);
        fixtureRepository.save(fixture);
    }

    private String countryName(String leagueName) {
        return leagueName.replace(" Football First League", "")
                .replace(" First League", "")
                .replace(" Championship", "")
                .replace(" League", "").trim();
    }
}
