package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface TeamCompetitionDetailRepository extends JpaRepository<TeamCompetitionDetail, Long> {

  List<TeamCompetitionDetail> findTeamCompetitionDetailByTeamIdAndCompetitionId(long teamId, long competitionId);

  List<TeamCompetitionDetail> findAllByCompetitionId(long competitionId);

  List<TeamCompetitionDetail> findAllByCompetitionIdAndTeamIdIn(
          long competitionId, Collection<Long> teamIds);

  List<TeamCompetitionDetail> findAllByCompetitionIdIn(Collection<Long> competitionIds);

  default TeamCompetitionDetail findFirstByTeamIdAndCompetitionId(long teamId, long competitionId) {
    List<TeamCompetitionDetail> results = findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
    return results.isEmpty() ? null : results.get(0);
  }
}
