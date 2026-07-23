package com.footballmanagergamesimulator.dynamics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamMeetingReactionRepository extends JpaRepository<TeamMeetingReaction, Long> {

    List<TeamMeetingReaction> findByMeetingId(long meetingId);

    List<TeamMeetingReaction> findByTeamId(long teamId);
}
