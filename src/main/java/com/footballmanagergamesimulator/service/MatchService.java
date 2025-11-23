package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class MatchService {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;

    public List<ScheduleView> getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List<CompetitionTeamInfoMatch> competitionTeamInfoMatches, long teamId, long seasonNumber) {

        List<ScheduleView> scheduleViews = new ArrayList<>();

        for (CompetitionTeamInfoMatch competitionTeamInfoMatch: competitionTeamInfoMatches) {

            ScheduleView scheduleView = new ScheduleView();
            long opponentTeamId = competitionTeamInfoMatch.getTeam1Id() == teamId ? competitionTeamInfoMatch.getTeam2Id() : competitionTeamInfoMatch.getTeam1Id();
            String opponentTeamName = teamRepository.findNameById(opponentTeamId);
            scheduleView.setOpponentTeam(opponentTeamName);

            String competitionName = competitionRepository.findNameById(competitionTeamInfoMatch.getCompetitionId());
            scheduleView.setCompetitionName(competitionName);

            scheduleView.setHomeOrAway(competitionTeamInfoMatch.getTeam1Id() == teamId ? "H" : "A");

            CompetitionTeamInfoDetail competitionTeamInfoDetail = competitionTeamInfoDetailRepository.findCompetitionTeamInfoDetailByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(competitionTeamInfoMatch.getCompetitionId(), competitionTeamInfoMatch.getRound(), competitionTeamInfoMatch.getTeam1Id(), competitionTeamInfoMatch.getTeam2Id(), seasonNumber);
            String score = "-";
            if (competitionTeamInfoDetail != null) {
                score = competitionTeamInfoDetail.getScore();

                if (scheduleView.getHomeOrAway().equals("A")) {
                    String[] values = score.split("-");
                    ArrayUtils.reverse(values);
                    score = values[0] + "-" + values[1];
                }
            }
            scheduleView.setScore(score);

            scheduleView.setDate(String.valueOf(competitionTeamInfoMatch.getRound()));
            scheduleViews.add(scheduleView);
        }

        return scheduleViews;
    }
}
