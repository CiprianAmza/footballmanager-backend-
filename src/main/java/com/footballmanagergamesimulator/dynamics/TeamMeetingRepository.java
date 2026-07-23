package com.footballmanagergamesimulator.dynamics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMeetingRepository extends JpaRepository<TeamMeeting, Long> {

    Optional<TeamMeeting> findByTeamIdAndSeasonAndMonthIndex(long teamId, int season, int monthIndex);

    boolean existsByTeamIdAndSeasonAndMonthIndex(long teamId, int season, int monthIndex);

    List<TeamMeeting> findByTeamIdOrderBySeasonDescMonthIndexDesc(long teamId);
}
